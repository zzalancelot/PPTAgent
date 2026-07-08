package com.ppt.agent.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Binds provider API keys imported from `ai-keys.yaml` (or env). Referenced by
 * the gateway-server YAML via `${ai.keys.<provider>:...}` placeholders.
 */
@ConfigurationProperties(prefix = "ai.keys")
class AiKeysProperties {
    var deepseek: String? = null
    var mimo: String? = null
    var minimax: String? = null
}
