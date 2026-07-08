package com.ppt.agent.app.output

import org.springframework.boot.context.properties.ConfigurationProperties

/** Where rendered `.pptx` files are written for HTTP download. */
@ConfigurationProperties(prefix = "ppt.output")
data class PptOutputProperties(
    /** Directory relative to the process working directory (repo root under bootRun). */
    val dir: String = "build/output/pptx",
)
