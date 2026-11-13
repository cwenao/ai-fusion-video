import { http } from "./client";

export type GenerationReviewTarget =
  | "ASSET"
  | "SHOT_VIDEO"
  | "SHOT_FIRST_FRAME"
  | "SHOT_LAST_FRAME";

export interface GenerationReviewTargetRef {
  target: GenerationReviewTarget;
  targetId: number;
}

export interface GenerationReviewModel {
  id: number;
  name: string;
}

export interface GenerationReviewReport {
  state: "PENDING" | "RUNNING" | "COMPLETED" | "FAILED";
  modelName: string;
  result: {
    verdict: "MATCH" | "NEEDS_CHANGES" | "UNCERTAIN";
    summary: string;
    findings: { issue: string; evidence: string; suggestion: string }[];
    limitations: string[];
  } | null;
  errorMessage: string | null;
}

export const generationReviewApi = {
  models: (mode: "image" | "video", signal: AbortSignal) =>
    http.get<never, GenerationReviewModel[]>("/api/generation/reviews/models", {
      params: { kind: mode === "image" ? "IMAGE" : "VIDEO" },
      signal,
    }),
  result: (mediaId: string, scope: GenerationReviewTargetRef | undefined, signal: AbortSignal) =>
    http.get<never, GenerationReviewReport | null>(`/api/generation/reviews/media/${mediaId}`, {
      params: scope,
      signal,
    }),
};
