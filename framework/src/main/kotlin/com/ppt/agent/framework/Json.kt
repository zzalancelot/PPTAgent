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

    /**
     * Parses arbitrary JSON text into a generic object graph: a `Map<*, *>`
     * for objects, a `List<*>` for arrays, and `String` / `Double` / `Boolean`
     * / `null` for scalars. Unlike [toMap], this does **not** swallow
     * malformed input — it throws (e.g. [com.google.gson.JsonSyntaxException])
     * so callers that need to distinguish "invalid JSON" from "valid JSON,
     * wrong shape" can do so.
     */
    fun parse(json: String): Any? = gson.fromJson(json, Any::class.java)

    /**
     * Extracts and parses the **first balanced JSON object** (`{ ... }`) found
     * in [text], tolerating leading/trailing prose or markdown fences that
     * models often emit around JSON. Brace matching ignores braces inside JSON
     * string literals (respecting `\"` escapes).
     *
     * Returns `null` when no complete top-level object can be found or the
     * extracted candidate fails to parse — callers (e.g. the outline retry loop)
     * treat `null` as a truncation / parse-failure signal.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseFirstObject(text: String?): Map<String, Any>? {
        val candidate = firstBalancedObject(text ?: return null) ?: return null
        return try {
            gson.fromJson(candidate, Map::class.java) as? Map<String, Any>
        } catch (e: Exception) {
            null
        }
    }

    /** Returns the substring of the first complete brace-balanced object, or null. */
    private fun firstBalancedObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

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
