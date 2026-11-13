"use client";

import { useEffect, useId, useState } from "react";
import { GenerationImageOutputPickers } from "@/components/dashboard/generation/generation-image-output-pickers";
import { EMPTY_GENERATION_FORM } from "@/components/dashboard/generation/generation-types";
import { generationFormForModel } from "@/components/dashboard/generation/generation-utils";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { aiModelApi, type AiModel, type ModelPreset } from "@/lib/api/ai-model";
import { getApiErrorMessage } from "@/lib/api/api-error";
import { generationCapabilityError, resolvedGenerationConfig, resolveGenerationCapabilities } from "@/lib/generation-capabilities";
import { imageRequestResolution, resolutionChoices } from "@/lib/image-output-sizes";

export interface FrameImageOptions {
  frameImageModelId: number;
  frameImageAspectRatio?: string;
  frameImageResolution?: string;
}

export function useFrameImageOptions(open: boolean) {
  const [catalog, setCatalog] = useState<{ models: AiModel[]; presets: ModelPreset[] }>();
  const [selectedId, setSelectedId] = useState<number>();
  const [output, setOutput] = useState<{ ratio: string; resolution: string }>();
  const [error, setError] = useState("");
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!open) {
      return;
    }
    let active = true;
    void Promise.all([aiModelApi.listByType(2), aiModelApi.presets(2)]).then(([models, presets]) => {
      if (active) {
        setCatalog({ models: models.filter((model) => model.status === 1), presets });
      }
    }).catch((cause) => {
      if (active) {
        setError(getApiErrorMessage(cause));
      }
    });
    return () => {
      active = false;
    };
  }, [open, attempt]);

  const models = catalog?.models ?? [];
  const model = models.find((entry) => entry.id === selectedId);
  const capabilities = model && catalog ? resolveGenerationCapabilities(model, catalog.presets) : null;
  const config = model && catalog ? resolvedGenerationConfig(model, catalog.presets) : null;
  if (capabilities && config && !config.supportedAspectRatios && !config.defaultAspectRatio
      && !config.supportedSizes && !capabilities.supportAutoSize) {
    capabilities.supportedAspectRatios = [];
  }
  const form = output ?? (capabilities ? generationFormForModel(EMPTY_GENERATION_FORM, capabilities) : EMPTY_GENERATION_FORM);
  const options: FrameImageOptions | null = model && capabilities && !capabilities.unavailable ? {
    frameImageModelId: model.id,
    ...(form.ratio ? { frameImageAspectRatio: form.ratio } : {}),
    ...(form.resolution ? { frameImageResolution: imageRequestResolution(capabilities, form.resolution, form.ratio) } : {}),
  } : null;

  return {
    models, model, form, capabilities, options, error,
    loading: !catalog && !error,
    retry: () => {
      setError("");
      setAttempt((current) => current + 1);
    },
    select: (id: number) => {
      setSelectedId(id);
      setOutput(undefined);
    },
    changeOutput: (patch: Partial<Pick<typeof form, "ratio" | "resolution">>) => {
      const next = { ratio: form.ratio, resolution: form.resolution, ...patch };
      const resolutions = resolutionChoices(capabilities, next.ratio);
      if (!resolutions.includes(next.resolution)) {
        next.resolution = resolutions[0] ?? "";
      }
      setOutput(next);
    },
  };
}

export function FrameModelFields({ choice, disabled }: {
  choice: ReturnType<typeof useFrameImageOptions>;
  disabled?: boolean;
}) {
  const modelField = useId();
  const { models, model, form, capabilities, loading, error } = choice;
  return (
    <section className="flex min-w-0 flex-col gap-3">
      <div className="flex min-w-0 flex-col gap-2">
        <label htmlFor={modelField} className="text-sm font-medium">生图模型</label>
        <Select items={models.map(({ id, name }) => ({ value: String(id), label: name }))}
          value={model ? String(model.id) : null}
          disabled={disabled || loading || models.length === 0}
          onValueChange={(value) => {
            if (value !== null) {
              choice.select(Number(value));
            }
          }}>
          <SelectTrigger id={modelField} className="w-full">
            <SelectValue placeholder={loading ? "正在加载模型…" : "选择生图模型"} />
          </SelectTrigger>
          <SelectContent>
            <SelectGroup>
              {models.map((entry) => (
                <SelectItem key={entry.id} value={String(entry.id)} disabled={!!generationCapabilityError(entry)}>
                  {entry.name}{generationCapabilityError(entry) ? "（不可用）" : ""}
                </SelectItem>
              ))}
            </SelectGroup>
          </SelectContent>
        </Select>
        {error && <div className="flex items-center justify-between gap-2">
          <p role="alert" className="text-sm text-destructive">{error}</p>
          <Button variant="outline" onClick={choice.retry}>重试</Button>
        </div>}
        {!loading && !error && !models.some((entry) => !generationCapabilityError(entry)) && (
          <p role="status" className="text-sm text-muted-foreground">暂无可用生图模型，请在模型设置中启用图片模型。</p>
        )}
      </div>
      {capabilities && !capabilities.unavailable && (
        <>
          <div className="grid min-w-0 grid-cols-2 gap-3">
            <GenerationImageOutputPickers capabilities={capabilities} form={form} onChange={choice.changeOutput} disabled={disabled} hideUnavailable />
          </div>
          {capabilities.supportedResolutions.length === 0 && (
            <p className="text-xs text-muted-foreground">
              {capabilities.defaultWidth && capabilities.defaultHeight
                ? `模型默认尺寸 ${capabilities.defaultWidth} × ${capabilities.defaultHeight}`
                : "尺寸由模型配置决定"}
            </p>
          )}
        </>
      )}
    </section>
  );
}
