package com.veltrix.ultron.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrainRouterTest {
    @Test
    fun freeOnlyRoutingSkipsPaidAndRateLimitedProviders() {
        val router = BrainRouter()
        router.register(BrainModel("a", "fast", setOf(BrainCapability.FAST_TEXT), freeTier = true, priority = 10))
        router.register(BrainModel("b", "paid", setOf(BrainCapability.FAST_TEXT), freeTier = false, priority = 1))
        router.updateHealth(ProviderHealth("a", healthy = true, rateLimited = true))

        assertNull(router.nextFallback(BrainRequest(setOf(BrainCapability.FAST_TEXT)), emptySet()))
    }

    @Test
    fun fallbackMovesToNextEligibleModel() {
        val router = BrainRouter()
        router.register(BrainModel("a", "one", setOf(BrainCapability.REASONING), freeTier = true, priority = 1))
        router.register(BrainModel("b", "two", setOf(BrainCapability.REASONING), freeTier = true, priority = 2))

        val next = router.nextFallback(
            BrainRequest(setOf(BrainCapability.REASONING)),
            setOf("a" to "one")
        )
        assertEquals("two", next?.modelId)
    }
}
