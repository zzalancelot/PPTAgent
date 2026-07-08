package com.ppt.agent.app.output

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(PptOutputProperties::class)
class PptOutputConfiguration
