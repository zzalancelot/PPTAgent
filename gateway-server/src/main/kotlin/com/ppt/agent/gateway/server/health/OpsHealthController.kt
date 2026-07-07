package com.ppt.agent.gateway.server.health

import com.ppt.agent.gateway.server.CapabilityRegistry
import com.ppt.agent.gateway.server.GrpcServerLifecycle
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Plain HTTP ops endpoints served on `server.port` (9091). Kept separate from
 * gRPC and from the actuator base path.
 */
@RestController
class OpsHealthController(
    private val lifecycle: GrpcServerLifecycle,
    private val registry: CapabilityRegistry,
    private val modelHealthProbe: ModelHealthProbe,
) {

    /** 200 when serving, 503 otherwise. */
    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, Any>> {
        val serving = lifecycle.isServing()
        val body = mapOf(
            "status" to if (serving) "UP" else "DOWN",
            "grpc" to if (serving) "SERVING" else "NOT_SERVING",
            "capabilities" to registry.aliases().size,
        )
        val status = if (serving) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE
        return ResponseEntity.status(status).body(body)
    }

    /** Per-capability model probe. Always 200 — this is diagnostic, not gating. */
    @GetMapping("/health/models")
    fun models(): Map<String, Any> {
        val results = modelHealthProbe.probeAll()
        return mapOf(
            "models" to results.map { result ->
                mapOf(
                    "capability" to result.capability,
                    "provider" to result.provider,
                    "model" to result.model,
                    "status" to result.status.name,
                    "detail" to result.detail,
                )
            },
        )
    }

    /** The alias table (alias -> provider/model/params). */
    @GetMapping("/health/capabilities")
    fun capabilities(): Map<String, Any> = mapOf(
        "defaultCapability" to registry.defaultCapability,
        "capabilities" to registry.all().map { (alias, spec) ->
            mapOf(
                "alias" to alias,
                "provider" to spec.provider,
                "model" to spec.model,
                "params" to spec.params,
            )
        },
    )
}
