package com.veltrix.ultron.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalMemoryStoreTest {
    @Test
    fun newerPreferenceReplacesOlderValueWithoutDuplicatingKey() {
        val store = PersonalMemoryStore()
        store.remember(MemoryRecord(kind = MemoryKind.PREFERENCE, key = "response_style", value = "normal", source = "user"))
        store.remember(MemoryRecord(kind = MemoryKind.PREFERENCE, key = "response_style", value = "minimal", source = "user"))

        val values = store.recall(MemoryKind.PREFERENCE, "response_style")
        assertEquals(1, values.size)
        assertEquals("minimal", values.single().value)
    }

    @Test
    fun privateMemoryCanBeClearedSeparately() {
        val store = PersonalMemoryStore()
        store.remember(MemoryRecord(kind = MemoryKind.FACT, key = "safe", value = "keep", source = "user"))
        store.remember(MemoryRecord(kind = MemoryKind.FACT, key = "secret-note", value = "erase", source = "user", isPrivate = true))

        assertEquals(1, store.clearPrivate())
        assertTrue(store.recall().none { it.isPrivate })
        assertEquals(1, store.recall().size)
    }
}
