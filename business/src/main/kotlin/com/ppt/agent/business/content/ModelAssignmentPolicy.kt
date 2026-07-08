package com.ppt.agent.business.content

import com.ppt.agent.business.outline.OutlineJson
import com.ppt.agent.framework.GatewayModel

/**
 * Assigns exactly one [GatewayModel] per outline section, stable for every slide
 * in that section. Assignment is round-robin across [pool] in section iteration
 * order — deterministic, NOT random per slide.
 *
 * Example (7 sections, pool `[DEEPSEEK, MIMO, MINIMAX]`):
 * `opening→DEEPSEEK, setup→MIMO, basics→MINIMAX, control→DEEPSEEK, functions→MIMO,
 * project→MINIMAX, closing→DEEPSEEK`.
 */
object ModelAssignmentPolicy {

    /**
     * @param pool models to round-robin over. Must be non-empty.
     * @return `sectionId → GatewayModel`, one stable entry per section.
     */
    fun assignBySection(outline: OutlineJson, pool: List<GatewayModel>): Map<String, GatewayModel> {
        require(pool.isNotEmpty()) { "model pool must not be empty" }
        val assignments = LinkedHashMap<String, GatewayModel>()
        var i = 0
        for (section in outline.sections) {
            // A section id could repeat defensively; keep the first assignment stable.
            if (assignments.containsKey(section.id)) continue
            assignments[section.id] = pool[i % pool.size]
            i++
        }
        return assignments
    }
}
