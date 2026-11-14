"use client";

import { ImageModelFields, useImageModelOptions } from "@/components/dashboard/generation/image-model-fields";

export interface FrameImageOptions {
  frameImageModelId: number;
  frameImageAspectRatio?: string;
  frameImageResolution?: string;
}

export function useFrameImageOptions(open: boolean) {
  const choice = useImageModelOptions(open);
  const output = choice.options;
  const options: FrameImageOptions | null = output && {
    frameImageModelId: output.modelId,
    frameImageAspectRatio: output.aspectRatio,
    frameImageResolution: output.resolution,
  };
  return { ...choice, options };
}

export const FrameModelFields = ImageModelFields;
