package com.ppt.agent.gateway.server

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Binds the `gateway.*` configuration tree. The concrete provider endpoints and
 * model ids live here (in gateway-server YAML) and nowhere else. Callers select
 * a target via the [com.ppt.agent.framework.GatewayModel] enum, whose id matches
 * a key under [models].
 */
@ConfigurationProperties(prefix = "gateway")
class GatewayCapabilitiesProperties {
    var grpc: Grpc = Grpc()

    /** The model id used when a request omits one. Must be a key of [models]. */
    var defaultModel: String = "deepseek"

    var health: Health = Health()

    /** Model id (e.g. `deepseek` / `mimo` / `minimax`) -> endpoint definition. */
    var models: Map<String, ModelEntry> = emptyMap()

    class Grpc {
        var port: Int = 9090
    }

    class Health {
        /** `real` performs a live probe; anything else short-circuits to UP. */
        var probeMode: String = "real"
        var timeout: Duration = Duration.ofSeconds(8)
        var cacheTtl: Duration = Duration.ofSeconds(15)
        var probePrompt: String = "ping"
    }

    /** An OpenAI-compatible model endpoint. */
    class ModelEntry {
        var baseUrl: String = ""
        var apiKey: String = ""
        var model: String = ""
        var params: Map<String, String> = emptyMap()
    }
}
