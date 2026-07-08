package com.ppt.agent.renderer.cli

import com.ppt.agent.renderer.PptRenderToolImpl
import com.ppt.agent.renderer.RenderToolError
import com.ppt.agent.renderer.RenderToolResult
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = "Usage: render-pptx --input <deck.json> --output <deck.pptx>"

/**
 * CLI entry point for the `render_pptx` tool. Exit code 0 on success, 1 on any
 * error; errors are printed to stderr as plain text.
 */
fun main(args: Array<String>) {
    exitProcess(runCli(args))
}

/** Testable core: returns the process exit code. */
internal fun runCli(args: Array<String>): Int {
    val opts = parseArgs(args)
    val input = opts["input"]
    val output = opts["output"]
    if (input.isNullOrBlank() || output.isNullOrBlank()) {
        System.err.println(USAGE)
        return 1
    }

    return when (val result = PptRenderToolImpl().render(Path.of(input), Path.of(output))) {
        is RenderToolResult.Ok -> {
            println("Rendered ${result.slideCount} slides -> ${result.outputPath}")
            0
        }
        is RenderToolResult.Err -> {
            System.err.println("render_pptx failed:")
            result.errors.forEach { System.err.println("  - ${describe(it)}") }
            1
        }
    }
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val opts = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--input" || arg == "-i" -> args.getOrNull(++i)?.let { opts["input"] = it }
            arg == "--output" || arg == "-o" -> args.getOrNull(++i)?.let { opts["output"] = it }
            arg.startsWith("--input=") -> opts["input"] = arg.substringAfter('=')
            arg.startsWith("--output=") -> opts["output"] = arg.substringAfter('=')
        }
        i++
    }
    return opts
}

private fun describe(error: RenderToolError): String = when (error) {
    is RenderToolError.InvalidJson -> "invalid JSON: ${error.message}"
    is RenderToolError.ValidationFailed -> "validation failed: ${error.violations.joinToString("; ")}"
    is RenderToolError.UnsupportedSlideType -> "unsupported slideType '${error.slideType}' at slide ${error.index}"
    is RenderToolError.IoFailure -> "I/O failure: ${error.message}"
}
