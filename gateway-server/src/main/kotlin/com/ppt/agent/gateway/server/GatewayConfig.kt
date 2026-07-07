package com.ppt.agent.gateway.server

import com.ppt.agent.config.SpringAiModelClient
import com.ppt.agent.gateway.server.health.ModelHealthProbe
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.ai.chat.model.ChatModel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires providers and gateway beans. The Anthropic [ChatModel] is
 * auto-configured by `spring-ai-starter-model-anthropic`.
 */
@Configuration(proxyBeanMethods = false)
class GatewayConfig {

    @Bean
    fun capabilityRegistry(props: GatewayCapabilitiesProperties): CapabilityRegistry {
        val entries = props.capabilities.mapValues { (alias, config) ->
            CapabilitySpec(alias, config.provider, config.model, config.params)
        }
        return CapabilityRegistry(entries, props.defaultCapability)
    }

    @Bean
    fun springAiModelClient(chatModel: ChatModel): SpringAiModelClient = SpringAiModelClient(chatModel)

    @Bean
    fun providerChatModels(
        chatModel: ChatModel,
        springAiModelClient: SpringAiModelClient,
        meterRegistry: MeterRegistry,
    ): ProviderChatModels = ProviderChatModels(
        clients = mapOf("anthropic" to springAiModelClient),
        chatModels = mapOf("anthropic" to chatModel),
        meterRegistry = meterRegistry,
    )

    @Bean
    fun modelGatewayService(
        registry: CapabilityRegistry,
        providers: ProviderChatModels,
    ): ModelGatewayService = ModelGatewayService(registry, providers)

    @Bean
    fun grpcServerLifecycle(
        service: ModelGatewayService,
        props: GatewayCapabilitiesProperties,
    ): GrpcServerLifecycle = GrpcServerLifecycle(service, props.grpc.port)

    @Bean
    fun modelHealthProbe(
        registry: CapabilityRegistry,
        providers: ProviderChatModels,
        props: GatewayCapabilitiesProperties,
    ): ModelHealthProbe = ModelHealthProbe(registry, providers, props.health)
}
