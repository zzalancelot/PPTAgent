package com.ppt.agent.gateway.server

import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.Json
import com.ppt.agent.framework.ModelStreamEvent
import com.ppt.agent.framework.Tool
import com.ppt.agent.framework.ToolResult
import com.ppt.agent.framework.ToolResultItem
import com.ppt.agent.gateway.v1.CapabilityInfo
import com.ppt.agent.gateway.v1.ChatEvent
import com.ppt.agent.gateway.v1.ChatRequest
import com.ppt.agent.gateway.v1.ChatResponse
import com.ppt.agent.gateway.v1.Done
import com.ppt.agent.gateway.v1.ErrorEvent
import com.ppt.agent.gateway.v1.ListCapabilitiesRequest
import com.ppt.agent.gateway.v1.ListCapabilitiesResponse
import com.ppt.agent.gateway.v1.ModelGatewayGrpc
import com.ppt.agent.gateway.v1.ResolvedCapability
import com.ppt.agent.gateway.v1.Role
import com.ppt.agent.gateway.v1.TextDelta
import com.ppt.agent.gateway.v1.ToolCallEvent
import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory
import com.ppt.agent.framework.ToolCall as FwToolCall
import com.ppt.agent.gateway.v1.Message as ProtoMessage
import com.ppt.agent.gateway.v1.ToolCall as ProtoToolCall
import com.ppt.agent.gateway.v1.ToolSpec as ProtoToolSpec

/**
 * gRPC implementation of the ModelGateway service. Each RPC performs exactly one
 * model turn. Errors are mapped to gRPC [Status] for unary calls and to an
 * in-band [ErrorEvent] for streaming calls.
 */
class ModelGatewayService(
    private val registry: CapabilityRegistry,
    private val providers: ProviderChatModels,
) : ModelGatewayGrpc.ModelGatewayImplBase() {

    private val log = LoggerFactory.getLogger(ModelGatewayService::class.java)

    override fun chat(request: ChatRequest, responseObserver: StreamObserver<ChatResponse>) {
        try {
            val spec = resolve(request)
            val response = providers.chat(spec, toMessages(request.messagesList), toTools(request.toolsList))
            val proto = ChatResponse.newBuilder()
                .setText(response.text ?: "")
                .addAllToolCalls(response.toolCalls.map(::toProto))
                .setResolved(
                    ResolvedCapability.newBuilder()
                        .setCapability(spec.capability)
                        .setProvider(spec.provider)
                        .setModel(spec.model)
                        .build(),
                )
                .build()
            responseObserver.onNext(proto)
            responseObserver.onCompleted()
        } catch (e: CapabilityRegistry.UnknownCapabilityException) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.message).asRuntimeException())
        } catch (e: Exception) {
            log.warn("chat failed: {}", e.message)
            val status = if (RateLimit.is429(e)) Status.RESOURCE_EXHAUSTED else Status.INTERNAL
            responseObserver.onError(status.withDescription(e.message).asRuntimeException())
        }
    }

    override fun chatStream(request: ChatRequest, responseObserver: StreamObserver<ChatEvent>) {
        val spec = try {
            resolve(request)
        } catch (e: CapabilityRegistry.UnknownCapabilityException) {
            responseObserver.onNext(errorEvent(e.message ?: "unknown capability", "UNKNOWN_CAPABILITY"))
            responseObserver.onCompleted()
            return
        }
        providers.stream(spec, toMessages(request.messagesList), toTools(request.toolsList))
            .subscribe(
                { event -> responseObserver.onNext(toProto(event)) },
                { error ->
                    log.warn("chatStream failed: {}", error.message)
                    responseObserver.onNext(errorEvent(error.message ?: "stream failed", RateLimit.codeOf(error)))
                    responseObserver.onCompleted()
                },
                { responseObserver.onCompleted() },
            )
    }

    override fun listCapabilities(
        request: ListCapabilitiesRequest,
        responseObserver: StreamObserver<ListCapabilitiesResponse>,
    ) {
        val response = ListCapabilitiesResponse.newBuilder()
            .addAllCapabilities(
                registry.all().map { (alias, spec) ->
                    CapabilityInfo.newBuilder()
                        .setAlias(alias)
                        .setProvider(spec.provider)
                        .setModel(spec.model)
                        .putAllParams(spec.params)
                        .build()
                },
            )
            .setDefaultCapability(registry.defaultCapability)
            .build()
        responseObserver.onNext(response)
        responseObserver.onCompleted()
    }

    private fun resolve(request: ChatRequest): CapabilitySpec {
        val base = registry.resolve(request.capability)
        if (request.paramOverridesMap.isEmpty()) return base
        return base.copy(params = base.params + request.paramOverridesMap)
    }

    private fun toMessages(messages: List<ProtoMessage>): List<ChatMessage> = messages.map { m ->
        when (m.role) {
            Role.SYSTEM -> ChatMessage.System(m.text)
            Role.USER -> ChatMessage.User(m.text)
            Role.ASSISTANT -> ChatMessage.Assistant(
                m.text.takeIf { it.isNotEmpty() },
                m.toolCallsList.map { FwToolCall(it.id, it.name, it.argsJson) },
            )
            Role.TOOL_RESULTS -> ChatMessage.ToolResults(
                m.toolResultsList.map { ToolResultItem(it.id, it.name, it.content) },
            )
            else -> ChatMessage.User(m.text)
        }
    }

    private fun toTools(tools: List<ProtoToolSpec>): List<Tool> = tools.map { spec ->
        object : Tool {
            override fun name() = spec.name
            override fun description() = spec.description
            override fun parametersSchema() = Json.toMap(spec.parametersSchemaJson)
            override fun execute(argsJson: String): ToolResult =
                throw UnsupportedOperationException("Gateway does not execute tools.")
        }
    }

    private fun toProto(call: FwToolCall): ProtoToolCall = ProtoToolCall.newBuilder()
        .setId(call.id)
        .setName(call.name)
        .setArgsJson(call.argsJson)
        .build()

    private fun toProto(event: ModelStreamEvent): ChatEvent = when (event) {
        is ModelStreamEvent.TextDelta -> ChatEvent.newBuilder()
            .setTextDelta(TextDelta.newBuilder().setContent(event.content).build())
            .build()
        is ModelStreamEvent.ToolCallRequest -> ChatEvent.newBuilder()
            .setToolCall(ToolCallEvent.newBuilder().setCall(toProto(event.call)).build())
            .build()
        is ModelStreamEvent.Done -> ChatEvent.newBuilder()
            .setDone(
                Done.newBuilder()
                    .setFullText(event.fullText ?: "")
                    .addAllToolCalls(event.toolCalls.map(::toProto))
                    .build(),
            )
            .build()
        is ModelStreamEvent.Failed -> errorEvent(event.message, event.code ?: "PROVIDER_ERROR")
    }

    private fun errorEvent(message: String, code: String): ChatEvent = ChatEvent.newBuilder()
        .setError(ErrorEvent.newBuilder().setMessage(message).setCode(code).build())
        .build()
}
