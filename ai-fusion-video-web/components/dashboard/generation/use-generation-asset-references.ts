"use client";

import { useEffect, useRef, useState, type Dispatch, type SetStateAction } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { toast } from "sonner";
import type { AiModel, ModelPreset } from "@/lib/api/ai-model";
import { getApiErrorMessage } from "@/lib/api/api-error";
import { projectApi } from "@/lib/api/project";
import type { StoryboardItem } from "@/lib/api/storyboard";
import { loadAssetImagePrompt, loadAssetReference, validateAssetReferences } from "@/lib/asset-reference-client";
import { ASSET_IMAGE_PROMPT_QUERY_KEYS, parseAssetImagePromptSource } from "@/lib/asset-image-prompt-source";
import { ASSET_REFERENCE_QUERY_KEYS, parseAssetReferenceIdentity, type AssetReference, type AssetReferenceMediaType } from "@/lib/asset-references";
import { loadStoryboardVideo, validateStoryboardImages } from "@/lib/storyboard-video-client";
import { STORYBOARD_VIDEO_QUERY_KEYS, parseStoryboardVideoIdentity, storyboardImageReferences } from "@/lib/storyboard-video-source";
import { resolveGenerationCapabilities, resolveReferenceImageUploadAvailability, type GenerationCapabilities, type ReferenceImageUploadAvailability } from "@/lib/generation-capabilities";
import {
  appendAssetReferences,
  GENERATION_ASSET_REFERENCE_FIELDS,
  generationReferenceUrls,
  type GenerationAssetReferenceField,
} from "./generation-asset-inputs";
import { generationFormForModel } from "./generation-utils";
import { storyboardReferenceIssue, storyboardVideoForm } from "./generation-storyboard-inputs";
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
  shot?: StoryboardItem;
  shotImages?: string[];
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
  const promptImport = options.mode === "image" && ASSET_IMAGE_PROMPT_QUERY_KEYS.some((key) => searchParams.has(key));
  const waitingForMedia = !promptImport && (options.loadingModels || !options.imageAvailability.runtimeConfigLoaded);
  const storyboardQuery = options.mode === "video" && (searchParams.has("shotId") || searchParams.has("shotVersion"));
  const importedStoryboard = useRef<string | null>(null);
  const storyboardImport = storyboardQuery && importedStoryboard.current !== query;
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
    setContext(context ?? nextContext);
    setPickerTarget(null);
    setError("");
    toast.success(`已加入 ${validated.length} 项资产参考`);
  };

  function removeImportKeys(keys: readonly string[]) {
    const params = new URLSearchParams(query);
    for (const key of keys) {
      params.delete(key);
    }
    router.replace(`${pathname}${params.size ? `?${params}` : ""}`, { scroll: false });
  }

  useEffect(() => {
    if (waitingForMedia) {
      return;
    }
    if (storyboardQuery && importedStoryboard.current === query) {
      setBusy(false);
      return;
    }
    let cancelled = false;
    let identity;
    let promptSource;
    let shot;
    try {
      const params = new URLSearchParams(query);
      promptSource = promptImport ? parseAssetImagePromptSource(params) : null;
      shot = storyboardQuery ? parseStoryboardVideoIdentity(params) : null;
      identity = shot || promptSource ? null : parseAssetReferenceIdentity(params);
    } catch (cause) {
      setError(getApiErrorMessage(cause));
      return;
    }
    if (!identity && !shot && !promptSource) {
      setBusy(false);
      return;
    }
    setBusy(true);
    setError("");
    const snapshot = latest.current;
    void (async () => {
      if (promptSource) {
        const { prompt, projectId } = await loadAssetImagePrompt(promptSource);
        const nextContext = await projectContext(projectId);
        if (cancelled) {
          return;
        }
        if (latest.current.form.prompt.trim() || generationReferenceUrls(latest.current.form).length
          || (context && context.id !== projectId)) {
          throw new Error("请清空当前提示词和参考素材后重试带入");
        }
        snapshot.setForm((current) => ({ ...current, prompt }));
        setContext(nextContext);
        removeImportKeys(ASSET_IMAGE_PROMPT_QUERY_KEYS);
        toast.success("已带入子资产生图提示词");
        return;
      }
      const assertUnchanged = () => {
        if (latest.current.form !== snapshot.form || latest.current.modelId !== snapshot.modelId) {
          throw new Error("模型或表单已变化，请重新带入");
        }
      };
      const candidates = [
        ...snapshot.models.filter((model) => model.id === snapshot.modelId),
        ...snapshot.models.filter((model) => model.id !== snapshot.modelId),
      ];
      if (shot) {
        const [{ shot: loadedShot, draft }, nextContext] = await Promise.all([loadStoryboardVideo(shot), projectContext(shot.projectId)]);
        const shotImages = storyboardImageReferences(draft);
        const [validated] = await Promise.all([validateAssetReferences(draft.sources), validateStoryboardImages(shotImages)]);
        if (cancelled) {
          return;
        }
        assertUnchanged();
        if (snapshot.form.prompt.trim() || generationReferenceUrls(snapshot.form).length) {
          throw new Error("请清空视频工具中的提示词和参考素材后重试带入");
        }
        const mapped = candidates.map((model) => {
          const capabilities = resolveGenerationCapabilities(model, snapshot.presets);
          const availability = resolveReferenceImageUploadAvailability(capabilities, snapshot.imageAvailability.publicMediaAccessConfigured, snapshot.imageAvailability.runtimeConfigLoaded);
          const form = storyboardVideoForm(generationFormForModel(snapshot.form, capabilities), draft, capabilities);
          return { model, form, issue: storyboardReferenceIssue(form, capabilities, availability) };
        });
        const compatible = mapped.find((model) => !model.issue);
        const selected = compatible ?? mapped.find((model) => model.model.id === snapshot.modelId);
        snapshot.setModelId(selected?.model.id);
        snapshot.setForm(selected?.form ?? storyboardVideoForm(snapshot.form, draft));
        setSources(validated);
        importedStoryboard.current = query;
        setContext({ ...nextContext, shot: loadedShot, shotImages });
        toast.success("已带入镜头提示词和参考素材");
        return;
      }
      if (!identity) {
        return;
      }
      const source = await loadAssetReference(identity);
      const [validated, nextContext] = await Promise.all([validateAssetReferences([source]), projectContext(source.projectId)]);
      if (cancelled) {
        return;
      }
      assertUnchanged();
      if (context && source.projectId !== context.id) {
        throw new Error("请先清空项目参考，再导入其他项目资产");
      }
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
      removeImportKeys(ASSET_REFERENCE_QUERY_KEYS);
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
  }, [query, retry, promptImport, storyboardQuery, waitingForMedia, pathname, router]);

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
    if (storyboardQuery) {
      importedStoryboard.current = null;
      removeImportKeys(STORYBOARD_VIDEO_QUERY_KEYS);
    }
  };

  const updateShot = (shot: StoryboardItem) => {
    setContext((current) => current?.shot?.id === shot.id ? { ...current, shot } : current);
    const params = new URLSearchParams(query);
    params.set("shotVersion", String(shot.version));
    importedStoryboard.current = params.toString();
    router.replace(`${pathname}?${params}`, { scroll: false });
  };

  const validateForSubmit = async () => {
    await Promise.all([
      validateAssetReferences(activeSources),
      validateStoryboardImages(context?.shotImages?.filter((url) => activeUrls.has(url)) ?? []),
    ]);
    if (context?.id !== null && context?.id !== undefined) {
      await projectApi.get(context.id);
    }
  };

  const isStoryboard = context?.shot !== undefined;
  const referenceIssue = isStoryboard && activeUrls.size > 0
    ? options.capabilities
      ? storyboardReferenceIssue(options.form, options.capabilities, options.imageAvailability)
      : "请选择支持当前参考素材的模型"
    : null;

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
    promptImport, hasReferences: activeUrls.size > 0,
    retryImport: () => setRetry((value) => value + 1),
    dismissError: () => {
      setError("");
      if (promptImport) {
        removeImportKeys(ASSET_IMAGE_PROMPT_QUERY_KEYS);
      } else if (storyboardQuery) {
        removeImportKeys(STORYBOARD_VIDEO_QUERY_KEYS);
      }
    },
    isStoryboard, referenceIssue, storyboardImport,
    updateShot,
    projectId: context?.id ?? null,
    projectLocked: context !== null,
  };
}
