"use client";

import { useEffect, useReducer, useState } from "react";
import { getApiErrorMessage } from "@/lib/api/api-error";
import { promptTemplateApi, type PromptTemplate } from "@/lib/api/prompt-template";

type CatalogState =
  | { status: "loading" }
  | { status: "ready"; templates: PromptTemplate[] }
  | { status: "error"; error: string };

export function usePromptTemplates() {
  const [request, reload] = useReducer((count: number) => count + 1, 0);
  const [result, setResult] = useState<{ request: number; catalog: CatalogState } | null>(null);
  const catalog: CatalogState = result?.request === request ? result.catalog : { status: "loading" };

  useEffect(() => {
    const controller = new AbortController();
    promptTemplateApi.list(controller.signal).then((templates) => {
      if (!controller.signal.aborted) {
        setResult({ request, catalog: { status: "ready", templates } });
      }
    }).catch((error) => {
      if (!controller.signal.aborted) {
        setResult({ request, catalog: { status: "error", error: getApiErrorMessage(error) } });
      }
    });
    return () => controller.abort();
  }, [request]);

  return { catalog, reload };
}
