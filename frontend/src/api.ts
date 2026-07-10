import axios from "axios";

/** Pipeline stages exposed by `PptApiController`. */
export type Stage = "parse" | "outline" | "content" | "pptx" | "restyle";

/** Gateway model ids (see framework `GatewayModel`). */
export type ModelId = "deepseek" | "mimo" | "minimax";

/** Request body — mirrors the fixture JSON (snake_case `slide_count`). */
export interface RunRequestBody {
  topic: string;
  brief: string;
  audience: string;
  slide_count?: number;
}

/** Validated input echoed back by the server. */
export interface PptInput {
  topic: string;
  brief: string;
  audience: string;
  slideCount: number;
}

export interface OutlineMeta {
  topic: string;
  audience: string;
  slideCount: number;
  language: string;
  narrativeArc: string;
  tone: string;
  oneLiner: string;
}

export interface Storyline {
  hook: string;
  promise: string;
  openingBeats: string[];
  coreBeats: string[];
  closingBeats: string[];
  audienceMotivation: string;
}

export interface OutlineSection {
  id: string;
  title: string;
  purpose: string;
  slideRange: number[];
  layoutProfile: string;
}

export interface OutlineSlide {
  index: number;
  sectionId: string;
  slideType: string;
  title: string;
  subtitleHint?: string | null;
  intent: string;
  bulletHints: string[];
  visualHint?: string | null;
  transition?: string | null;
}

export interface KeyTerm {
  term: string;
  definitionHint: string;
}

export interface ConsistencyRules {
  keyTerms: KeyTerm[];
  forbiddenTerms: string[];
  preferredPhrases: string[];
  avoidPatterns: string[];
  differentiationNote: string;
}

export interface OutlineJson {
  meta: OutlineMeta;
  storyline: Storyline;
  sections: OutlineSection[];
  slides: OutlineSlide[];
  consistency: ConsistencyRules;
}

export interface ContentMeta {
  topic: string;
  slideCount: number;
  language: string;
  modelsUsed: Record<string, string>;
}

export interface SlideContent {
  index: number;
  sectionId: string;
  slideType: string;
  title: string;
  subtitle?: string | null;
  bullets: string[];
  speakerNotes?: string | null;
  bodyText?: string | null;
}

export interface SlideDeckContent {
  meta: ContentMeta;
  slides: SlideContent[];
}

/** A structured pipeline error (shape varies by `type`). */
export type PipelineError = Record<string, unknown> & { type?: string; message?: string };

export interface PptxFileInfo {
  fileName: string;
  downloadUrl: string;
  slideCount: number;
}

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

export interface RunResponse {
  stage: Stage;
  status: "ok" | "error";
  input?: PptInput;
  outline?: OutlineJson;
  content?: SlideDeckContent;
  pptx?: PptxFileInfo;
  themeColors?: string[];
  scenarios?: PresentationScenario[];
  deckStance?: DeckStance;
  modelsUsed?: Record<string, string>;
  errors: PipelineError[];
  timingMs: Record<string, number>;
}

export interface RestyleRequestBody {
  topic: string;
  brief: string;
  audience: string;
  slideCount: number;
  scenarioId: string;
  scenarios?: PresentationScenario[];
}

// `content` can run several minutes for a full 27-slide deck.
const http = axios.create({ timeout: 15 * 60 * 1000 });

/** Convert non-RunResponse error body (e.g. Spring { error, message }) to a synthetic RunResponse. */
function normalizeResponse(data: unknown, fallbackStage: Stage): RunResponse {
  const d = data as Record<string, unknown>;
  if (d && typeof d === "object" && "error" in d && !("stage" in d)) {
    return {
      stage: fallbackStage,
      status: "error",
      errors: [{ type: String(d.error), message: String(d.message ?? d.error) }],
      timingMs: {},
    };
  }
  return d as unknown as RunResponse;
}

export async function runPipeline(
  body: RunRequestBody,
  stage: Stage,
  model: ModelId,
): Promise<RunResponse> {
  const { data } = await http.post<RunResponse>("/v1/ppt/run", body, {
    params: { stage, model },
    validateStatus: (s) => s < 600,
  });
  return normalizeResponse(data, stage);
}

export interface PingResponse {
  model: string;
  text: string | null;
}

export async function pingModel(model: ModelId): Promise<PingResponse> {
  const { data } = await http.get<PingResponse>("/v1/ppt/ping", { params: { model } });
  return data;
}

export interface HealthResponse {
  status: string;
  stages: string[];
  defaultStage: string;
}

export async function fetchHealth(): Promise<HealthResponse> {
  const { data } = await http.get<HealthResponse>("/v1/ppt/health", { timeout: 8000 });
  return data;
}

export async function restylePipeline(body: RestyleRequestBody): Promise<RunResponse> {
  const { data } = await http.post<RunResponse>("/v1/ppt/restyle", body, {
    validateStatus: (s) => s < 600,
  });
  return normalizeResponse(data, "restyle");
}
