"use client";

import { useEffect, useRef, useState, type Dispatch, type SetStateAction } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { toast } from "sonner";
import type { AiModel, ModelPreset } from "@/lib/api/ai-model";
import { getApiErrorMessage } from "@/lib/api/api-error";
import { projectApi } from "@/lib/api/project";
import { loadAssetReference, validateAssetReferences } from "@/lib/asset-reference-client";
import { ASSET_REFERENCE_QUERY_KEYS, parseAssetReferenceIdentity, type AssetReference, type AssetReferenceMediaType } from "@/lib/asset-references";
import { resolveGenerationCapabilities, resolveReferenceImageUploadAvailability, type GenerationCapabilities, type ReferenceImageUploadAvailability } from "@/lib/generation-capabilities";
import {
  appendAssetReferences,
  GENERATION_ASSET_REFERENCE_FIELDS,
  generationReferenceUrls,
  type GenerationAssetReferenceField,
} from "./generation-asset-inputs";
import { generationFormForModel } from "./generation-utils";
import type { GenerationFormState, WorkbenchMode } from "./generation-types";

interface Options {
  mode: WorkbenchMode;
  form: GenerationFormState;
  setForm: Dispatch<SetStateAction<GenerationFormState>>;
  models: AiModel[];
  presets: ModelPreset[];
  modelId?: number;
  setModelId: Dispatch<SetStateAction<number | undefined>>;
  loadingModels: boolean;
  capabilities: GenerationCapabilities | null;
  imageAvailability: ReferenceImageUploadAvailability & { publicMediaAccessConfigured: boolean; runtimeConfigLoaded: boolean };
}

interface ProjectContext {
  id: number | null;
  name: string;
}

async function projectContext(projectId: number | null): Promise<ProjectContext> {
  if (projectId === null) {
    return { id: null, name: "非项目资产" };
  }
  const project = await projectApi.get(projectId);
  return { id: project.id, name: project.name };
}

export function useGenerationAssetReferences(options: Options) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const query = searchParams.toString();
  const latest = useRef(options);
  latest.current = options;
  const [pickerTarget, setPickerTarget] = useState<GenerationAssetReferenceField | "mixed" | null>(null);
  const [context, setContext] = useState<ProjectContext | null>(null);
  const [sources, setSources] = useState<AssetReference[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const activeUrls = new Set(generationReferenceUrls(options.form));
  const activeSources = sources.filter((source) => activeUrls.has(source.reference));
  const field = pickerTarget && pickerTarget !== "mixed" ? pickerTarget : undefined;
  const fieldConfig = field ? GENERATION_ASSET_REFERENCE_FIELDS[field] : undefined;

  const selectionIssue = (selection: AssetReference[]): string | null => {
    try {
      if (context && selection.some((source) => source.projectId !== context.id)) {
        throw new Error("请先清空项目参考，再选择其他项目");
      }
      appendAssetReferences(options.form, options.capabilities, options.mode, selection, options.imageAvailability, field);
      return null;
    } catch (cause) {
      return getApiErrorMessage(cause);
    }
  };

  const confirm = async (selection: AssetReference[]) => {
    if (!selection.length) {
      throw new Error("请先选择参考素材");
    }
    const snapshot = latest.current;
    const issue = selectionIssue(selection);
    if (issue) {
      throw new Error(issue);
    }
    const [validated, nextContext] = await Promise.all([validateAssetReferences(selection), projectContext(selection[0].projectId)]);
    if (latest.current.form !== snapshot.form || latest.current.modelId !== snapshot.modelId) {
      throw new Error("模型或参考栏已变化，请重新确认选择");
    }
    snapshot.setForm(appendAssetReferences(snapshot.form, snapshot.capabilities, snapshot.mode, validated, snapshot.imageAvailability, field));
    setSources([...activeSources, ...validated]);
    setContext(nextContext);
    setPickerTarget(null);
    setError("");
    toast.success(`已加入 ${validated.length} 项资产参考`);
  };

  useEffect(() => {
    if (options.loadingModels || !options.imageAvailability.runtimeConfigLoaded) {
      return;
    }
    let cancelled = false;
    let identity;
    try {
      identity = parseAssetReferenceIdentity(new URLSearchParams(query));
    } catch (cause) {
      setError(getApiErrorMessage(cause));
      return;
    }
    if (!identity) {
      setBusy(false);
      return;
    }
    setBusy(true);
    setError("");
    const snapshot = latest.current;
    void (async () => {
      const source = await loadAssetReference(identity);
      const [validated, nextContext] = await Promise.all([validateAssetReferences([source]), projectContext(source.projectId)]);
      if (cancelled) {
        return;
      }
      if (latest.current.form !== snapshot.form || latest.current.modelId !== snapshot.modelId) {
        throw new Error("模型或参考栏已变化，请重试导入资产");
      }
      if (context && source.projectId !== context.id) {
        throw new Error("请先清空项目参考，再导入其他项目资产");
      }
      const candidates = [snapshot.models.find((model) => model.id === snapshot.modelId), ...snapshot.models.filter((model) => model.id !== snapshot.modelId)].filter((model): model is AiModel => Boolean(model));
      let chosen: { model: AiModel; form: GenerationFormState } | null = null;
      let lastIssue = "没有支持此素材类型的可用模型，请先配置模型";
      for (const model of candidates) {
        const capabilities = resolveGenerationCapabilities(model, snapshot.presets);
        const base = model.id === snapshot.modelId ? snapshot.form : generationFormForModel(snapshot.form, capabilities);
        if (model.id !== snapshot.modelId && generationReferenceUrls(snapshot.form).length > 0) {
          continue;
        }
        try {
          const availability = resolveReferenceImageUploadAvailability(capabilities, snapshot.imageAvailability.publicMediaAccessConfigured, snapshot.imageAvailability.runtimeConfigLoaded);
          chosen = { model, form: appendAssetReferences(base, capabilities, snapshot.mode, validated, availability) };
          break;
        } catch (cause) {
          lastIssue = getApiErrorMessage(cause);
        }
      }
      if (!chosen) {
        throw new Error(lastIssue);
      }
      snapshot.setModelId(chosen.model.id);
      snapshot.setForm(chosen.form);
      setSources((current) => [...current.filter((entry) => generationReferenceUrls(snapshot.form).includes(entry.reference)), ...validated]);
      setContext(nextContext);
      const params = new URLSearchParams(query);
      for (const key of ASSET_REFERENCE_QUERY_KEYS) {
        params.delete(key);
      }
      router.replace(`${pathname}${params.size ? `?${params}` : ""}`, { scroll: false });
      toast.success(chosen.model.id === snapshot.modelId ? "资产已加入参考栏" : `资产已加入参考栏，已选择 ${chosen.model.name}`);
    })().catch((cause) => {
      if (!cancelled) {
        setError(getApiErrorMessage(cause));
      }
    }).finally(() => {
      if (!cancelled) {
        setBusy(false);
      }
    });
    return () => {
      cancelled = true;
    };
  // The URL/retry is the import command. Form/model edits must not reapply it.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, retry, options.loadingModels, options.imageAvailability.runtimeConfigLoaded, pathname, router]);

  const mediaTypes: AssetReferenceMediaType[] = [];
  if (fieldConfig) {
    mediaTypes.push(fieldConfig.mediaType);
  } else if (pickerTarget === "mixed") {
    const capabilities = options.capabilities;
    if (capabilities && (capabilities.supportsReferenceImages || capabilities.supportsFirstFrame || capabilities.supportsLastFrame)) {
      mediaTypes.push("image");
    }
    if (options.mode === "video" && capabilities?.supportsReferenceVideos) {
      mediaTypes.push("video");
    }
    if (options.mode === "video" && capabilities?.supportsReferenceAudios) {
      mediaTypes.push("audio");
    }
  }

  const clear = () => {
    options.setForm((current) => ({ ...current, referenceImages: [], firstFrame: [], lastFrame: [], referenceVideos: [], referenceAudios: [] }));
    setSources([]);
    setContext(null);
  };

  const validateForSubmit = async () => {
    await validateAssetReferences(activeSources);
    if (context?.id !== null && context?.id !== undefined) {
      await projectApi.get(context.id);
    }
  };

  return {
    open: pickerTarget !== null,
    openPicker: setPickerTarget,
    onPickerOpenChange: (open: boolean) => {
      if (!open) {
        setPickerTarget(null);
      }
    },
    targetLabel: fieldConfig?.label,
    context, busy, error, selectionIssue, confirm, mediaTypes, clear, validateForSubmit,
    retryImport: () => setRetry((value) => value + 1),
    dismissError: () => setError(""),
    projectId: context?.id ?? null,
    projectLocked: context !== null,
  };
}
