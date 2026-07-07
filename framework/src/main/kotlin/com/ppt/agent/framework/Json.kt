package com.ppt.agent.framework

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * Tiny JSON helper used for tool schema / argument serialization across the
 * gRPC boundary. Backed by Gson with defensive fallbacks so serialization
 * never throws in the hot path.
 */
object Json {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun toJson(value: Any?): String = try {
        gson.toJson(value)
    } catch (e: Exception) {
        value?.toString() ?: "null"
    }

    fun <T> fromJson(json: String, type: Class<T>): T = gson.fromJson(json, type)

    @Suppress("UNCHECKED_CAST")
    fun toMap(json: String?): Map<String, Any> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            (gson.fromJson(json, Map::class.java) as? Map<String, Any>) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
