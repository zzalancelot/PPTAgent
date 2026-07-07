package com.ppt.agent.gateway.server.health

import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.gateway.server.CapabilityRegistry
import com.ppt.agent.gateway.server.CapabilitySpec
import com.ppt.agent.gateway.server.GatewayCapabilitiesProperties
import com.ppt.agent.gateway.server.ProviderChatModels
import com.ppt.agent.gateway.server.RateLimit
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-capability model reachability probe. Probes are **deduplicated** by
 * provider/model within a single sweep (aliases sharing a model probe once) and
 * cached for [GatewayCapabilitiesProperties.Health.cacheTtl]. A 429 marks the
 * affected entries DOWN without failing overall serve-ability.
 */
class ModelHealthProbe(
    private val registry: CapabilityRegistry,
    private val providers: ProviderChatModels,
    private val health: GatewayCapabilitiesProperties.Health,
) {
    private val log = LoggerFactory.getLogger(ModelHealthProbe::class.java)
    private val cache = ConcurrentHashMap<String, CachedResult>()

    enum class ModelStatus { UP, DOWN, DISABLED }

    data class ProbeResult(
        val capability: String,
        val provider: String,
        val model: String,
        val status: ModelStatus,
        val detail: String? = null,
    )

    private data class CachedResult(val status: ModelStatus, val detail: String?, val at: Instant)

    fun probeAll(): List<ProbeResult> {
        val probedThisSweep = HashMap<String, CachedResult>()
        return registry.all().map { (alias, spec) ->
            val key = "${spec.provider}/${spec.model}"
            val result = probedThisSweep.getOrPut(key) { probeModel(key, spec) }
            ProbeResult(alias, spec.provider, spec.model, result.status, result.detail)
        }
    }

    private fun probeModel(key: String, spec: CapabilitySpec): CachedResult {
        if (!health.probeMode.equals("real", ignoreCase = true)) {
            return CachedResult(ModelStatus.DISABLED, "probe-mode=${health.probeMode}", Instant.now())
        }
        cache[key]?.let { cached ->
            if (Instant.now().isBefore(cached.at.plus(health.cacheTtl))) return cached
        }
        val fresh = runProbe(spec)
        cache[key] = fresh
        return fresh
    }

    private fun runProbe(spec: CapabilitySpec): CachedResult {
        val probeSpec = spec.copy(params = spec.params + ("max_tokens" to "1"))
        return try {
            providers.chat(probeSpec, listOf(ChatMessage.User(health.probePrompt)), emptyList())
            CachedResult(ModelStatus.UP, null, Instant.now())
        } catch (e: Exception) {
            val detail = if (RateLimit.is429(e)) "rate limited (429)" else (e.message ?: "probe failed")
            log.debug("Model probe DOWN for {}/{}: {}", spec.provider, spec.model, detail)
            CachedResult(ModelStatus.DOWN, detail, Instant.now())
        }
    }
}
