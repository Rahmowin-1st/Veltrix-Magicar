package com.veltrix.ultron.brain

class BrainRouter {
    private val models = mutableListOf<BrainModel>()
    private val health = mutableMapOf<String, ProviderHealth>()

    @Synchronized
    fun register(model: BrainModel) {
        models.removeAll { it.providerId == model.providerId && it.modelId == model.modelId }
        models += model
    }

    @Synchronized
    fun updateHealth(providerHealth: ProviderHealth) {
        health[providerHealth.providerId] = providerHealth
    }

    @Synchronized
    fun route(request: BrainRequest): List<BrainModel> = models
        .asSequence()
        .filter { it.enabled }
        .filter { !request.freeOnly || it.freeTier }
        .filter { model -> request.required.all(model.capabilities::contains) }
        .filter { model -> health[model.providerId]?.let { it.healthy && !it.rateLimited } ?: true }
        .sortedWith(
            compareBy<BrainModel> { it.priority }
                .thenByDescending { request.preferFast && BrainCapability.FAST_TEXT in it.capabilities }
        )
        .toList()

    @Synchronized
    fun nextFallback(request: BrainRequest, excluded: Set<Pair<String, String>>): BrainModel? =
        route(request).firstOrNull { (it.providerId to it.modelId) !in excluded }
}
