package com.ppt.agent.gateway.client

import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.ModelClient
import com.ppt.agent.framework.ModelResponse
import com.ppt.agent.framework.Tool
import com.ppt.agent.gateway.v1.ChatRequest
import com.ppt.agent.gateway.v1.ModelGatewayGrpc

/**
 * [ModelClient] over gRPC. The `model` argument is a capability alias; concrete
 * model resolution happens server-side.
 */
class GatewayModelClient(
    private val blockingStub: ModelGatewayGrpc.ModelGatewayBlockingStub,
) : ModelClient {

    override fun chat(messages: List<ChatMessage>, tools: List<Tool>, model: String): ModelResponse {
        val request = ChatRequest.newBuilder()
            .setCapability(model)
            .addAllMessages(messages.map(ClientMappers::toProtoMessage))
            .addAllTools(tools.map(ClientMappers::toProtoTool))
            .build()
        return ClientMappers.toModelResponse(blockingStub.chat(request))
    }
}
