package com.ppt.agent.app.api

import com.ppt.agent.framework.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PptRestyleRequestParseTest {

    @Test
    fun `restyle request deserializes without outline`() {
        val body =
            """
            {
              "topic": "Python 入门",
              "brief": "brief",
              "audience": "新人",
              "slideCount": 27,
              "scenarioId": "client_pitch",
              "scenarios": [
                {
                  "id": "client_pitch",
                  "label": "客户推介",
                  "description": "desc",
                  "audienceFrame": "客户",
                  "colorMood": "warm_earth",
                  "voiceTone": "专业",
                  "narrativeArc": "persuasion",
                  "confidence": 0.9
                }
              ]
            }
            """.trimIndent()

        val request = Json.fromJson(body, PptRestyleRequest::class.java)

        assertEquals(27, request.slideCount)
        assertEquals("client_pitch", request.scenarioId)
        assertEquals("Python 入门", request.topic)
        assertEquals(1, request.scenarios?.size)
    }
}
