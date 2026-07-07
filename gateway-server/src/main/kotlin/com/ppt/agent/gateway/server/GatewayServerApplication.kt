package com.ppt.agent.gateway.server

import com.ppt.agent.config.AiKeysProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(GatewayCapabilitiesProperties::class, AiKeysProperties::class)
class GatewayServerApplication

fun main(args: Array<String>) {
    runApplication<GatewayServerApplication>(*args)
}
