package com.ppt.agent.gateway.server.health

import com.ppt.agent.gateway.server.GrpcServerLifecycle
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

/** Reports whether the gRPC server is currently serving. */
@Component("grpcServer")
class GrpcServerHealthIndicator(
    private val lifecycle: GrpcServerLifecycle,
) : HealthIndicator {
    override fun health(): Health =
        if (lifecycle.isServing()) {
            Health.up().withDetail("state", "SERVING").build()
        } else {
            Health.down().withDetail("state", "NOT_SERVING").build()
        }
}
