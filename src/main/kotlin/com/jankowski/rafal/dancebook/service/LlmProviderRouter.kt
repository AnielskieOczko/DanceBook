package com.jankowski.rafal.dancebook.service

import org.springframework.stereotype.Service

@Service
class LlmProviderRouter(private val providers: List<LlmProvider>) {

    private val registry: Map<String, LlmProvider> =
        providers.associateBy { it.providerName.lowercase() }

    fun getProvider(name: String): LlmProvider {
        return registry[name.lowercase()]
            ?: throw IllegalArgumentException("Unknown LLM provider: $name. Available providers: ${registry.keys}")
    }

    fun getAllModels(): Map<String, List<String>> {
        return registry.mapValues { it.value.getModels() }
    }

    fun callLlm(provider: String, request: LlmRequest): LlmResponse {
        return getProvider(provider).callLlm(request)
    }
}
