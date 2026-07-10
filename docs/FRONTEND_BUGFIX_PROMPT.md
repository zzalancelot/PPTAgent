# Frontend Bugfix Spec

> **For coding agent:** Implement all fixes in `frontend/`. Frontend-only unless unavoidable. Do not commit unless asked.

## Scope

Repo: `/Users/zhaozian/code/LearnCode/PPTAgent/frontend`  
Stack: React 18 + TypeScript + Vite + Ant Design 5 + axios  
API proxy: Vite `/v1` → `http://localhost:8080`

### Key files

| File | Role |
|------|------|
| `src/App.tsx` | `loading`, `result`, `networkError`, `handleRun` |
| `src/api.ts` | Types + `runPipeline` / `restylePipeline` |
| `src/components/ResultView.tsx` | Results, PPTX download, scenario tags, restyle |
| `src/components/InputForm.tsx` | Input form, debug stage/model |
| `src/components/OutlineView.tsx` | Outline display |
| `src/components/ContentView.tsx` | Slide content grid |
| `src/components/ErrorList.tsx` | Pipeline error humanization |
| `src/slideType.ts` | Slide type labels + model colors |

### Backend contract (`PptApiController.kt`)

```kotlin
data class PptRunResponse(
    val stage: String,           // parse | outline | content | pptx | restyle
    val status: String,          // ok | error
    val input: PptInput?,
    val outline: OutlineJson?,
    val content: SlideDeckContent?,
    val pptx: PptxFileResponse?,
    val themeColors: List<String>?,
    val scenarios: List<PresentationScenario>?,
    val deckStance: DeckStance?,
    val modelsUsed: Map<String, String>,
    val errors: List<Map<String, Any?>>,
    val timingMs: Map<String, Long>,
)
```

Non-pipeline errors (e.g. unknown model) return HTTP 400 as `{ "error": "bad_request", "message": "..." }` — **not** a `PptRunResponse`.

---

## P0 — Correctness

### 1. Restyle race overwrites newer run result

**Repro:** Complete pptx run A → click scenario tag (restyle R) → while R is in flight, submit new run B → when R finishes, `ResultView.onResultUpdate` overwrites B with stale restyle data.

**Fix:** Lift restyle state to `App.tsx`. Use a request-generation counter (or `AbortController`); only `setResult` if generation still matches. Disable form + scenario tags while `loading` OR `restyling`.

### 2. Form enabled during restyle

Pass `restyling` from `App` to `InputForm`; disable submit while restyling.

### 3. Stale PPTX download during restyle

Disable download button and show regenerating state until restyle completes. Optionally overlay content/pptx area.

### 4. Restyle errors not shown

On `updated.status === "error"`, surface `updated.errors` via `ErrorList` + descriptive toast. Keep previous result visible with error banner.

---

## P1 — API / errors

### 5. Missing `restyle` stage

Extend `Stage` (or `ResponseStage`) and `STAGE_LABEL` — show「风格切换」not raw `restyle`.

### 6. Non-RunResponse bodies

Detect `{ error, message }` responses; convert to synthetic `RunResponse` error or throw with readable message.

### 7. `validateStatus` inconsistency

Change `(s) => s < 500 || s === 500` → `(s) => s < 600`.

### 8. `errorMessage()` ignores `response.data`

Extract `response.data.message` from axios errors when present.

### 9. Missing `modelsUsed` on `RunResponse` type

Add field; optionally display when `content` is absent.

---

## P2 — UX

### 10. `themeColors` never displayed

Show color swatches near PPTX / style section after pptx/restyle.

### 11. Scenario tags lack context

Add `Tooltip` with `description`; show current `deckStance` summary (label, colorMood, voiceTone).

### 12. `layoutProfile` missing in `OutlineView`

Show per-section `layoutProfile` tag in sections timeline.

### 13. `modelColor()` wrong for `deepseek-pro` / `deepseek-flash`

Map actual gateway model ids used in `meta.modelsUsed`.

### 14. `TimingBar` missing labels

Add: `scenarios`→场景分析, `theme`→配色, `restyle`→风格切换.

### 15. `ErrorList` missing types

Add: `scenario_*`, `theme_*`, `pptx_render_failed`, `unknown_scenario_id`, `slide_count_mismatch`.

### 16. Client `slide_count` validation

Form rules: integer, min 25, max 30; `InputNumber precision={0}`.

### 17. Silent restyle guard failure

When `!result.scenarios`, show warning: needs full pptx run first.

### 18. README outdated

Document `pptx`, `restyle`, `download` endpoints.

---

## Implementation notes

- Keep Chinese UI copy.
- Reuse `ErrorList`; don't duplicate error formatting.
- Lift async state (`loading`, `restyling`, request gen) to `App.tsx`.

### Suggested generation guard (`App.tsx`)

```ts
const genRef = useRef(0);

async function handleRun(...) {
  const gen = ++genRef.current;
  setLoading(true);
  // ...
  const data = await runPipeline(...);
  if (gen !== genRef.current) return;
  setResult(data);
}
```

Same pattern for restyle.

---

## Acceptance criteria

- [ ] New run during restyle is never overwritten by stale restyle response
- [ ] Form + scenario tags disabled during `loading` and `restyling`
- [ ] PPTX download disabled during restyle
- [ ] Restyle failure shows `ErrorList`, not only toast
- [ ] Stage header shows「风格切换」after restyle
- [ ] Invalid model shows readable error
- [ ] `themeColors` visible; scenario tooltips; section `layoutProfile` shown
- [ ] `modelColor` works for `deepseek-pro` / `deepseek-flash`
- [ ] `slide_count` validated 25–30 integer client-side
- [ ] `npm run typecheck` and `npm run build` pass

## Verification

```bash
cd frontend && npm run typecheck && npm run build
```

Manual (with `./scripts/dev-up.sh`):

1. Full pptx → theme swatches + scenario tags
2. Switch scenario → form/download disabled during wait
3. During restyle, start new run → final result is the **new** run
4. Debug + invalid model → readable error
5. Restyle error → `ErrorList` visible
