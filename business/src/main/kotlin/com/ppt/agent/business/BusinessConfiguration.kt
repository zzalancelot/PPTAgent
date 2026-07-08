package com.ppt.agent.business

import com.ppt.agent.llm.adapter.LlmAdapter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires the business layer. Import this from consumer applications via `@Import`. */
@Configuration(proxyBeanMethods = false)
class BusinessConfiguration {

    @Bean
    fun pptGenerationService(adapter: LlmAdapter): PptGenerationService =
        PptGenerationServiceImpl(adapter)
}
