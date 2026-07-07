package com.ppt.agent.gateway.client

import com.ppt.agent.gateway.v1.ModelGatewayGrpc
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the gRPC [ManagedChannel], stubs, and both framework client beans.
 * Import this from consumer applications via `@Import`.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayClientProperties::class)
class GatewayClientConfiguration {

    @Bean(destroyMethod = "shutdown")
    fun gatewayManagedChannel(properties: GatewayClientProperties): ManagedChannel =
        ManagedChannelBuilder.forAddress(properties.host, properties.port)
            .usePlaintext()
            .build()

    @Bean
    fun gatewayBlockingStub(channel: ManagedChannel): ModelGatewayGrpc.ModelGatewayBlockingStub =
        ModelGatewayGrpc.newBlockingStub(channel)

    @Bean
    fun gatewayAsyncStub(channel: ManagedChannel): ModelGatewayGrpc.ModelGatewayStub =
        ModelGatewayGrpc.newStub(channel)

    @Bean
    fun gatewayModelClient(blockingStub: ModelGatewayGrpc.ModelGatewayBlockingStub): GatewayModelClient =
        GatewayModelClient(blockingStub)

    @Bean
    fun gatewayStreamingModelClient(asyncStub: ModelGatewayGrpc.ModelGatewayStub): GatewayStreamingModelClient =
        GatewayStreamingModelClient(asyncStub)
}
