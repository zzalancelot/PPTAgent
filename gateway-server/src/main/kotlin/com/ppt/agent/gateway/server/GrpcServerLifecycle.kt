package com.ppt.agent.gateway.server

import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.BindableService
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.util.concurrent.TimeUnit

/**
 * Starts/stops a plain [io.grpc.Server] alongside the Spring context via
 * [SmartLifecycle] — no gRPC starter involved.
 */
class GrpcServerLifecycle(
    private val service: BindableService,
    private val port: Int,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(GrpcServerLifecycle::class.java)

    @Volatile
    private var server: Server? = null

    @Synchronized
    override fun start() {
        if (server != null) return
        log.info("Starting gRPC server on port {}", port)
        server = ServerBuilder.forPort(port)
            .addService(service)
            .build()
            .start()
    }

    @Synchronized
    override fun stop() {
        val current = server ?: return
        log.info("Stopping gRPC server")
        current.shutdown()
        try {
            if (!current.awaitTermination(10, TimeUnit.SECONDS)) {
                current.shutdownNow()
            }
        } catch (e: InterruptedException) {
            current.shutdownNow()
            Thread.currentThread().interrupt()
        }
        server = null
    }

    override fun isRunning(): Boolean {
        val current = server ?: return false
        return !current.isShutdown && !current.isTerminated
    }

    /** True when the gRPC server is up and serving requests. */
    fun isServing(): Boolean = isRunning()

    /** Start late and stop early relative to the web server. */
    override fun getPhase(): Int = Int.MAX_VALUE - 1024
}
