"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { assetApi, type AssetItem, type AssetWithItems } from "@/lib/api/asset";
import { getApiErrorMessage, getApiErrorStatus } from "@/lib/api/api-error";
import { getAssetImagePrompt } from "@/lib/asset-image-prompt-source";
import type { Project } from "@/lib/api/project";
import { useCommandRequestKey } from "@/hooks/use-command-request-key";
import { enqueueProjectText, projectTextOutput } from "@/lib/project-text-generation";
import { usePipelineStore } from "@/lib/store/pipeline-store";
import { userFacingError } from "@/lib/user-facing-error";
import { assetImagePromptContext, ASSET_IMAGE_PROMPT_INSTRUCTION } from "./asset-image-prompt-context";
import { getAssetItemVariant } from "@/lib/asset-item-variant";
import { getAppearance } from "./asset-types";
import type { AssetImagePromptTemplate } from "./asset-image-prompt-templates";

export interface BatchPromptTarget {
  asset: AssetWithItems;
  item: AssetItem;
  template: AssetImagePromptTemplate;
}

export interface BatchPromptDraft extends BatchPromptTarget {
  status: "queued" | "running" | "ready" | "failed";
  text: string;
  savedText: string;
  savedInBatch?: boolean;
  taskId?: string;
  error?: string;
}

export function useAssetBatchPrompts(project: Project | null, onSaved: () => void) {
  const [drafts, setDrafts] = useState<BatchPromptDraft[]>([]);
  const [savingId, setSavingId] = useState<number | null>(null);
  const saving = useRef(false);
  const launched = useRef(new WeakSet<BatchPromptDraft>());
  const requests = useCommandRequestKey();
  const running = drafts.some(({ status }) => status === "running" || status === "queued");
  const active = drafts.find(({ status }) => status === "running");
  const next = drafts.find(({ status }) => status === "queued");
  const task = usePipelineStore((state) => state.tasks.find(({ id }) => id === active?.taskId));

  useEffect(() => {
    if (active || !next || !project || launched.current.has(next)) {
      return;
    }
    launched.current.add(next);
    const { asset, item, template } = next;
    const name = item.name?.trim() || asset.name;
    const taskId = enqueueProjectText({
      projectId: project.id,
      kind: "assetImagePrompt",
      title: `AI 写生图提示词 · ${name}`,
      conversationId: crypto.randomUUID(),
      context: assetImagePromptContext(project, asset, {
        name,
        type: item.itemType ?? "",
        appearance: getAppearance(item),
        variant: getAssetItemVariant(item.properties),
      }, template),
      draft: next.savedText,
      instruction: ASSET_IMAGE_PROMPT_INSTRUCTION,
    });
    setDrafts((current) => current.map((row) => row === next ? { ...row, taskId, status: "running" } : row));
  }, [active, next, project]);

  useEffect(() => {
    if (!active || task?.status === "running") {
      return;
    }
    const text = projectTextOutput(task);
    const error = task?.status === "error" ? userFacingError(task.state.error)
      : task?.status === "cancelled" ? "本项生成已取消。"
      : task ? "AI 未返回可用的提示词。" : "任务记录已移除。";
    setDrafts((current) => current.map((row) => row === active
      ? text ? { ...row, text, status: "ready" } : { ...row, error, status: "failed" }
      : row));
  }, [active, task]);

  function start(targets: BatchPromptTarget[]) {
    setDrafts(targets.map((target) => ({
      ...target, status: "queued", text: "", savedText: getAssetImagePrompt(target.item),
    })));
  }

  function retry(id: number) {
    setDrafts((current) => current.map((row) => row.item.id === id
      ? { ...row, status: "queued", taskId: undefined, error: undefined } : row));
  }

  function edit(id: number, text: string) {
    setDrafts((current) => current.map((row) => row.item.id === id ? { ...row, text } : row));
  }

  async function save(row: BatchPromptDraft) {
    if (saving.current) {
      return;
    }
    saving.current = true;
    setSavingId(row.item.id);
    const patch = {
      id: row.item.id,
      version: row.item.version,
      assetVersion: row.item.assetVersion,
      propertyChanges: { imagePrompt: row.text },
    };
    try {
      const item = await assetApi.updateItem(patch, requests.forPayload(patch));
      requests.clear();
      setDrafts((current) => current.map((candidate) => {
        if (candidate.item.id === item.id) {
          return { ...candidate, item, savedText: getAssetImagePrompt(item), savedInBatch: true, error: undefined };
        }
        if (candidate.item.assetId === item.assetId && candidate.item.assetVersion === patch.assetVersion) {
          return { ...candidate, item: { ...candidate.item, assetVersion: item.assetVersion } };
        }
        return candidate;
      }));
      onSaved();
    } catch (cause) {
      let error = getApiErrorMessage(cause);
      if (getApiErrorStatus(cause) === 409) {
        error = "资产已被其他操作修改。请复制本草稿，在资产管理中刷新并核对最新内容后保存。";
        onSaved();
      }
      setDrafts((current) => current.map((candidate) => candidate.item.id === row.item.id
        ? { ...candidate, error } : candidate));
    } finally {
      saving.current = false;
      setSavingId(null);
    }
  }

  const saved = useMemo(() => drafts.filter((row) => row.savedInBatch && row.text === row.savedText), [drafts]);
  return { drafts, savingId, running, saved, start, retry, edit, save };
}
