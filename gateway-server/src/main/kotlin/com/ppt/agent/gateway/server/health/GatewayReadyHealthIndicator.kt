package com.ppt.agent.gateway.server.health

import com.ppt.agent.gateway.server.CapabilityRegistry
import com.ppt.agent.gateway.server.GrpcServerLifecycle
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

/**
 * Overall serve-ability: the gateway is ready when the gRPC server is serving
 * and at least one capability alias is configured.
 */
@Component("gatewayReady")
class GatewayReadyHealthIndicator(
    private val lifecycle: GrpcServerLifecycle,
    private val registry: CapabilityRegistry,
) : HealthIndicator {
    override fun health(): Health {
        val serving = lifecycle.isServing()
        val capabilities = registry.aliases().size
        return if (serving && capabilities > 0) {
            Health.up()
                .withDetail("grpc", "SERVING")
                .withDetail("capabilities", capabilities)
                .build()
        } else {
            Health.down()
                .withDetail("grpc", if (serving) "SERVING" else "NOT_SERVING")
                .withDetail("capabilities", capabilities)
                .build()
        }
    }
}
