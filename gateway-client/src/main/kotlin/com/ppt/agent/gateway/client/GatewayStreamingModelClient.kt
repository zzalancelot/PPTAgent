package com.ppt.agent.gateway.client

import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.ModelStreamEvent
import com.ppt.agent.framework.StreamingModelClient
import com.ppt.agent.framework.Tool
import com.ppt.agent.gateway.v1.ChatEvent
import com.ppt.agent.gateway.v1.ChatRequest
import com.ppt.agent.gateway.v1.ModelGatewayGrpc
import io.grpc.stub.StreamObserver
import reactor.core.publisher.Flux

/** [StreamingModelClient] over gRPC server-streaming. */
class GatewayStreamingModelClient(
    private val asyncStub: ModelGatewayGrpc.ModelGatewayStub,
) : StreamingModelClient {

    override fun chatStream(
        messages: List<ChatMessage>,
        tools: List<Tool>,
        capability: String,
    ): Flux<ModelStreamEvent> {
        val request = ChatRequest.newBuilder()
            .setCapability(capability)
            .addAllMessages(messages.map(ClientMappers::toProtoMessage))
            .addAllTools(tools.map(ClientMappers::toProtoTool))
            .build()

        return Flux.create { sink ->
            asyncStub.chatStream(
                request,
                object : StreamObserver<ChatEvent> {
                    override fun onNext(value: ChatEvent) {
                        sink.next(ClientMappers.toStreamEvent(value))
                    }

                    override fun onError(t: Throwable) {
                        sink.error(t)
                    }

                    override fun onCompleted() {
                        sink.complete()
                    }
                },
            )
        }
    }
}
