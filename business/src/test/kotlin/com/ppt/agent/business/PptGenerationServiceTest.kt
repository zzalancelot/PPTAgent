package com.ppt.agent.business

import com.ppt.agent.business.input.ParseResult
import com.ppt.agent.business.input.PptInput
import com.ppt.agent.business.input.PptInputParser
import com.ppt.agent.business.content.ContentResult
import com.ppt.agent.business.content.SlideContentGenerator
import com.ppt.agent.business.outline.OutlineJson
import com.ppt.agent.business.outline.OutlinePlanner
import com.ppt.agent.business.outline.OutlineResult
import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.GatewayModel
import com.ppt.agent.framework.ModelResponse
import com.ppt.agent.framework.ModelStreamEvent
import com.ppt.agent.framework.Tool
import com.ppt.agent.llm.adapter.LlmAdapter
import reactor.core.publisher.Flux
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

private class FakeLlmAdapter(
    private val behavior: (List<ChatMessage>, List<Tool>, GatewayModel) -> ModelResponse =
        { _, _, _ -> ModelResponse(text = "pong", toolCalls = emptyList()) },
) : LlmAdapter {
    var callCount = 0
    var lastMessages: List<ChatMessage>? = null
    var lastModel: GatewayModel? = null
    var lastParamOverrides: Map<String, String>? = null

    override fun chat(
        messages: List<ChatMessage>,
        tools: List<Tool>,
        model: GatewayModel,
        paramOverrides: Map<String, String>,
    ): ModelResponse {
        callCount++
        lastMessages = messages
        lastModel = model
        lastParamOverrides = paramOverrides
        return behavior(messages, tools, model)
    }

    override fun chatStream(
        messages: List<ChatMessage>,
        tools: List<Tool>,
        model: GatewayModel,
    ): Flux<ModelStreamEvent> = Flux.empty()
}

private class FakePptInputParser(
    private val result: ParseResult = ParseResult.Ok(
        PptInput(topic = "t", brief = "b", audience = "a"),
    ),
) : PptInputParser {
    var callCount = 0
    var lastJson: String? = null

    override fun parse(json: String): ParseResult {
        callCount++
        lastJson = json
        return result
    }

    override fun parseFromFile(path: java.nio.file.Path): ParseResult = result
}

private class FakeOutlinePlanner(
    private val result: OutlineResult = OutlineResult.Err(emptyList()),
) : OutlinePlanner {
    var callCount = 0
    var lastInput: PptInput? = null
    var lastModel: GatewayModel? = null

    override fun plan(input: PptInput, model: GatewayModel): OutlineResult {
        callCount++
        lastInput = input
        lastModel = model
        return result
    }
}

private class FakeSlideContentGenerator(
    private val result: ContentResult = ContentResult.Err(emptyList()),
) : SlideContentGenerator {
    var callCount = 0
    var lastInput: PptInput? = null
    var lastOutline: OutlineJson? = null

    override fun generate(
        input: PptInput,
        outline: OutlineJson,
        modelPool: List<com.ppt.agent.framework.GatewayModel>,
    ): ContentResult {
        callCount++
        lastInput = input
        lastOutline = outline
        return result
    }
}

/** No Spring context: verifies the service delegates to its collaborators, never the gateway directly. */
class PptGenerationServiceTest {

    private fun service(
        adapter: LlmAdapter = FakeLlmAdapter(),
        parser: PptInputParser = FakePptInputParser(),
        planner: OutlinePlanner = FakeOutlinePlanner(),
        generator: SlideContentGenerator = FakeSlideContentGenerator(),
    ) = PptGenerationServiceImpl(adapter, parser, planner, generator)

    @Test
    fun pingLlmSendsASinglePingMessageAndReturnsTheAdapterResponseText() {
        val fakeAdapter = FakeLlmAdapter()
        val service = service(adapter = fakeAdapter)

        val result = service.pingLlm(GatewayModel.DEEPSEEK)

        assertEquals("pong", result)
        assertEquals(1, fakeAdapter.callCount)
        assertEquals(listOf(ChatMessage.User("ping")), fakeAdapter.lastMessages)
        assertEquals(GatewayModel.DEEPSEEK, fakeAdapter.lastModel)
    }

    @Test
    fun pingLlmReturnsAnErrorMessageInsteadOfThrowingWhenTheAdapterFails() {
        val fakeAdapter = FakeLlmAdapter { _, _, _ -> throw IllegalStateException("gateway down") }
        val service = service(adapter = fakeAdapter)

        val result = service.pingLlm(GatewayModel.DEEPSEEK)

        assertEquals("error: gateway down", result)
    }

    @Test
    fun parseInputDelegatesToTheParserWithTheSameJsonAndReturnsItsResult() {
        val expected = ParseResult.Ok(PptInput(topic = "topic", brief = "brief", audience = "audience"))
        val fakeParser = FakePptInputParser(expected)
        val service = service(parser = fakeParser)
        val json = """{"topic": "topic", "brief": "brief", "audience": "audience"}"""

        val result = service.parseInput(json)

        assertSame(expected, result)
        assertEquals(1, fakeParser.callCount)
        assertEquals(json, fakeParser.lastJson)
    }

    @Test
    fun parseInputReturnsTheParsersErrResultUnchanged() {
        val expected = ParseResult.Err(listOf())
        val fakeParser = FakePptInputParser(expected)
        val service = service(parser = fakeParser)

        val result = service.parseInput("{}")

        assertSame(expected, result)
    }

    @Test
    fun planOutlineDelegatesToTheOutlinePlannerWithTheSameInputAndModel() {
        val input = PptInput(topic = "topic", brief = "brief", audience = "audience")
        val expected = OutlineResult.Err(emptyList())
        val fakePlanner = FakeOutlinePlanner(expected)
        val service = service(planner = fakePlanner)

        val result = service.planOutline(input, GatewayModel.DEEPSEEK)

        assertSame(expected, result)
        assertEquals(1, fakePlanner.callCount)
        assertEquals(input, fakePlanner.lastInput)
        assertEquals(GatewayModel.DEEPSEEK, fakePlanner.lastModel)
    }

    @Test
    fun planOutlineDefaultsToDeepseekModel() {
        val input = PptInput(topic = "topic", brief = "brief", audience = "audience")
        val fakePlanner = FakeOutlinePlanner()
        val service = service(planner = fakePlanner)

        service.planOutline(input)

        assertEquals(GatewayModel.DEEPSEEK, fakePlanner.lastModel)
    }

    @Test
    fun generateContentDelegatesToTheSlideContentGeneratorWithSameInputAndOutline() {
        val input = PptInput(topic = "topic", brief = "brief", audience = "audience")
        val outline = com.ppt.agent.framework.Json.fromJson(
            javaClass.getResource("/outline/valid-outline.json")!!.readText(),
            OutlineJson::class.java,
        )
        val expected = ContentResult.Err(emptyList())
        val fakeGenerator = FakeSlideContentGenerator(expected)
        val service = service(generator = fakeGenerator)

        val result = service.generateContent(input, outline)

        assertSame(expected, result)
        assertEquals(1, fakeGenerator.callCount)
        assertEquals(input, fakeGenerator.lastInput)
        assertSame(outline, fakeGenerator.lastOutline)
    }
}
