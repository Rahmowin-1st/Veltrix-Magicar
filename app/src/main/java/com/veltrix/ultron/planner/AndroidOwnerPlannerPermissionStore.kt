package com.veltrix.ultron.planner

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Durable owner policy storage.
 *
 * Owner HARD DENY / remembered approval scopes are security authority, so they are
 * stored as one versioned AES-GCM authenticated snapshot under a dedicated Android
 * Keystore key. A persisted mutation marker makes interrupted/failed writes fail
 * closed across process restart instead of silently reviving stale ALLOW state.
 */
class AndroidOwnerPlannerPermissionStore(context: Context) : OwnerPlannerPermissionStore {
    private val repository = DurableOwnerPlannerPermissionStore(
        AndroidEncryptedOwnerPlannerPermissionPersistence(context.applicationContext)
    )

    override fun resolve(
        principalId: String,
        deviceId: String,
        targetScope: String,
        actionScope: String,
        riskClass: String
    ): OwnerPlannerPermissionDecision = repository.resolve(
        principalId,
        deviceId,
        targetScope,
        actionScope,
        riskClass
    )

    override fun put(permission: OwnerPlannerPermission) = repository.put(permission)

    override fun revoke(
        principalId: String,
        deviceId: String,
        targetScope: String,
        actionScope: String,
        riskClass: String
    ): Boolean = repository.revoke(
        principalId,
        deviceId,
        targetScope,
        actionScope,
        riskClass
    )

    override fun isHardDenied(
        ownerPrincipalId: String,
        deviceId: String,
        targetScope: String,
        actionScope: String,
        riskClass: String
    ): Boolean = repository.isHardDenied(
        ownerPrincipalId,
        deviceId,
        targetScope,
        actionScope,
        riskClass
    )
}

internal interface OwnerPlannerPermissionPersistence {
    fun hasUnfinishedMutation(): Boolean
    fun beginMutation()
    fun finishMutation()
    fun load(): List<OwnerPlannerPermission>
    fun save(permissions: List<OwnerPlannerPermission>)
}

/**
 * Pure fail-closed repository so corruption/write-interruption behavior can be
 * regression-tested without Android Keystore instrumentation.
 */
internal class DurableOwnerPlannerPermissionStore(
    private val persistence: OwnerPlannerPermissionPersistence
) : OwnerPlannerPermissionStore {
    @Volatile
    private var compromised = false

    override fun resolve(
        principalId: String,
        deviceId: String,
        targetScope: String,
        actionScope: String,
        riskClass: String
    ): OwnerPlannerPermissionDecision {
        val snapshot = safeSnapshot() ?: return OwnerPlannerPermissionDecision.DENY
        return snapshot[key(principalId, deviceId, targetScope, actionScope, riskClass)]?.decision
            ?: OwnerPlannerPermissionDecision.ASK
    }

    override fun isHardDenied(
        ownerPrincipalId: String,
        deviceId: String,
        targetScope: String,
        actionScope: String,
        riskClass: String
    ): Boolean {
        val snapshot = safeSnapshot() ?: return true
        return ownerHardDenyLookups(deviceId, targetScope, actionScope, riskClass).any { candidate ->
            snapshot[
                key(
                    ownerPrincipalId,
                    candidate.deviceId,
                    candidate.targetScope,
                    candidate.actionScope,
                    candidate.riskClass
                )
            ]?.decision == OwnerPlannerPermissionDecision.DENY
        }
    }

    override fun put(permission: OwnerPlannerPermission) {
        if (compromised) return
        runCatching {
            val current = strictSnapshot().toMutableMap()
            current[key(permission)] = permission
            check(current.size <= OwnerPlannerPermissionSnapshotCodec.MAX_RECORDS) {
                "Owner policy retention bound exceeded"
            }
            persistMutation(current.values.toList())
        }.onFailure {
            compromised = true
        }
    }

    override fun revoke(
        principalId: String,
        deviceId: String,
        targetScope: String,
        actionScope: String,
        riskClass: String
    ): Boolean {
        if (compromised) return false
        return runCatching {
            val current = strictSnapshot().toMutableMap()
            val removed = current.remove(key(principalId, deviceId, targetScope, actionScope, riskClass))
                ?: return@runCatching false
            persistMutation(current.values.toList())
            removed != null
        }.getOrElse {
            compromised = true
            false
        }
    }

    internal fun isCompromisedForTest(): Boolean = compromised

    private fun safeSnapshot(): Map<String, OwnerPlannerPermission>? {
        if (compromised) return null
        return runCatching { strictSnapshot() }
            .onFailure { compromised = true }
            .getOrNull()
    }

    private fun strictSnapshot(): Map<String, OwnerPlannerPermission> {
        check(!persistence.hasUnfinishedMutation()) { "Owner policy mutation was interrupted" }
        val records = persistence.load()
        check(records.size <= OwnerPlannerPermissionSnapshotCodec.MAX_RECORDS) {
            "Owner policy retention bound exceeded"
        }
        val map = linkedMapOf<String, OwnerPlannerPermission>()
        records.forEach { permission ->
            OwnerPlannerPermissionSnapshotCodec.validate(permission)
            check(map.put(key(permission), permission) == null) { "Duplicate owner policy scope" }
        }
        return map
    }

    private fun persistMutation(records: List<OwnerPlannerPermission>) {
        persistence.beginMutation()
        try {
            persistence.save(records)
            persistence.finishMutation()
        } catch (error: Throwable) {
            compromised = true
            throw error
        }
    }

    private fun key(permission: OwnerPlannerPermission): String = key(
        permission.principalId,
        permission.deviceId,
        permission.targetScope,
        permission.actionScope,
        permission.riskClass
    )

    private fun key(
        principalId: String,
        deviceId: String,
        targetScope: String,
        actionScope: String,
        riskClass: String
    ): String = listOf(principalId, deviceId, targetScope, actionScope, riskClass).joinToString("\u001f")
}

internal class AndroidEncryptedOwnerPlannerPermissionPersistence(
    context: Context
) : OwnerPlannerPermissionPersistence {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    override fun hasUnfinishedMutation(): Boolean = prefs.getBoolean(KEY_MUTATION_PENDING, false)

    override fun beginMutation() {
        check(prefs.edit().putBoolean(KEY_MUTATION_PENDING, true).commit()) {
            "Owner policy mutation marker write failed"
        }
    }

    override fun finishMutation() {
        check(prefs.edit().remove(KEY_MUTATION_PENDING).commit()) {
            "Owner policy mutation marker clear failed"
        }
    }

    override fun load(): List<OwnerPlannerPermission> {
        val iv = prefs.getString(KEY_IV, null)
        val encrypted = prefs.getString(KEY_CIPHER, null)
        if (iv == null && encrypted == null) return migrateLegacyIfPresent()
        check(iv != null && encrypted != null) { "Truncated owner policy snapshot" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        val plaintext = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        return OwnerPlannerPermissionSnapshotCodec.decode(plaintext)
    }

    override fun save(permissions: List<OwnerPlannerPermission>) {
        val plaintext = OwnerPlannerPermissionSnapshotCodec.encode(permissions).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext)
        check(
            prefs.edit()
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit()
        ) { "Owner policy persistence commit failed" }
    }

    private fun migrateLegacyIfPresent(): List<OwnerPlannerPermission> {
        val legacyEntries = legacyPrefs.all.entries
            .filter { (name, _) -> name.startsWith(LEGACY_KEY_PREFIX) }
        if (legacyEntries.isEmpty()) return emptyList()

        val migrated = legacyEntries.map { (name, value) ->
            val rawDecision = value as? String ?: error("Invalid legacy owner policy value")
            LegacyOwnerPlannerPermissionCodec.decode(name, rawDecision)
        }
        check(migrated.size <= OwnerPlannerPermissionSnapshotCodec.MAX_RECORDS) {
            "Legacy owner policy retention bound exceeded"
        }

        beginMutation()
        try {
            save(migrated)
            check(legacyPrefs.edit().clear().commit()) { "Legacy owner policy clear failed" }
            finishMutation()
        } catch (error: Throwable) {
            throw error
        }
        return migrated
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
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
        const val PREFS = "veltrix.planner.owner_permissions.encrypted.v1"
        const val LEGACY_PREFS = "veltrix.planner.owner_permissions.v1"
        const val LEGACY_KEY_PREFIX = "owner_scope."
        const val KEY_IV = "snapshot_iv"
        const val KEY_CIPHER = "snapshot_cipher"
        const val KEY_MUTATION_PENDING = "mutation_pending"
        const val KEY_ALIAS = "veltrix.ultron.owner.planner.permissions.v1"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Versioned, bounded plaintext schema inside the authenticated encrypted envelope. */
internal object OwnerPlannerPermissionSnapshotCodec {
    const val VERSION = 1
    const val MAX_RECORDS = 512
    private const val MAX_ENCODED_CHARS = 1_000_000
    private const val HEADER = "VELTRIX_OWNER_POLICY"
    private const val MAX_SEGMENT_LENGTH = 512

    fun encode(permissions: List<OwnerPlannerPermission>): String {
        check(permissions.size <= MAX_RECORDS) { "Owner policy retention bound exceeded" }
        val unique = linkedSetOf<String>()
        val lines = permissions
            .onEach(::validate)
            .sortedWith(
                compareBy<OwnerPlannerPermission> { it.principalId }
                    .thenBy { it.deviceId }
                    .thenBy { it.targetScope }
                    .thenBy { it.actionScope }
                    .thenBy { it.riskClass }
            )
            .map { permission ->
                val scopeKey = listOf(
                    permission.principalId,
                    permission.deviceId,
                    permission.targetScope,
                    permission.actionScope,
                    permission.riskClass
                ).joinToString("\u001f")
                check(unique.add(scopeKey)) { "Duplicate owner policy scope" }
                listOf(
                    permission.principalId,
                    permission.deviceId,
                    permission.targetScope,
                    permission.actionScope,
                    permission.riskClass,
                    permission.decision.name
                ).joinToString("|") { encodeSegment(it) }
            }
        return buildString {
            append(HEADER).append('|').append(VERSION)
            lines.forEach { line -> append('\n').append(line) }
        }.also { encoded ->
            check(encoded.length <= MAX_ENCODED_CHARS) { "Owner policy snapshot too large" }
        }
    }

    fun decode(value: String): List<OwnerPlannerPermission> {
        check(value.length <= MAX_ENCODED_CHARS) { "Owner policy snapshot too large" }
        val lines = value.lineSequence().filter(String::isNotBlank).toList()
        check(lines.isNotEmpty()) { "Owner policy snapshot is empty" }
        check(lines.first() == "$HEADER|$VERSION") { "Unsupported owner policy version" }
        check(lines.size - 1 <= MAX_RECORDS) { "Owner policy retention bound exceeded" }

        val seen = linkedSetOf<String>()
        return lines.drop(1).map { line ->
            val fields = line.split('|')
            check(fields.size == 6) { "Invalid owner policy record" }
            val permission = OwnerPlannerPermission(
                principalId = decodeSegment(fields[0]),
                deviceId = decodeSegment(fields[1]),
                targetScope = decodeSegment(fields[2]),
                actionScope = decodeSegment(fields[3]),
                riskClass = decodeSegment(fields[4]),
                decision = OwnerPlannerPermissionDecision.valueOf(decodeSegment(fields[5]))
            )
            validate(permission)
            val scopeKey = fields.take(5).joinToString("|")
            check(seen.add(scopeKey)) { "Duplicate owner policy scope" }
            permission
        }
    }

    fun validate(permission: OwnerPlannerPermission) {
        listOf(
            permission.principalId,
            permission.deviceId,
            permission.targetScope,
            permission.actionScope,
            permission.riskClass
        ).forEach { segment ->
            check(segment.isNotBlank()) { "Owner policy scope must not be blank" }
            check(segment.length <= MAX_SEGMENT_LENGTH) { "Owner policy scope too long" }
            check('\u0000' !in segment) { "Owner policy scope contains NUL" }
        }
    }

    private fun encodeSegment(value: String): String = java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    internal fun decodeSegment(value: String): String =
        java.util.Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
}

/** One-time reader for the pre-V2.33 per-entry SharedPreferences representation. */
internal object LegacyOwnerPlannerPermissionCodec {
    private const val PREFIX = "owner_scope."

    fun decode(key: String, rawDecision: String): OwnerPlannerPermission {
        check(key.startsWith(PREFIX)) { "Invalid legacy owner policy key" }
        val fields = key.removePrefix(PREFIX).split('.')
        check(fields.size == 5) { "Invalid legacy owner policy scope" }
        val permission = OwnerPlannerPermission(
            principalId = OwnerPlannerPermissionSnapshotCodec.decodeSegment(fields[0]),
            deviceId = OwnerPlannerPermissionSnapshotCodec.decodeSegment(fields[1]),
            targetScope = OwnerPlannerPermissionSnapshotCodec.decodeSegment(fields[2]),
            actionScope = OwnerPlannerPermissionSnapshotCodec.decodeSegment(fields[3]),
            riskClass = OwnerPlannerPermissionSnapshotCodec.decodeSegment(fields[4]),
            decision = OwnerPlannerPermissionDecision.valueOf(rawDecision)
        )
        OwnerPlannerPermissionSnapshotCodec.validate(permission)
        return permission
    }
}
