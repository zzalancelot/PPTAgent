package com.ppt.agent.gateway.client

import org.springframework.boot.context.properties.ConfigurationProperties

/** Connection settings for reaching the gateway-server gRPC endpoint. */
@ConfigurationProperties(prefix = "gateway.client")
class GatewayClientProperties {
    var host: String = "localhost"
    var port: Int = 9090
}
