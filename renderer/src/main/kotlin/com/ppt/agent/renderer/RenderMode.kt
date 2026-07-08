package com.ppt.agent.renderer

/**
 * How deck JSON is turned into a .pptx file.
 *
 * PROGRAMMATIC — draw slides with POI text boxes (existing PptxWriter).
 * TEMPLATE     — open a bundled/custom .pptx template and fill placeholders.
 */
enum class RenderMode {
    /** Mode A: code-drawn slides (current default). */
    PROGRAMMATIC,

    /** Mode B: fill an on-disk / classpath master template. */
    TEMPLATE,
}
