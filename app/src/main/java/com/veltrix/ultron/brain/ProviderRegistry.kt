package com.veltrix.ultron.brain

data class ProviderConfig(
    val id: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val secretAlias: String? = null
)

class ProviderRegistry {
    private val providers = linkedMapOf<String, ProviderConfig>()

    @Synchronized
    fun upsert(config: ProviderConfig) {
        providers[config.id] = config
    }

    @Synchronized
    fun get(id: String): ProviderConfig? = providers[id]

    @Synchronized
    fun enabled(): List<ProviderConfig> = providers.values.filter { it.enabled }
}
