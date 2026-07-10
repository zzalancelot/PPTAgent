package com.ppt.agent.app.api

import com.ppt.agent.app.output.PptxExportResult
import com.ppt.agent.app.output.PptxExportService
import com.ppt.agent.business.PptGenerationService
import com.ppt.agent.business.content.ContentError
import com.ppt.agent.business.content.ContentResult
import com.ppt.agent.business.input.ParseResult
import com.ppt.agent.business.input.PptInput
import com.ppt.agent.business.input.PptInputError
import com.ppt.agent.business.outline.OutlineError
import com.ppt.agent.business.outline.OutlineJson
import com.ppt.agent.business.outline.OutlineResult
import com.ppt.agent.business.content.SlideDeckContent
import com.ppt.agent.business.scenario.DeckStance
import com.ppt.agent.business.scenario.DeckStanceResolver
import com.ppt.agent.business.scenario.PresentationScenario
import com.ppt.agent.business.scenario.ScenarioBrief
import com.ppt.agent.business.scenario.ScenarioError
import com.ppt.agent.business.scenario.ScenarioResult
import com.ppt.agent.business.theme.ThemeColorError
import com.ppt.agent.business.theme.ThemeColorResult
import com.ppt.agent.framework.GatewayModel
import com.ppt.agent.framework.Json
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.Executors

/**
 * Manual verification HTTP API for the PPT pipeline (parse → outline → content).
 * Requires [gateway-server] running on the configured gRPC port (default :9090).
 */
@RestController
@RequestMapping("/v1/ppt")
class PptApiController(
    private val pptGenerationService: PptGenerationService,
    private val pptxExportService: PptxExportService,
) {

  /** Quick liveness + documents available pipeline stages. */
  @GetMapping("/health")
  fun health(): Map<String, Any> =
      mapOf(
          "status" to "ok",
          "stages" to STAGES.toList(),
          "defaultStage" to "pptx",
          "pptxOutputDir" to "build/output/pptx",
      )

  /** One-shot gateway connectivity check through business → llm-adapter → gateway-client. */
  @GetMapping("/ping")
  fun ping(@RequestParam(defaultValue = "deepseek") model: String): Map<String, Any?> {
    val resolved = resolveModel(model)
    val text = pptGenerationService.pingLlm(resolved)
    return mapOf("model" to resolved.id, "text" to text)
  }

  /**
   * Runs part or all of the pipeline on the same JSON body used by fixtures
   * (`topic` / `brief` / `audience` / optional `slide_count`).
   *
   * - `parse` — validation only (milliseconds)
   * - `outline` — parse + one LLM call for the full deck plan (~1–2 min)
   * - `content` — parse + outline + per-slide LLM calls (~several minutes for 27 slides)
   * - `pptx` — same as `content`, then render a downloadable `.pptx` under `build/output/pptx/`
   */
  @PostMapping("/run", consumes = [MediaType.APPLICATION_JSON_VALUE])
  fun run(
      @RequestBody json: String,
      @RequestParam(defaultValue = "outline") stage: String,
      @RequestParam(required = false) model: String?,
      @RequestParam(required = false) outlineModel: String?,
      @RequestParam(required = false) contentModel: String?,
  ): ResponseEntity<PptRunResponse> {
    val normalizedStage =
        stage.lowercase().also {
          require(it in STAGES) { "Unknown stage '$stage'. Use one of: ${STAGES.joinToString()}" }
        }
    val resolvedOutlineModel = resolveModel(outlineModel ?: model ?: GatewayModel.DEEPSEEK.id)
    val resolvedContentModel = resolveModel(contentModel ?: model ?: GatewayModel.DEEPSEEK.id)
    val timing = linkedMapOf<String, Long>()

    val parseStart = System.nanoTime()
    val parseResult = pptGenerationService.parseInput(json)
    timing["parse"] = elapsedMs(parseStart)

    if (parseResult is ParseResult.Err) {
      return ResponseEntity.badRequest().body(
          PptRunResponse(
              stage = normalizedStage,
              status = "error",
              errors = parseResult.errors.map { it.toMap() },
              timingMs = timing,
          ),
      )
    }

    val input = (parseResult as ParseResult.Ok).input
    if (normalizedStage == "parse") {
      return ResponseEntity.ok(okResponse("parse", input = input, timing = timing))
    }

    val outlineStart = System.nanoTime()
    val outlineResult: OutlineResult
    val scenarioResult: ScenarioResult?

    if (normalizedStage == "pptx") {
      // Run outline and scenarios in parallel for pptx stage
      val parallelExecutor = Executors.newFixedThreadPool(2)
      val outlineFuture = parallelExecutor.submit<OutlineResult> {
        pptGenerationService.planOutline(input, model = resolvedOutlineModel)
      }
      val scenarioFuture = parallelExecutor.submit<ScenarioResult> {
        pptGenerationService.inferScenarios(input, resolvedOutlineModel)
      }
      // Await BOTH futures before shutting down the executor
      outlineResult = outlineFuture.get()
      scenarioResult = scenarioFuture.get()
      parallelExecutor.shutdown()
      timing["outline"] = elapsedMs(outlineStart)
      timing["scenarios"] = elapsedMs(outlineStart)
    } else {
      outlineResult = pptGenerationService.planOutline(input, model = resolvedOutlineModel)
      timing["outline"] = elapsedMs(outlineStart)
      scenarioResult = null
    }

    if (outlineResult is OutlineResult.Err) {
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
          PptRunResponse(
              stage = normalizedStage,
              status = "error",
              input = input,
              errors = outlineResult.errors.map { it.toMap() },
              timingMs = timing,
          ),
      )
    }

    val outline = (outlineResult as OutlineResult.Ok).outline
    if (normalizedStage == "outline") {
      return ResponseEntity.ok(
          okResponse(
              "outline",
              input = input,
              outline = outline,
              timing = timing,
              modelsUsed = mapOf("outline" to resolvedOutlineModel.id),
          ),
      )
    }

    // Fail fast if scenario inference failed on pptx stage
    if (scenarioResult is ScenarioResult.Err) {
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
          PptRunResponse(
              stage = "pptx",
              status = "error",
              input = input,
              outline = outline,
              errors = scenarioResult.errors.map { it.toMap() },
              timingMs = timing,
          ),
      )
    }

    // Resolve stance from scenario brief
    val scenarioBrief = (scenarioResult as? ScenarioResult.Ok)?.brief
    val stance = scenarioBrief?.let { DeckStanceResolver.resolve(it) }

    val contentStart = System.nanoTime()
    val contentResult =
        pptGenerationService.generateContent(input, outline, stance, listOf(resolvedContentModel))
    timing["content"] = elapsedMs(contentStart)

  return when (contentResult) {
      is ContentResult.Ok -> {
        if (normalizedStage == "content") {
          ResponseEntity.ok(
              okResponse(
                  "content",
                  input = input,
                  outline = outline,
                  content = contentResult.deck,
                  timing = timing,
                  modelsUsed = mapOf(
                      "outline" to resolvedOutlineModel.id,
                      "content" to resolvedContentModel.id,
                  ),
              ),
          )
        } else {
          val themeStart = System.nanoTime()
          val themeResult = pptGenerationService.pickThemeColors(outline, stance, resolvedContentModel)
          timing["theme"] = elapsedMs(themeStart)

          when (themeResult) {
            is ThemeColorResult.Err ->
                ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                    PptRunResponse(
                        stage = "pptx",
                        status = "error",
                        input = input,
                        outline = outline,
                        content = contentResult.deck,
                        errors = themeResult.errors.map { it.toMap() },
                        timingMs = timing,
                    ),
                )
            is ThemeColorResult.Ok -> {
              val sectionLayouts = outline.sections.associate { it.id to it.layoutProfile }
              val pptxStart = System.nanoTime()
              val export = pptxExportService.render(
                  contentResult.deck,
                  input.topic,
                  themeResult.palette.colors,
                  sectionLayouts,
              )
              timing["pptx"] = elapsedMs(pptxStart)
              when (export) {
                is PptxExportResult.Ok ->
                    ResponseEntity.ok(
                        okResponse(
                            "pptx",
                            input = input,
                            outline = outline,
                            content = contentResult.deck,
                            timing = timing,
                            modelsUsed = mapOf(
                                "outline" to resolvedOutlineModel.id,
                                "content" to resolvedContentModel.id,
                                "theme" to resolvedContentModel.id,
                            ),
                            pptx = export.info.toResponse(),
                            themeColors = themeResult.palette.colors,
                            scenarios = scenarioBrief?.scenarios,
                            deckStance = stance,
                        ),
                    )
                is PptxExportResult.Err ->
                    ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                        PptRunResponse(
                            stage = "pptx",
                            status = "error",
                            input = input,
                            outline = outline,
                            content = contentResult.deck,
                            themeColors = themeResult.palette.colors,
                            errors = listOf(mapOf("type" to "pptx_render_failed", "message" to export.message)),
                            timingMs = timing,
                        ),
                    )
              }
            }
          }
        }
      }
      is ContentResult.Err ->
          ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
              PptRunResponse(
                  stage = "content",
                  status = "error",
                  input = input,
                  outline = outline,
                  errors = contentResult.errors.map { it.toMap() },
                  timingMs = timing,
              ),
          )
    }
  }

  /**
   * Full pipeline regeneration under a different scenario/stance.
   * Runs: resolve stance → planOutline(input, stance) → generateContent → pickThemeColors → render pptx.
   * The new outline may differ in section titles, structure, and layoutProfile from the first generation.
   */
  @PostMapping("/restyle", consumes = [MediaType.APPLICATION_JSON_VALUE])
  fun restyle(@RequestBody json: String): ResponseEntity<PptRunResponse> {
    val timing = linkedMapOf<String, Long>()
    val request =
        try {
          Json.fromJson(json, PptRestyleRequest::class.java)
        } catch (e: Exception) {
          return ResponseEntity.badRequest().body(
              PptRunResponse(
                  stage = "restyle",
                  status = "error",
                  errors = listOf(
                      mapOf(
                          "type" to "invalid_json",
                          "message" to (e.message ?: "Failed to parse restyle request"),
                      ),
                  ),
                  timingMs = timing,
              ),
          )
        }
    val input = PptInput(
        topic = request.topic,
        brief = request.brief,
        audience = request.audience,
        slideCount = request.slideCount,
    )

    // Resolve stance from provided scenarios or re-infer
    val scenarioStart = System.nanoTime()
    val scenarios: List<PresentationScenario>
    val stance: DeckStance
    if (request.scenarios != null && request.scenarios.isNotEmpty()) {
      scenarios = request.scenarios
      val found = scenarios.find { it.id == request.scenarioId }
          ?: return ResponseEntity.badRequest().body(
              PptRunResponse(
                  stage = "restyle",
                  status = "error",
                  errors = listOf(mapOf("type" to "unknown_scenario_id", "message" to "scenarioId '${request.scenarioId}' not found in scenarios")),
                  timingMs = timing,
              ),
          )
      stance = DeckStanceResolver.fromScenario(found)
    } else {
      val scenarioResult = pptGenerationService.inferScenarios(input, GatewayModel.DEEPSEEK)
      if (scenarioResult is ScenarioResult.Err) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            PptRunResponse(
                stage = "restyle",
                status = "error",
                errors = scenarioResult.errors.map { it.toMap() },
                timingMs = timing,
            ),
        )
      }
      val brief = (scenarioResult as ScenarioResult.Ok).brief
      scenarios = brief.scenarios
      stance = DeckStanceResolver.resolve(brief, request.scenarioId)
    }
    timing["scenarios"] = elapsedMs(scenarioStart)

    // Full pipeline: stance-aware outline planning
    val outlineStart = System.nanoTime()
    val outlineResult = pptGenerationService.planOutline(input, stance, GatewayModel.DEEPSEEK)
    timing["outline"] = elapsedMs(outlineStart)

    if (outlineResult is OutlineResult.Err) {
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
          PptRunResponse(
              stage = "restyle",
              status = "error",
              input = input,
              errors = outlineResult.errors.map { it.toMap() },
              timingMs = timing,
          ),
      )
    }

    val outline = (outlineResult as OutlineResult.Ok).outline

    val contentStart = System.nanoTime()
    val contentResult =
        pptGenerationService.generateContent(input, outline, stance, listOf(GatewayModel.DEEPSEEK))
    timing["content"] = elapsedMs(contentStart)

    if (contentResult is ContentResult.Err) {
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
          PptRunResponse(
              stage = "restyle",
              status = "error",
              input = input,
              outline = outline,
              errors = contentResult.errors.map { it.toMap() },
              timingMs = timing,
          ),
      )
    }

    val deck = (contentResult as ContentResult.Ok).deck

    val themeStart = System.nanoTime()
    val themeResult = pptGenerationService.pickThemeColors(outline, stance, GatewayModel.DEEPSEEK)
    timing["theme"] = elapsedMs(themeStart)

    if (themeResult is ThemeColorResult.Err) {
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
          PptRunResponse(
              stage = "restyle",
              status = "error",
              input = input,
              outline = outline,
              content = deck,
              errors = themeResult.errors.map { it.toMap() },
              timingMs = timing,
          ),
      )
    }

    val palette = (themeResult as ThemeColorResult.Ok).palette

    val sectionLayouts = outline.sections.associate { it.id to it.layoutProfile }
    val pptxStart = System.nanoTime()
    val export = pptxExportService.render(deck, input.topic, palette.colors, sectionLayouts)
    timing["pptx"] = elapsedMs(pptxStart)

    return when (export) {
      is PptxExportResult.Ok ->
          ResponseEntity.ok(
              okResponse(
                  "restyle",
                  input = input,
                  outline = outline,
                  content = deck,
                  timing = timing,
                  modelsUsed = mapOf(
                      "outline" to GatewayModel.DEEPSEEK.id,
                      "content" to GatewayModel.DEEPSEEK.id,
                      "theme" to GatewayModel.DEEPSEEK.id,
                  ),
                  pptx = export.info.toResponse(),
                  themeColors = palette.colors,
                  scenarios = scenarios,
                  deckStance = stance,
              ),
          )
      is PptxExportResult.Err ->
          ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
              PptRunResponse(
                  stage = "restyle",
                  status = "error",
                  input = input,
                  outline = outline,
                  content = deck,
                  themeColors = palette.colors,
                  errors = listOf(mapOf("type" to "pptx_render_failed", "message" to export.message)),
                  timingMs = timing,
              ),
          )
    }
  }

  /** Download a `.pptx` produced by `stage=pptx` (files live under `build/output/pptx/`). */
  @GetMapping("/download/{fileName}")
  fun download(@PathVariable fileName: String): ResponseEntity<Any> {
    val path = pptxExportService.resolveDownload(fileName)
        ?: return ResponseEntity.notFound().build()
    val resource = FileSystemResource(path)
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            ),
        )
        .body(resource)
  }

  private fun okResponse(
      stage: String,
      input: PptInput,
      outline: OutlineJson? = null,
      content: SlideDeckContent? = null,
      timing: Map<String, Long>,
      modelsUsed: Map<String, String> = emptyMap(),
      pptx: PptxFileResponse? = null,
      themeColors: List<String>? = null,
      scenarios: List<PresentationScenario>? = null,
      deckStance: DeckStance? = null,
  ): PptRunResponse =
      PptRunResponse(
          stage = stage,
          status = "ok",
          input = input,
          outline = outline,
          content = content,
          pptx = pptx,
          themeColors = themeColors,
          scenarios = scenarios,
          deckStance = deckStance,
          modelsUsed = modelsUsed,
          timingMs = timing,
      )

  private fun resolveModel(id: String): GatewayModel =
      GatewayModel.entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
          ?: throw IllegalArgumentException(
              "Unknown model '$id'. Known: ${GatewayModel.entries.joinToString { it.id }}",
          )

  private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

  companion object {
    private val STAGES = setOf("parse", "outline", "content", "pptx")
  }
}

data class PptxFileResponse(
    val fileName: String,
    val downloadUrl: String,
    val slideCount: Int,
)

private fun com.ppt.agent.app.output.PptxExportInfo.toResponse() =
    PptxFileResponse(
        fileName = fileName,
        downloadUrl = downloadPath,
        slideCount = slideCount,
    )

data class PptRunResponse(
    val stage: String,
    val status: String,
    val input: PptInput? = null,
    val outline: OutlineJson? = null,
    val content: SlideDeckContent? = null,
    val pptx: PptxFileResponse? = null,
    val themeColors: List<String>? = null,
    val scenarios: List<PresentationScenario>? = null,
    val deckStance: DeckStance? = null,
    val modelsUsed: Map<String, String> = emptyMap(),
    val errors: List<Map<String, Any?>> = emptyList(),
    val timingMs: Map<String, Long> = emptyMap(),
)

data class PptRestyleRequest(
    val topic: String,
    val brief: String,
    val audience: String,
    val slideCount: Int,
    val scenarioId: String,
    val scenarios: List<PresentationScenario>? = null,
)

private fun PptInputError.toMap(): Map<String, Any?> =
    when (this) {
      is PptInputError.InvalidJson -> mapOf("type" to "invalid_json", "message" to message)
      is PptInputError.MissingField -> mapOf("type" to "missing_field", "field" to field)
      is PptInputError.BlankField -> mapOf("type" to "blank_field", "field" to field)
      is PptInputError.BriefTooLong -> mapOf("type" to "brief_too_long", "length" to length, "max" to max)
      is PptInputError.InvalidSlideCount ->
          mapOf("type" to "invalid_slide_count", "value" to value, "min" to min, "max" to max)
      is PptInputError.SlideCountWrongType -> mapOf("type" to "slide_count_wrong_type", "actual" to actual)
    }

private fun OutlineError.toMap(): Map<String, Any?> =
    when (this) {
      is OutlineError.LlmFailure -> mapOf("type" to "llm_failure", "message" to message)
      is OutlineError.InvalidJson ->
          mapOf("type" to "invalid_json", "message" to message, "attempt" to attempt)
      is OutlineError.TruncatedOutput ->
          mapOf("type" to "truncated_output", "attempt" to attempt, "maxTokensUsed" to maxTokensUsed)
      is OutlineError.ValidationFailed ->
          mapOf("type" to "validation_failed", "violations" to violations, "attempt" to attempt)
      is OutlineError.ExhaustedRetries ->
          mapOf("type" to "exhausted_retries", "attempts" to attempts, "lastError" to lastError)
    }

private fun ContentError.toMap(): Map<String, Any?> =
    when (this) {
      is ContentError.SlideFailed ->
          mapOf("type" to "slide_failed", "index" to index, "sectionId" to sectionId, "message" to message)
      is ContentError.PartialFailure ->
          mapOf("type" to "partial_failure", "failedIndices" to failedIndices, "message" to message)
    }

private fun ThemeColorError.toMap(): Map<String, Any?> =
    when (this) {
      is ThemeColorError.LlmFailure -> mapOf("type" to "theme_llm_failure", "message" to message)
      is ThemeColorError.InvalidJson ->
          mapOf("type" to "theme_invalid_json", "message" to message, "attempt" to attempt)
      is ThemeColorError.ValidationFailed ->
          mapOf("type" to "theme_validation_failed", "violations" to violations, "attempt" to attempt)
      is ThemeColorError.ExhaustedRetries ->
          mapOf("type" to "theme_exhausted_retries", "attempts" to attempts, "lastError" to lastError)
    }

private fun ScenarioError.toMap(): Map<String, Any?> =
    when (this) {
      is ScenarioError.LlmFailure -> mapOf("type" to "scenario_llm_failure", "message" to message)
      is ScenarioError.InvalidJson ->
          mapOf("type" to "scenario_invalid_json", "message" to message, "attempt" to attempt)
      is ScenarioError.ValidationFailed ->
          mapOf("type" to "scenario_validation_failed", "violations" to violations, "attempt" to attempt)
      is ScenarioError.ExhaustedRetries ->
          mapOf("type" to "scenario_exhausted_retries", "attempts" to attempts, "lastError" to lastError)
    }
