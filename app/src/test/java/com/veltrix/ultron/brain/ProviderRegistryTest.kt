package com.veltrix.ultron.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderRegistryTest {
    @Test
    fun registryUpsertsAndFiltersEnabledProviders() {
        val registry = ProviderRegistry()
        registry.upsert(
            ProviderConfig(
                id = "openrouter",
                baseUrl = "https://openrouter.ai/api/v1",
                enabled = true,
                secretAlias = "openrouter_key"
            )
        )
        registry.upsert(
            ProviderConfig(
                id = "disabled-provider",
                baseUrl = "https://example.invalid",
                enabled = false
            )
        )

        assertEquals("https://openrouter.ai/api/v1", registry.get("openrouter")?.baseUrl)
        assertEquals(listOf("openrouter"), registry.enabled().map { it.id })
        assertNull(registry.get("missing"))
    }
}
