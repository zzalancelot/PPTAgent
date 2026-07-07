package com.ppt.agent.gateway.server

/** A fully resolved capability: alias mapped to a concrete provider/model/params. */
data class CapabilitySpec(
    val capability: String,
    val provider: String,
    val model: String,
    val params: Map<String, String>,
)

/**
 * Pure-Kotlin, unit-testable registry mapping capability aliases to concrete
 * provider/model/params. Unknown aliases fail fast.
 */
class CapabilityRegistry(
    private val entries: Map<String, CapabilitySpec>,
    val defaultCapability: String,
) {
    fun resolve(alias: String?): CapabilitySpec {
        val key = alias?.takeIf { it.isNotBlank() } ?: defaultCapability
        return entries[key] ?: throw UnknownCapabilityException(key)
    }

    fun contains(alias: String): Boolean = entries.containsKey(alias)

    fun all(): Map<String, CapabilitySpec> = entries

    fun aliases(): Set<String> = entries.keys

    class UnknownCapabilityException(val alias: String) :
        RuntimeException("Unknown capability alias: '$alias'")
}
