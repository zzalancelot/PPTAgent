package com.ppt.agent.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Binds provider API keys imported from `ai-keys.yaml` (or env). Referenced by
 * the gateway-server YAML via `${ai.keys.anthropic:...}` placeholders.
 */
@ConfigurationProperties(prefix = "ai.keys")
class AiKeysProperties {
    var anthropic: String? = null
}
