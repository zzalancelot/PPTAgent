# Presentation Scenario & Style Switch — Implementation Spec

> **For coding agent:** Add a **Scenario Planner** that runs **in parallel with outline planning** on `PptInput`, auto-selects a **recommended scenario (A)** for the first full generation, and exposes **style-switch tags (C)** on the frontend so the user can **re-generate content + theme + pptx** with a different scenario while **keeping the same `OutlineJson`**. Do **not** re-plan the outline on style switch.

---

## 1. Product behavior (A + C)

### First run (`stage=pptx`)

1. User submits `topic` / `brief` / `audience` / `slide_count`.
2. Backend **in parallel**:
   - `OutlinePlanner` → `OutlineJson` (unchanged responsibility)
   - `ScenarioPlanner` → `ScenarioBrief` (3–5 plausible presentation scenarios + one `recommendedScenarioId`)
3. Backend auto-picks **`recommendedScenarioId`** → resolves `DeckStance`.
4. Pipeline continues with **fixed stance**:
   - `generateContent(input, outline, stance)`
   - `pickThemeColors(outline, stance)`
   - render `.pptx`
5. API response includes:
   - `outline`, `content`, `pptx`, `themeColors`
   - **`scenarios`** (all options for UI tags)
   - **`deckStance`** (the active / recommended one)

### Style switch (user clicks another tag)

1. Frontend calls **`POST /v1/ppt/restyle`** with:
   - same `input`
   - same `outline` (from first response — **do not re-run outline**)
   - `scenarioId` (the tag the user selected)
2. Backend resolves new `DeckStance` from cached scenario list (or re-resolve from `scenarios` payload).
3. Re-run only:
   - `generateContent` (new voice/tone)
   - `pickThemeColors` (new color mood)
   - `pptx` render
4. Return updated `content`, `themeColors`, `pptx`, `deckStance`; `outline` echoed unchanged.

**Non-goals for restyle:** no `planOutline`, no `inferScenarios` (unless scenarios omitted from request — see §7).

---

## 2. Pipeline diagram

### First generation

```
PptInput
   ├─ parallel ─┬─ OutlinePlanner (DEEPSEEK_PRO)  → OutlineJson
   │            └─ ScenarioPlanner (DEEPSEEK_FLASH) → ScenarioBrief
   │
   └─ resolveDeckStance(brief, recommendedScenarioId)
         → DeckStance
              ├─ generateContent(outline + stance)
              ├─ pickThemeColors(outline + stance)
              └─ render pptx
```

### Restyle

```
(input, outline, scenarioId)
   → resolveDeckStance from scenarios[]
   → generateContent + pickThemeColors + pptx
   (outline unchanged)
```

---

## 3. New module location

All new logic in **`business`** package `com.ppt.agent.business.scenario` (no new Gradle module).

Wire beans in `BusinessConfiguration`; orchestration parallel calls in **`app`** (`PptApiController` or thin `PipelineOrchestrator` in app).

`renderer` unchanged except consuming new theme colors as today.

---

## 4. Data models

### 4.1 `PresentationScenario` (one option)

```kotlin
data class PresentationScenario(
    /** Stable id, snake_case, e.g. "client_pitch", "team_report", "travel_guide". */
    val id: String,
    /** Short UI label, e.g. "向客户推介方案". */
    val label: String,
    /** One sentence: when/why you'd use this version. */
    val description: String,
    /** Implied primary audience for this scenario. */
    val audienceFrame: String,
    /** Drives Morandi hue family. See enum below. */
    val colorMood: String,
    /** Drives slide copy voice. Free text + mapped narrativeArc. */
    val voiceTone: String,
    /** Maps to existing OutlineMeta.narrativeArc allowed set when possible. */
    val narrativeArc: String,
    /** 0.0–1.0 confidence this scenario fits the user's brief. */
    val confidence: Double,
)
```

### 4.2 `ScenarioBrief` (LLM output)

```kotlin
data class ScenarioBrief(
    val scenarios: List<PresentationScenario>, // size 3–5
    val recommendedScenarioId: String,
    /** One sentence explaining why recommended fits brief best. */
    val recommendationRationale: String,
)
```

### 4.3 `DeckStance` (resolved, used downstream)

```kotlin
/** Active presentation stance for theme + content generation. */
data class DeckStance(
    val scenarioId: String,
    val label: String,
    val colorMood: String,
    val voiceTone: String,
    val narrativeArc: String,
    val audienceFrame: String,
)
```

Resolver:

```kotlin
object DeckStanceResolver {
    fun fromScenario(s: PresentationScenario): DeckStance = DeckStance(
        scenarioId = s.id,
        label = s.label,
        colorMood = s.colorMood,
        voiceTone = s.voiceTone,
        narrativeArc = s.narrativeArc,
        audienceFrame = s.audienceFrame,
    )

    fun resolve(brief: ScenarioBrief, scenarioId: String? = null): DeckStance {
        val id = scenarioId ?: brief.recommendedScenarioId
        val scenario = brief.scenarios.find { it.id == id }
            ?: brief.scenarios.first { it.id == brief.recommendedScenarioId }
        return fromScenario(scenario)
    }
}
```

---

## 5. Allowed enums (validator enforces)

### `colorMood`

`warm_earth`, `cool_slate`, `soft_green`, `dusty_rose`, `neutral_stone`, `ocean_mist`, `sunset_amber`

### `narrativeArc`

Must be in existing `ALLOWED_NARRATIVE_ARCS`:
`teaching`, `personal_story`, `decision_framework`, `persuasion`, `itinerary`, `general`

### `scenario.id`

`^[a-z][a-z0-9_]{2,40}$`, unique within brief.

### `scenarios.size`

3–5 inclusive.

### `recommendedScenarioId`

Must match one `scenarios[].id`.

### `confidence`

0.0–1.0; recommended scenario should have highest confidence (validator warning if not).

---

## 6. ScenarioPlanner

```kotlin
interface ScenarioPlanner {
    fun infer(input: PptInput, model: GatewayModel = GatewayModel.DEEPSEEK_FLASH): ScenarioResult
}

sealed class ScenarioResult {
    data class Ok(val brief: ScenarioBrief) : ScenarioResult()
    data class Err(val errors: List<ScenarioError>) : ScenarioResult()
}
```

- **Model:** `DEEPSEEK_FLASH`
- **Retries:** mirror `ThemeColorPickerImpl` — max 3 attempts, `max_tokens` 2048
- **Parse:** `Json.parseFirstObject` (no `endsWith("}")` trap)
- **No dependency on outline** — input only (`topic`, `brief`, `audience`, `slide_count`)

### Prompt files

- `business/src/main/resources/prompts/scenario_planner_system.txt`
- `business/src/main/resources/prompts/scenario_planner_user.txt`

Placeholders: `{topic}`, `{brief}`, `{audience}`, `{slide_count}`

### `scenario_planner_system.txt` (copy verbatim)

```
You are a presentation strategist. Given a deck topic, brief, and audience, enumerate plausible REAL-WORLD scenarios where this same subject could become a slide deck — each with a different purpose, voice, and visual mood.

## Task
Return 3–5 distinct presentation scenarios (not 3–5 outline structures). Pick ONE as recommended based on how well it matches the user's brief.

## Output — JSON only (no markdown):

{
  "scenarios": [
    {
      "id": "snake_case_id",
      "label": "short UI label in the input language",
      "description": "one sentence: when you'd use this deck",
      "audienceFrame": "who you're presenting to in this scenario",
      "colorMood": "warm_earth" | "cool_slate" | "soft_green" | "dusty_rose" | "neutral_stone" | "ocean_mist" | "sunset_amber",
      "voiceTone": "2-6 word voice description, e.g. persuasive and trustworthy",
      "narrativeArc": "teaching" | "personal_story" | "decision_framework" | "persuasion" | "itinerary" | "general",
      "confidence": 0.0-1.0
    }
  ],
  "recommendedScenarioId": "id of the best-matching scenario",
  "recommendationRationale": "one sentence why this scenario fits the brief best"
}

## Rules
1. Produce 3–5 scenarios that are GENUINELY different in purpose (e.g. CEO report vs travel guide vs client pitch — not synonyms).
2. Scenarios must be plausible for the SAME topic (e.g. Kyoto → executive itinerary report, tourist introduction, luxury client proposal, team offsite plan).
3. If the brief already states a clear purpose, still list alternatives but give the matching scenario highest confidence and set it as recommendedScenarioId.
4. colorMood should differ across scenarios when purposes differ (persuasion → neutral_stone/cool_slate; travel → warm_earth/sunset_amber; teaching → soft_green/neutral_stone).
5. voiceTone must be concrete enough to steer slide copy (not generic "professional").
6. Use the language implied by topic/brief/audience for label, description, audienceFrame, voiceTone.
7. JSON only.
```

### `scenario_planner_user.txt` (copy verbatim)

```
Infer 3–5 presentation scenarios for this deck input.

topic: {topic}
brief: {brief}
audience: {audience}
slide_count: {slide_count}

Return ONLY the ScenarioBrief JSON.
```

---

## 7. API changes

### 7.1 Extend `POST /v1/ppt/run?stage=pptx`

After `parse`, run **parallel**:

```kotlin
val outlineDeferred = async { pptGenerationService.planOutline(input, outlineModel) }
val scenarioDeferred = async { pptGenerationService.inferScenarios(input) }
val outlineResult = outlineDeferred.await()
val scenarioResult = scenarioDeferred.await()
```

On success, `stance = resolve(scenarioBrief, recommendedScenarioId)` and pass stance into content/theme.

**Extend `PptRunResponse`:**

```kotlin
val scenarios: List<PresentationScenarioResponse>? = null,
val deckStance: DeckStanceResponse? = null,
```

Echo `outline` as today.

### 7.2 New `POST /v1/ppt/restyle`

```kotlin
data class PptRestyleRequest(
    val topic: String,
    val brief: String,
    val audience: String,
    val slideCount: Int,
    val outline: OutlineJson,
    val scenarioId: String,
    /** Optional: pass scenarios from first response to avoid re-inferring. */
    val scenarios: List<PresentationScenario>? = null,
)
```

Handler:

1. Validate `outline.meta.slideCount == slideCount`.
2. Resolve stance: if `scenarios` provided → `DeckStanceResolver.resolve(ScenarioBrief(scenarios, scenarioId, ""), scenarioId)`; else call `inferScenarios` again and resolve (fallback only).
3. `generateContent(input, outline, stance)`
4. `pickThemeColors(outline, stance)`
5. `pptxExportService.render(...)`
6. Return same shape as `pptx` success + updated `deckStance`.

**Errors:** unknown `scenarioId` when scenarios list provided → 400.

### 7.3 Optional `stage=scenarios`

Preview-only: parse + inferScenarios, return `scenarios` + `recommendedScenarioId` without outline. **Optional** — implement if easy.

---

## 8. Downstream integration

### 8.1 `PptGenerationService` signatures

```kotlin
fun inferScenarios(input: PptInput, model: GatewayModel = GatewayModel.DEEPSEEK_FLASH): ScenarioResult

fun generateContent(
    input: PptInput,
    outline: OutlineJson,
    stance: DeckStance? = null,  // null = legacy behavior
    modelPool: List<GatewayModel> = ModelPool.DEFAULT,
): ContentResult

fun pickThemeColors(
    outline: OutlineJson,
    stance: DeckStance? = null,
    model: GatewayModel = GatewayModel.DEEPSEEK_FLASH,
): ThemeColorResult
```

### 8.2 Content generation

Inject stance into `slide_content_system.txt` or per-call user context:

```
## Presentation stance (scenario)
scenario: {stance_label}
voice: {voice_tone}
audience frame: {audience_frame}
narrative arc: {narrative_arc}

Write slide copy that matches this voice. Do not change slide structure — outline fixes slideType and titles.
```

Add placeholders to content prompt builder in `SlideContentGeneratorImpl`.

**Do not** change outline slide indices or types based on stance.

### 8.3 Theme colors

Extend `theme_color_user.txt` usage:

```
color_mood: {color_mood}
voice_tone: {voice_tone}
```

`ThemeColorPickerImpl.buildUserPrompt` reads `stance` when present; falls back to outline-only meta when null.

Morandi rules unchanged; `colorMood` steers hue family (document mapping in picker system prompt addendum).

### 8.4 Outline unchanged on restyle

`OutlineJson` from client must be used as-is. Validator runs on content against outline as today.

---

## 9. Frontend (style switch tags)

### 9.1 Types (`frontend/src/api.ts`)

```typescript
export interface PresentationScenario {
  id: string;
  label: string;
  description: string;
  audienceFrame: string;
  colorMood: string;
  voiceTone: string;
  narrativeArc: string;
  confidence: number;
}

export interface DeckStance {
  scenarioId: string;
  label: string;
  colorMood: string;
  voiceTone: string;
  narrativeArc: string;
  audienceFrame: string;
}

// Extend RunResponse:
scenarios?: PresentationScenario[];
deckStance?: DeckStance;
themeColors?: string[];
```

### 9.2 UI (`ResultView.tsx`)

When `result.pptx` exists and `result.scenarios?.length`:

- Render a **Tag.Group** (or clickable Tags) below the download card:
  - One tag per `scenario.label`
  - Active tag = `deckStance.scenarioId` (highlighted)
  - Inactive tags clickable
- On click:
  - Call `restylePipeline({ input, outline, scenarioId, scenarios })`
  - Show loading on tags / disable download until done
  - Replace `content`, `pptx`, `themeColors`, `deckStance` in parent state
  - **Keep** `outline` unchanged in UI

### 9.3 `restylePipeline` API helper

```typescript
export async function restylePipeline(body: RestyleRequestBody): Promise<RunResponse>
// POST /v1/ppt/restyle
```

### 9.4 UX copy

- Section title: **「演示风格」** or **「Presentation style」**
- Helper text: 「切换风格将保留大纲，重新生成文案、配色与 PPTX」
- Show `deckStance.label` + optional `recommendationRationale` on first load

---

## 10. Tests

### `ScenarioValidatorTest`

- valid 4-scenario fixture passes
- 2 scenarios fails (min 3)
- duplicate ids fails
- invalid colorMood / narrativeArc fails
- recommendedScenarioId not in list fails

### `ScenarioPlannerTest` (fake LlmAdapter)

- valid JSON → Ok
- invalid JSON → retries → Err

### `DeckStanceResolverTest`

- resolve by recommended
- resolve by explicit scenarioId
- unknown id falls back to recommended

### `PptApiController` / integration (optional)

- restyle with mock services: outline id stable, stance changes

### Run

```bash
./gradlew :business:test :app:compileKotlin
```

Frontend: manual smoke — generate pptx, click second tag, verify new download URL.

---

## 11. Acceptance criteria

- [ ] `ScenarioPlanner` + validator + prompts
- [ ] `inferScenarios` on `PptGenerationService`
- [ ] `stage=pptx` runs outline ∥ scenarios; uses recommended stance
- [ ] `generateContent` + `pickThemeColors` accept `DeckStance`
- [ ] `POST /v1/ppt/restyle` re-generates content + theme + pptx without re-outline
- [ ] Response includes `scenarios` + `deckStance`
- [ ] Frontend style tags + restyle call
- [ ] `./gradlew :business:test` green
- [ ] Outline **not** re-planned on tag switch (verified by test or log)

---

## 12. Non-goals

- User editing scenario text inline
- Caching outline/scenarios server-side by session id (client passes outline + scenarios)
- Re-inferring scenarios on every tag click (pass `scenarios` from first response)
- Changing `layoutProfile` on restyle (stays on outline sections)
- TEMPLATE render mode stance support

---

## 13. Example (Kyoto)

**Input:** topic=京都三日, brief=部门季度汇报行程方案, audience=CEO

**Scenarios returned:**

| id | label | colorMood | voiceTone |
|----|-------|-----------|-----------|
| `ceo_report` ★ | 向 CEO 汇报行程 | cool_slate | concise, data-driven |
| `client_luxury_pitch` | 高端客户方案推介 | neutral_stone | persuasive, premium |
| `team_guide` | 团队出行攻略 | warm_earth | friendly, practical |
| `travel_marketing` | 目的地种草介绍 | sunset_amber | inspiring, sensory |

★ = `recommendedScenarioId` (matches brief)

User clicks **「高端客户方案推介」** → restyle with same outline → warmer authority tone + different Morandi palette + new pptx.

---

## 14. Implementation order

1. Models + `ScenarioValidator` + prompts + `ScenarioPlannerImpl`
2. `DeckStanceResolver` + extend theme/content with stance
3. Parallel orchestration in `PptApiController` for `stage=pptx`
4. `POST /v1/ppt/restyle`
5. Frontend tags + API
6. Tests
