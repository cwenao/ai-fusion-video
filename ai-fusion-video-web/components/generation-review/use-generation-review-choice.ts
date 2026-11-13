"use client";

import { useEffect, useState } from "react";
import { generationReviewApi, type GenerationReviewModel } from "@/lib/api/generation-review";

type Options =
  | { state: "loading" | "error" }
  | { state: "ready"; items: GenerationReviewModel[] };

export function useGenerationReviewChoice(mode: "image" | "video") {
  const [checked, setChecked] = useState(false);
  const [options, setOptions] = useState<Options>({ state: "loading" });
  const [selected, select] = useState<number>();
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!checked) {
      return;
    }
    const request = new AbortController();
    void generationReviewApi.models(mode, request.signal).then((items) => {
      if (!request.signal.aborted) {
        setOptions({ state: "ready", items });
        if (items.length === 1) {
          select(items[0].id);
        }
      }
    }).catch(() => {
      if (!request.signal.aborted) {
        setOptions({ state: "error" });
      }
    });
    return () => request.abort();
  }, [checked, mode, attempt]);

  const setEnabled = (enabled: boolean) => {
    setChecked(enabled);
    setOptions({ state: "loading" });
    select(undefined);
  };
  const retry = () => {
    setOptions({ state: "loading" });
    setAttempt((value) => value + 1);
  };
  const modelId = checked && options.state === "ready" && options.items.some((item) => item.id === selected)
    ? selected : undefined;
  return { checked, setEnabled, options, modelId, select, ready: !checked || modelId !== undefined, retry };
}

export type GenerationReviewChoice = ReturnType<typeof useGenerationReviewChoice>;
