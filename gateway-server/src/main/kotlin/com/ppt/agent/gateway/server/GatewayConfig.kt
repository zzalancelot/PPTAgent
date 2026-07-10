package com.ppt.agent.gateway.server

import com.ppt.agent.config.SpringAiModelClient
import com.ppt.agent.gateway.server.health.ModelHealthProbe
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.openai.OpenAiChatOptions
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
        val bundles: Map<String, ProviderModelBundle> =
            props.models.mapValues { (_, entry) -> ChatModelFactory.createBundle(entry) }
        val chatModels: Map<String, ChatModel> = bundles.mapValues { (_, bundle) -> bundle.chatModel }
        // OpenAiChatModel casts a prompt's runtime options to OpenAiChatOptions,
        // so the client must emit that concrete options subtype. MiMo also needs
        // max_tokens overrides mapped to maxCompletionTokens at request time.
        val clients: Map<String, SpringAiModelClient> =
            bundles.mapValues { (_, bundle) ->
                SpringAiModelClient(
                    bundle.chatModel,
                    optionsBuilder = { OpenAiChatOptions.builder() },
                    tokenLimitConfigurer = bundle.tokenLimitConfigurer,
                    optionsCustomizer = bundle.optionsCustomizer,
                )
            }
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
