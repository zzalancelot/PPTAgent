# Scenario Style Switch — Full Pipeline Regeneration Spec

> **For coding agent:** Change **style switch** (`POST /v1/ppt/restyle`) from “keep outline, regen content/theme/pptx” to **full pipeline regeneration** under a **selected scenario**: `planOutline(input, stance)` → `generateContent` → `pickThemeColors` → `render pptx`. The new outline may differ in **section titles, section boundaries, layoutProfile, slide titles, and slide mix** from the first generation. **Section titles and page structure do NOT need to match the previous version.** Only the validated `PptInput` (topic/brief/audience/slideCount) and chosen `scenarioId` are fixed anchors.
>
> **Supersedes** the restyle sections in `docs/SCENARIO_STANCE_PROMPT.md` (§1 style switch, §2 restyle diagram, §7.2 restyle body). First-run `stage=pptx` behavior (outline ∥ scenarios, then recommended stance) stays unless noted below.

---

## 1. Product behavior

### First run (`POST /v1/ppt/run?stage=pptx`) — unchanged

1. Parse input.
2. **Parallel:** `planOutline(input)` + `inferScenarios(input)`.
3. Resolve `DeckStance` from `recommendedScenarioId`.
4. `generateContent(input, outline, stance)` → `pickThemeColors(outline, stance)` → render pptx.
5. Return `outline`, `content`, `pptx`, `themeColors`, `scenarios`, `deckStance`.

> **Note:** First-run outline is still planned **without** stance (parallel path). Only style-switch regen passes stance into outline planning. Optional future improvement: plan first outline with recommended stance sequentially — **out of scope here**.

### Style switch (`POST /v1/ppt/restyle`) — **new behavior**

User selects a different scenario tag and clicks regenerate.

1. Request carries **same validated input** + **`scenarioId`** + optional cached **`scenarios[]`** (from first response).
2. **Do NOT** accept or require previous `outline` in the request.
3. Resolve `DeckStance` from `scenarios` + `scenarioId` (re-infer scenarios only if list omitted).
4. **Sequential full pipeline:**
   - `planOutline(input, stance)` — **NEW: stance-aware outline**
   - `generateContent(input, outline, stance)`
   - `pickThemeColors(outline, stance)`
   - `render pptx` with `sectionLayouts` from **new** outline
5. Return fresh `outline`, `content`, `pptx`, `themeColors`, `deckStance`, `scenarios` (echo scenarios list).

**Explicit product rules (agreed with user):**

| Must preserve | May change on regen |
|---------------|---------------------|
| `topic`, `brief`, `audience` from request | Section titles (`sections[].title`) |
| `scenarioId` (user selection) | Section ids / count / `slideRange` |
| `scenarios` list (when provided) | Per-slide titles, `slideType` mix |
| `input.slideCount` (25–30, validated) | `layoutProfile` per section |
| Same topic subject matter | `storyline`, `meta.tone`, `meta.oneLiner` |
| | Previous outline JSON entirely |

**Page count:** New outline must still satisfy `OutlineValidator` against `input.slideCount` (exact slide count for **this** request). The new deck **does not** need the same number of sections or the same per-section page allocation as the first generation — only `slides.length == input.slideCount`.

**Duration:** Same order of magnitude as first `pptx` run (~outline 1–2 min + content several minutes). Frontend must show **full-pipeline** loading, not “quick restyle”.

---

## 2. Pipeline diagrams

### First generation (unchanged)

```
PptInput
   ├─ parallel ─┬─ planOutline(input)           → OutlineJson
   │            └─ inferScenarios(input)        → ScenarioBrief
   └─ DeckStance(recommended)
         → generateContent → pickThemeColors → pptx
```

### Style switch (new)

```
PptInput + scenarioId + scenarios[]
   → DeckStance
   → planOutline(input, stance)     ← stance drives narrative + layoutProfile
   → generateContent(outline, stance)
   → pickThemeColors(outline, stance)
   → pptx(sectionLayouts from new outline)
```

**No** `inferScenarios` on regen when `scenarios` provided. **No** outline reuse.

---

## 3. Backend changes

### 3.1 `OutlinePlanner` — add optional `DeckStance`

**Interface** (`business/.../outline/OutlinePlanner.kt`):

```kotlin
fun plan(
    input: PptInput,
    stance: DeckStance? = null,
    model: GatewayModel = GatewayModel.DEEPSEEK,
): OutlineResult
```

**`PptGenerationService`:** mirror signature on `planOutline`.

**`OutlinePlannerImpl`:**

- When `stance != null`, append a stance block to the user message (see §4).
- When `stance != null`, after successful parse, **soft-check** (validator warning → retry feedback, not hard fail in v1): `outline.meta.narrativeArc` should equal `stance.narrativeArc` if both set. Prefer adding to validation feedback string on mismatch.
- Retry / token ladder unchanged.

### 3.2 `POST /v1/ppt/restyle` — rewrite orchestration

**Request DTO** (`PptRestyleRequest`):

```kotlin
data class PptRestyleRequest(
    val topic: String,
    val brief: String,
    val audience: String,
    val slideCount: Int,
    val scenarioId: String,
    val scenarios: List<PresentationScenario>? = null,
    // REMOVED: outline — do not require previous outline
)
```

**Remove** `slide_count_mismatch` check against old outline.

**Orchestration** (pseudocode):

```kotlin
fun restyle(json: String): ResponseEntity<PptRunResponse> {
    val request = parse(json)
    val input = PptInput(request.topic, request.brief, request.audience, request.slideCount)
    val (scenarios, stance) = resolveStance(request)

    timing["scenarios"] = ...

    val outlineResult = pptGenerationService.planOutline(input, stance, GatewayModel.DEEPSEEK)
    if (outlineResult is Err) return 422 with errors

    val outline = (outlineResult as Ok).outline
    timing["outline"] = ...

    val contentResult = pptGenerationService.generateContent(input, outline, stance, listOf(DEEPSEEK))
    // ... theme, pptx same as stage=pptx success path
    return okResponse("restyle", input, outline, content, timing, pptx, themeColors, scenarios, deckStance)
}
```

**Models:** Use `GatewayModel.DEEPSEEK` for all LLM steps in regen (match current main-flow testing default).

**Response `stage`:** `"restyle"` (keep for UI labeling).

**`timingMs` keys:** `scenarios`, `outline`, `content`, `theme`, `pptx`.

### 3.3 Parse restyle body with Gson

Keep `@RequestBody json: String` + `Json.fromJson(..., PptRestyleRequest::class.java)` (Jackson cannot deserialize nested Kotlin types). Update `PptRestyleRequestParseTest` — **remove** `outline` from fixture.

### 3.4 Error handling

| Case | HTTP | `errors[].type` |
|------|------|-----------------|
| Unknown `scenarioId` in provided list | 400 | `unknown_scenario_id` |
| Outline failure | 422 | outline error types |
| Content failure | 422 | `slide_failed`, etc. |
| Theme failure | 422 | `theme_*` |
| PPTX render failure | 422 | `pptx_render_failed` |

On outline failure, do **not** return partial content from a previous client outline (there is none).

---

## 4. Outline prompts — stance-aware planning

Add to **`outline_planner_system.txt`** (after Goal section):

```
## Presentation stance (optional)
When the user message includes a "Presentation stance" block, the entire outline — meta.tone, meta.narrativeArc, storyline beats, section purposes, layoutProfile per section, and slideType mix — must be planned FOR THAT SCENARIO.
- layoutProfile choices must reflect the scenario (e.g. persuasion → pitch_bold / centered_impact; itinerary → timeline_flow; teaching → tutorial_friendly).
- Section titles and chapter structure should match the scenario's audience frame and purpose, not a generic version of the topic.
- meta.narrativeArc MUST match the stance narrative_arc when stance is provided.
```

Add to **`outline_planner_user.txt`** optional block (injected by `OutlinePlannerImpl` when `stance != null`):

```
## Presentation stance
scenario: {stance_label}
audience_frame: {stance_audience_frame}
voice_tone: {stance_voice_tone}
narrative_arc: {stance_narrative_arc}
color_mood: {stance_color_mood}

Plan this {slide_count}-slide outline specifically for the scenario above. Section layoutProfile values should visibly differ from a generic deck of the same topic.
```

Placeholders map from `DeckStance` fields (`label`, `audienceFrame`, `voiceTone`, `narrativeArc`, `colorMood`).

**Do not** change `layoutProfile` enum set or validator rules 9–11 in `OutlineValidator`.

---

## 5. Frontend changes (`frontend/`)

### 5.1 API types (`api.ts`)

```typescript
export interface RestyleRequestBody {
  topic: string;
  brief: string;
  audience: string;
  slideCount: number;
  scenarioId: string;
  scenarios?: PresentationScenario[];
  // REMOVED: outline
}
```

### 5.2 `App.tsx` — `handleRestyle`

- Build body **without** `outline`.
- On success: `setResult(updated)` — **use server `outline`**, do not merge old outline.
- Keep `scenarios` from response or previous `result.scenarios`.
- Guard: require `result?.input && result?.scenarios` (drop `result.outline` requirement).

### 5.3 `ResultView.tsx` — UX copy

- Helper text: “选择风格后重新生成将**重新规划大纲、文案、配色与 PPTX**，约需数分钟。”
- Button: “按此风格重新生成” (or keep “重新生成”).
- While `restyling`: show pipeline message “正在重新规划大纲并生成内容…”
- Consider showing `timingMs.outline` in timing bar after regen.

### 5.4 Loading state

`restyling` should block form + scenario tags (already implemented). User sees skeleton or overlay for **entire** right panel optional — at minimum disable download until complete.

---

## 6. Tests

### 6.1 `OutlinePlannerTest`

- `planWithStance_injectsStanceBlockIntoUserMessage` (fake adapter captures messages; assert stance label present).
- Valid outline with stance where `meta.narrativeArc` matches `stance.narrativeArc`.

### 6.2 `PptRestyleRequestParseTest`

- Body without `outline` deserializes.

### 6.3 `PptApiController` / integration (mock `PptGenerationService`)

- `restyle` calls `planOutline` with non-null stance **before** `generateContent`.
- `restyle` does **not** call `generateContent` with previous outline from request.

### 6.4 `PptGenerationServiceTest`

- `planOutline` forwards `stance` to planner when added to service signature.

---

## 7. Acceptance criteria

- [ ] `POST /v1/ppt/restyle` no longer accepts `outline` in request body
- [ ] Regen runs: stance resolve → **outline(stance)** → content → theme → pptx
- [ ] Response includes **new** `outline` (different from first run is allowed/expected)
- [ ] `OutlinePlanner.plan(input, stance)` injects stance into prompts
- [ ] `meta.narrativeArc` aligns with `stance.narrativeArc` when stance provided (validator feedback on mismatch)
- [ ] Frontend does not send or preserve old outline on regen
- [ ] `npm run typecheck` + `npm run build` pass
- [ ] `./gradlew :business:test :app:test` pass
- [ ] Manual: same topic, two scenarios → visibly different `sections[].layoutProfile` and/or section titles in UI outline view

---

## 8. Non-goals

- Re-planning first-run outline with stance (parallel path unchanged)
- `SECTION_STYLE_SPEC.md` per-section LLM stylist
- TEMPLATE render mode stance/layout
- Server-side session cache for scenarios (client still passes `scenarios[]`)
- Diff UI between old/new outline (nice-to-have later)
- Multi-model pool on regen (stay `deepseek` only)

---

## 9. Manual verification

```bash
./scripts/dev-up.sh
```

1. Run full `pptx` on a multi-scenario topic (e.g. 京都三日游 + CEO brief).
2. Note first `outline.sections` titles and `layoutProfile` values.
3. Select another scenario → “按此风格重新生成”.
4. Confirm:
   - Takes similar time to first run
   - New `outline` in response (section titles / layoutProfile may differ)
   - New pptx downloads
   - `deckStance.scenarioId` matches selected tag
5. Check `.run/logs/app.log` — restyle path logs outline timing before content.

---

## 10. Short agent prompt (copy to coding agent)

> Read and implement `docs/SCENARIO_FULL_REGEN_PROMPT.md` in PPTAgent.
>
> Change style switch to **full pipeline regen**: `planOutline(input, stance)` → content → theme → pptx. Remove `outline` from restyle request. Add stance to `OutlinePlanner` + outline prompts. Update frontend restyle body and stop merging old outline. Section titles and structure **may** differ from first generation; only `input.slideCount` must validate.
>
> Run `./gradlew :business:test :app:test` and `cd frontend && npm run typecheck && npm run build`. Do not commit unless asked.
