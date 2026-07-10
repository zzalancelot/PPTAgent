package com.ppt.agent.business.content

import com.ppt.agent.business.outline.ConsistencyRules
import com.ppt.agent.business.outline.LayoutProfiles
import com.ppt.agent.business.outline.OutlineJson
import com.ppt.agent.business.outline.OutlineMeta
import com.ppt.agent.business.outline.OutlineSection
import com.ppt.agent.business.outline.Storyline
import com.ppt.agent.framework.GatewayModel
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelAssignmentPolicyTest {

    private fun outlineWithSections(vararg ids: String): OutlineJson = OutlineJson(
        meta = OutlineMeta("t", "a", ids.size, "en", "teaching", "clear", "one"),
        storyline = Storyline("h", "p", emptyList(), emptyList(), emptyList(), "m"),
        sections = ids.map { OutlineSection(it, it, "purpose", listOf(1, 1), LayoutProfiles.EDITORIAL_LEFT) },
        slides = emptyList(),
        consistency = ConsistencyRules(emptyList(), emptyList(), emptyList(), emptyList(), ""),
    )

    @Test
    fun sevenSectionsAcrossThreeModelsRoundRobin() {
        val outline = outlineWithSections(
            "opening", "setup", "basics", "control", "functions", "project", "closing",
        )

        val assignments = ModelAssignmentPolicy.assignBySection(outline, ModelPool.MULTI)

        assertEquals(
            mapOf(
                "opening" to GatewayModel.DEEPSEEK,
                "setup" to GatewayModel.MIMO,
                "basics" to GatewayModel.MINIMAX,
                "control" to GatewayModel.DEEPSEEK,
                "functions" to GatewayModel.MIMO,
                "project" to GatewayModel.MINIMAX,
                "closing" to GatewayModel.DEEPSEEK,
            ),
            assignments,
        )
    }

    @Test
    fun singleModelPoolAssignsThatModelToEverySection() {
        val outline = outlineWithSections("a", "b", "c")

        val assignments = ModelAssignmentPolicy.assignBySection(outline, listOf(GatewayModel.MIMO))

        assertEquals(
            mapOf("a" to GatewayModel.MIMO, "b" to GatewayModel.MIMO, "c" to GatewayModel.MIMO),
            assignments,
        )
    }

    @Test
    fun assignmentIsStablePerSectionAndCoversEverySection() {
        val outline = outlineWithSections("s1", "s2", "s3", "s4")

        val assignments = ModelAssignmentPolicy.assignBySection(outline, ModelPool.DEFAULT)

        assertEquals(4, assignments.size)
        assertEquals(GatewayModel.DEEPSEEK, assignments["s1"])
        assertEquals(GatewayModel.DEEPSEEK, assignments["s2"])
        assertEquals(GatewayModel.DEEPSEEK, assignments["s3"])
        assertEquals(GatewayModel.DEEPSEEK, assignments["s4"])
    }

    @Test
    fun emptyPoolIsRejected() {
        val outline = outlineWithSections("a")
        try {
            ModelAssignmentPolicy.assignBySection(outline, emptyList())
            throw AssertionError("expected IllegalArgumentException for empty pool")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
