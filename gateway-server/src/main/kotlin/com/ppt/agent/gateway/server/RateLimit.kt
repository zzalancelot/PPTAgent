package com.ppt.agent.gateway.server

/** Best-effort classification of provider rate-limit (HTTP 429) failures. */
object RateLimit {
    fun is429(error: Throwable?): Boolean {
        var cause: Throwable? = error
        val seen = HashSet<Throwable>()
        while (cause != null && seen.add(cause)) {
            val name = cause.javaClass.name.lowercase()
            val msg = cause.message?.lowercase().orEmpty()
            if (name.contains("ratelimit") || name.contains("toomanyrequests") ||
                msg.contains("429") || msg.contains("too many requests") || msg.contains("rate limit")
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    fun codeOf(error: Throwable?): String = if (is429(error)) "RATE_LIMITED" else "PROVIDER_ERROR"
}
