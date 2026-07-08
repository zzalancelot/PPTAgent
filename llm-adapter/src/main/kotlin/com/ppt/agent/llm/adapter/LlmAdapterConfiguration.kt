package com.ppt.agent.llm.adapter

import com.ppt.agent.framework.ModelClient
import com.ppt.agent.framework.StreamingModelClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the default (pass-through) [LlmAdapter] bean. Import this from
 * consumer applications via `@Import`, alongside `GatewayClientConfiguration`
 * (which supplies the [ModelClient] / [StreamingModelClient] beans this
 * depends on).
 */
@Configuration(proxyBeanMethods = false)
class LlmAdapterConfiguration {

    @Bean
    fun llmAdapter(
        modelClient: ModelClient,
        streamingModelClient: StreamingModelClient,
    ): LlmAdapter = PassthroughLlmAdapter(modelClient, streamingModelClient)
}
