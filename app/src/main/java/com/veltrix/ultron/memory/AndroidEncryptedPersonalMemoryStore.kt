package com.veltrix.ultron.memory

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal const val PERSONAL_MEMORY_KEY_ALIAS = "veltrix.ultron.personal.memory.v1"

class AndroidEncryptedPersonalMemoryStore(context: Context) : PersonalMemoryRepository {
    private val delegate = DurablePersonalMemoryStore(
        AndroidEncryptedPersonalMemoryPersistence(context.applicationContext)
    )

    override fun rememberIf(record: MemoryRecord, canPersist: (MemoryRecord) -> Boolean): MemoryRecord? =
        delegate.rememberIf(record, canPersist)

    override fun recall(kind: MemoryKind?, query: String?): List<MemoryRecord> =
        delegate.recall(kind, query)

    override fun forget(id: String): Boolean = delegate.forget(id)

    override fun clearPrivate(): Int = delegate.clearPrivate()
}

internal interface PersonalMemoryPersistence {
    fun hasUnfinishedMutation(): Boolean
    fun beginMutation()
    fun finishMutation()
    fun load(): List<MemoryRecord>
    fun save(records: List<MemoryRecord>)
}

/** Pure durable repository so corruption/restart/write-failure semantics are JVM-testable. */
internal class DurablePersonalMemoryStore(
    private val persistence: PersonalMemoryPersistence,
    private val clock: () -> Instant = Instant::now
) : PersonalMemoryRepository {
    @Volatile
    private var compromised = false

    @Synchronized
    override fun rememberIf(
        record: MemoryRecord,
        canPersist: (MemoryRecord) -> Boolean
    ): MemoryRecord? {
        if (compromised) return null
        return runCatching {
            val current = strictSnapshot().toMutableList()
            val existing = current.firstOrNull { it.kind == record.kind && it.key == record.key }
            val merged = PersonalMemoryMerge.merge(existing, record, clock())
            if (!canPersist(merged.record)) return@runCatching null

            if (existing != null) current.removeAll { it.id == existing.id }
            check(current.none { it.id == merged.record.id }) { "Duplicate personal memory id" }
            current += merged.record
            check(current.size <= PersonalMemorySnapshotCodec.MAX_RECORDS) {
                "Personal memory retention bound exceeded"
            }
            PersonalMemorySnapshotCodec.validateSnapshot(current)
            persistMutation(current)
            merged.record
        }.getOrElse {
            compromised = true
            null
        }
    }

    @Synchronized
    override fun recall(kind: MemoryKind?, query: String?): List<MemoryRecord> {
        val snapshot = safeSnapshot() ?: return emptyList()
        val now = clock()
        return snapshot
            .asSequence()
            .filterNot { it.isExpired(now) }
            .filter { kind == null || it.kind == kind }
            .filter {
                query.isNullOrBlank() ||
                    it.key.contains(query, ignoreCase = true) ||
                    it.value.contains(query, ignoreCase = true)
            }
            .sortedByDescending { it.updatedAt }
            .toList()
    }

    @Synchronized
    override fun forget(id: String): Boolean {
        if (compromised || !PersonalMemorySnapshotCodec.validIdentity(id)) return false
        return runCatching {
            val current = strictSnapshot().toMutableList()
            val removed = current.removeAll { it.id == id }
            if (!removed) return@runCatching false
            persistMutation(current)
            true
        }.getOrElse {
            compromised = true
            false
        }
    }

    @Synchronized
    override fun clearPrivate(): Int {
        if (compromised) return 0
        return runCatching {
            val current = strictSnapshot()
            val next = current.filterNot { it.isPrivate }
            val removed = current.size - next.size
            if (removed > 0) persistMutation(next)
            removed
        }.getOrElse {
            compromised = true
            0
        }
    }

    internal fun isCompromisedForTest(): Boolean = compromised

    private fun safeSnapshot(): List<MemoryRecord>? {
        if (compromised) return null
        return runCatching { strictSnapshot() }
            .onFailure { compromised = true }
            .getOrNull()
    }

    private fun strictSnapshot(): List<MemoryRecord> {
        check(!persistence.hasUnfinishedMutation()) { "Personal memory mutation was interrupted" }
        return persistence.load().also(PersonalMemorySnapshotCodec::validateSnapshot)
    }

    private fun persistMutation(records: List<MemoryRecord>) {
        persistence.beginMutation()
        try {
            persistence.save(records)
            persistence.finishMutation()
        } catch (error: Throwable) {
            compromised = true
            throw error
        }
    }
}

internal class AndroidEncryptedPersonalMemoryPersistence(
    context: Context
) : PersonalMemoryPersistence {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun hasUnfinishedMutation(): Boolean = prefs.getBoolean(KEY_MUTATION_PENDING, false)

    override fun beginMutation() {
        check(prefs.edit().putBoolean(KEY_MUTATION_PENDING, true).commit()) {
            "Personal memory mutation marker write failed"
        }
    }

    override fun finishMutation() {
        check(prefs.edit().remove(KEY_MUTATION_PENDING).commit()) {
            "Personal memory mutation marker clear failed"
        }
    }

    override fun load(): List<MemoryRecord> {
        val iv = prefs.getString(KEY_IV, null)
        val encrypted = prefs.getString(KEY_CIPHER, null)
        if (iv == null && encrypted == null) return emptyList()
        check(iv != null && encrypted != null) { "Truncated personal memory snapshot" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        val plaintext = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        return PersonalMemorySnapshotCodec.decode(plaintext)
    }

    override fun save(records: List<MemoryRecord>) {
        val plaintext = PersonalMemorySnapshotCodec.encode(records).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext)
        check(
            prefs.edit()
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit()
        ) { "Personal memory persistence commit failed" }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(PERSONAL_MEMORY_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                PERSONAL_MEMORY_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "veltrix.ultron.personal.memory.encrypted.v1"
        const val KEY_IV = "snapshot_iv"
        const val KEY_CIPHER = "snapshot_cipher"
        const val KEY_MUTATION_PENDING = "mutation_pending"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Versioned, bounded plaintext schema inside the AES-GCM authenticated envelope. */
internal object PersonalMemorySnapshotCodec {
    const val VERSION = 1
    const val MAX_RECORDS = 256
    private const val HEADER = "VELTRIX_PERSONAL_MEMORY"
    private const val MAX_ENCODED_CHARS = 4_000_000
    private const val MAX_ID_CHARS = 256

    fun encode(records: List<MemoryRecord>): String {
        validateSnapshot(records)
        return buildString {
            append(HEADER).append('|').append(VERSION)
            records.sortedBy { it.id }.forEach { record ->
                val fields = listOf(
                    record.id,
                    record.kind.name,
                    record.key,
                    record.value,
                    record.confidence.toString(),
                    record.source,
                    record.createdAt.toString(),
                    record.updatedAt.toString(),
                    record.expiresAt?.toString().orEmpty(),
                    record.isPrivate.toString(),
                    record.visibleTo.map { it.name }.sorted().joinToString(",")
                )
                append('\n').append(fields.joinToString("|") { encodeSegment(it) })
            }
        }.also { encoded ->
            check(encoded.length <= MAX_ENCODED_CHARS) { "Personal memory snapshot too large" }
        }
    }

    fun decode(value: String): List<MemoryRecord> {
        check(value.length <= MAX_ENCODED_CHARS) { "Personal memory snapshot too large" }
        val lines = value.lineSequence().filter(String::isNotBlank).toList()
        check(lines.isNotEmpty() && lines.first() == "$HEADER|$VERSION") {
            "Unsupported personal memory snapshot version"
        }
        check(lines.size - 1 <= MAX_RECORDS) { "Personal memory retention bound exceeded" }
        val records = lines.drop(1).map { line ->
            val fields = line.split('|')
            check(fields.size == 11) { "Invalid personal memory record" }
            val decoded = fields.map(::decodeSegment)
            val audience = decoded[10].takeIf(String::isNotEmpty)
                ?.split(',')
                ?.map { name -> MemoryAudience.valueOf(name) }
                ?.toSet()
                ?: emptySet()
            MemoryRecord(
                id = decoded[0],
                kind = MemoryKind.valueOf(decoded[1]),
                key = decoded[2],
                value = decoded[3],
                confidence = decoded[4].toDouble(),
                source = decoded[5],
                createdAt = Instant.parse(decoded[6]),
                updatedAt = Instant.parse(decoded[7]),
                expiresAt = decoded[8].takeIf(String::isNotEmpty)?.let(Instant::parse),
                isPrivate = decoded[9].toBooleanStrict(),
                visibleTo = audience
            )
        }
        validateSnapshot(records)
        return records
    }

    fun validateSnapshot(records: List<MemoryRecord>) {
        check(records.size <= MAX_RECORDS) { "Personal memory retention bound exceeded" }
        val ids = linkedSetOf<String>()
        val semanticKeys = linkedSetOf<String>()
        records.forEach { record ->
            validate(record)
            check(ids.add(record.id)) { "Duplicate personal memory id" }
            check(semanticKeys.add("${record.kind.name}\u001f${record.key}")) {
                "Duplicate personal memory semantic key"
            }
        }
    }

    fun validate(record: MemoryRecord) {
        check(validIdentity(record.id)) { "Invalid personal memory id" }
        check(record.key.isNotBlank() && record.key.length <= PersonalMemoryPersistencePolicy.MAX_KEY_CHARS) {
            "Invalid personal memory key"
        }
        check(record.value.isNotBlank() && record.value.length <= PersonalMemoryPersistencePolicy.MAX_VALUE_CHARS) {
            "Invalid personal memory value"
        }
        check(record.source.isNotBlank() && record.source.length <= PersonalMemoryPersistencePolicy.MAX_SOURCE_CHARS) {
            "Invalid personal memory source"
        }
        check(record.confidence.isFinite() && record.confidence in 0.0..1.0) {
            "Invalid personal memory confidence"
        }
        check(!record.updatedAt.isBefore(record.createdAt)) { "Invalid personal memory timestamps" }
        check(PersonalMemoryPersistencePolicy.sanitize(record) == record) {
            "Unsafe personal memory content reached persistence"
        }
    }

    fun validIdentity(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_ID_CHARS && value.none { it.code < 0x20 || it.code == 0x7f }

    private fun encodeSegment(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val v = byte.toInt() and 0xff
                append(HEX[v ushr 4]).append(HEX[v and 0x0f])
            }
        }
    }

    private fun decodeSegment(value: String): String {
        check(value.length % 2 == 0) { "Invalid personal memory encoding" }
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            val high = Character.digit(value[index * 2], 16)
            val low = Character.digit(value[index * 2 + 1], 16)
            check(high >= 0 && low >= 0) { "Invalid personal memory encoding" }
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private const val HEX = "0123456789abcdef"
}
