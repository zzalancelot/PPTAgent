package com.ppt.agent.business

import com.ppt.agent.business.content.SlideContentGenerator
import com.ppt.agent.business.content.SlideContentGeneratorImpl
import com.ppt.agent.business.input.PptInputParser
import com.ppt.agent.business.input.PptInputParserImpl
import com.ppt.agent.business.outline.OutlinePlanner
import com.ppt.agent.business.outline.OutlinePlannerImpl
import com.ppt.agent.business.theme.ThemeColorPicker
import com.ppt.agent.business.theme.ThemeColorPickerImpl
import com.ppt.agent.llm.adapter.LlmAdapter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires the business layer. Import this from consumer applications via `@Import`. */
@Configuration(proxyBeanMethods = false)
class BusinessConfiguration {

    @Bean
    fun pptInputParser(): PptInputParser = PptInputParserImpl()

    @Bean
    fun outlinePlanner(adapter: LlmAdapter): OutlinePlanner = OutlinePlannerImpl(adapter)

    @Bean
    fun slideContentGenerator(adapter: LlmAdapter): SlideContentGenerator = SlideContentGeneratorImpl(adapter)

    @Bean
    fun themeColorPicker(adapter: LlmAdapter): ThemeColorPicker = ThemeColorPickerImpl(adapter)

    @Bean
    fun pptGenerationService(
        adapter: LlmAdapter,
        inputParser: PptInputParser,
        outlinePlanner: OutlinePlanner,
        slideContentGenerator: SlideContentGenerator,
        themeColorPicker: ThemeColorPicker,
    ): PptGenerationService =
        PptGenerationServiceImpl(adapter, inputParser, outlinePlanner, slideContentGenerator, themeColorPicker)
}
