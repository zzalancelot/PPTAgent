package com.ppt.agent.gateway.server

import com.ppt.agent.config.SpringAiModelClient
import com.ppt.agent.gateway.server.health.ModelHealthProbe
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.ai.chat.model.ChatModel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the gateway beans. One OpenAI-compatible [ChatModel] is built per
 * `gateway.models` entry (DeepSeek / MiMo / MiniMax); requests are routed to one
 * of them by the model id carried in the request.
 */
@Configuration(proxyBeanMethods = false)
class GatewayConfig {

    @Bean
    fun capabilityRegistry(props: GatewayCapabilitiesProperties): CapabilityRegistry {
        val entries = props.models.mapValues { (id, entry) ->
            CapabilitySpec(capability = id, provider = id, model = entry.model, params = entry.params)
        }
        return CapabilityRegistry(entries, props.defaultModel)
    }

    @Bean
    fun providerChatModels(
        props: GatewayCapabilitiesProperties,
        meterRegistry: MeterRegistry,
    ): ProviderChatModels {
        val chatModels: Map<String, ChatModel> = props.models.mapValues { (_, entry) -> ChatModelFactory.create(entry) }
        val clients: Map<String, SpringAiModelClient> = chatModels.mapValues { (_, model) -> SpringAiModelClient(model) }
        return ProviderChatModels(clients = clients, chatModels = chatModels, meterRegistry = meterRegistry)
    }

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
