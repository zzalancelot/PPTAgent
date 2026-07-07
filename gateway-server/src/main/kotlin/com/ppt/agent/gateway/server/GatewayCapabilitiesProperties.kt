package com.ppt.agent.gateway.server

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Binds the `gateway.*` configuration tree. Concrete provider/model ids live
 * here (in gateway-server YAML) and nowhere else.
 */
@ConfigurationProperties(prefix = "gateway")
class GatewayCapabilitiesProperties {
    var grpc: Grpc = Grpc()
    var defaultCapability: String = "fast-chat"
    var health: Health = Health()
    var capabilities: Map<String, CapabilityConfig> = emptyMap()

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

    class CapabilityConfig {
        var provider: String = ""
        var model: String = ""
        var params: Map<String, String> = emptyMap()
    }
}
