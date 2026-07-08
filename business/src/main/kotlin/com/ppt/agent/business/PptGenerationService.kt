package com.ppt.agent.business

import com.ppt.agent.framework.GatewayModel

/**
 * PPT domain entry point. Placeholder only today — no outline agent, no
 * `.pptx` rendering. See BUSINESS_ADAPTER_SPEC.md non-goals.
 */
interface PptGenerationService {
    /** Placeholder entry point for future JSON-in -> pptx-out pipeline. */
    fun pingLlm(model: GatewayModel): String
}
