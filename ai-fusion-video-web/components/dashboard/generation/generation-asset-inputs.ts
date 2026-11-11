import type { AssetReference, AssetReferenceMediaType } from "@/lib/asset-references";
import type { GenerationCapabilities, ReferenceImageUploadAvailability } from "@/lib/generation-capabilities";
import { videoInputState, videoRatioOptions } from "@/lib/video-model-rules";
import type { GenerationFormState, WorkbenchMode } from "./generation-types";
import { parseLimit } from "./generation-utils";

export const GENERATION_ASSET_REFERENCE_FIELDS = {
  firstFrame: { label: "首帧图", mediaType: "image", support: "supportsFirstFrame", maxCount: () => 1 },
  lastFrame: { label: "尾帧图", mediaType: "image", support: "supportsLastFrame", maxCount: () => 1 },
  referenceImages: {
    label: "参考图片",
    mediaType: "image",
    support: "supportsReferenceImages",
    maxCount: (capabilities: GenerationCapabilities) => parseLimit(capabilities.maxReferenceImages, 1),
  },
  referenceVideos: {
    label: "参考视频",
    mediaType: "video",
    support: "supportsReferenceVideos",
    maxCount: (capabilities: GenerationCapabilities) => parseLimit(capabilities.maxReferenceVideos, 3),
  },
  referenceAudios: {
    label: "参考音频",
    mediaType: "audio",
    support: "supportsReferenceAudios",
    maxCount: (capabilities: GenerationCapabilities) => parseLimit(capabilities.maxReferenceAudios, 3),
  },
} as const;

export type GenerationAssetReferenceField = keyof typeof GENERATION_ASSET_REFERENCE_FIELDS;

export function generationReferenceUrls(form: GenerationFormState): string[] {
  return [...form.firstFrame, ...form.lastFrame, ...form.referenceImages, ...form.referenceVideos, ...form.referenceAudios];
}

function automaticReferenceField(
  form: GenerationFormState,
  capabilities: GenerationCapabilities,
  mode: WorkbenchMode,
  mediaType: AssetReferenceMediaType,
): GenerationAssetReferenceField {
  if (mediaType === "video") {
    return "referenceVideos";
  }
  if (mediaType === "audio") {
    return "referenceAudios";
  }
  const framesInUse = form.firstFrame.length + form.lastFrame.length > 0;
  if (capabilities.supportsReferenceImages && !(capabilities.exclusiveInputModes && framesInUse)) {
    return "referenceImages";
  }
  if (mode === "video" && capabilities.supportsFirstFrame && !form.firstFrame.length) {
    return "firstFrame";
  }
  if (mode === "video" && capabilities.supportsLastFrame && !form.lastFrame.length) {
    return "lastFrame";
  }
  throw new Error("当前模型没有可用的图片输入位置");
}

/** Apply the ordered selection atomically: never trim, replace existing references or convert a video to its poster. */
export function appendAssetReferences(
  form: GenerationFormState,
  capabilities: GenerationCapabilities | null,
  mode: WorkbenchMode,
  selection: AssetReference[],
  imageAvailability?: ReferenceImageUploadAvailability,
  target?: GenerationAssetReferenceField,
): GenerationFormState {
  if (!capabilities) {
    throw new Error("请先选择生成模型");
  }
  if (capabilities.unavailable) {
    throw new Error(capabilities.unavailable);
  }
  const next = { ...form, firstFrame: [...form.firstFrame], lastFrame: [...form.lastFrame], referenceImages: [...form.referenceImages], referenceVideos: [...form.referenceVideos], referenceAudios: [...form.referenceAudios] };
  const used = new Set(generationReferenceUrls(form));
  const projectId = selection[0]?.projectId;
  for (const reference of selection) {
    if (reference.projectId !== projectId) {
      throw new Error("一次只能选择同一项目的资产");
    }
    if (used.has(reference.reference)) {
      throw new Error(`“${reference.name}”已在参考栏中，请勿重复添加`);
    }
    used.add(reference.reference);
    if (reference.mediaType === "image" && imageAvailability && !imageAvailability.supported &&
        !(capabilities.supportsReferenceImageUrlInput && /^https?:\/\//.test(reference.reference))) {
      throw new Error(imageAvailability.reason);
    }
    const field = target ?? automaticReferenceField(next, capabilities, mode, reference.mediaType);
    const { label, mediaType, support, maxCount } = GENERATION_ASSET_REFERENCE_FIELDS[field];
    if (reference.mediaType !== mediaType) {
      throw new Error(`“${reference.name}”的素材类型与${label}不匹配`);
    }
    if (!capabilities[support] || (field !== "referenceImages" && mode !== "video")) {
      throw new Error(`当前模型不支持${label}，请选择支持该类型的模型`);
    }
    if (next[field].length >= maxCount(capabilities)) {
      throw new Error(`${label}数量已达上限，请先移除部分参考`);
    }
    next[field].push(reference.reference);
  }
  if (mode === "video") {
    const input = videoInputState(next);
    const imageCount = next.firstFrame.length + next.lastFrame.length + next.referenceImages.length;
    if (capabilities.maxImageInputs > 0 && imageCount > capabilities.maxImageInputs) {
      throw new Error(`图片输入总数不能超过 ${capabilities.maxImageInputs} 张`);
    }
    if (capabilities.exclusiveInputModes && (input.firstFrame || input.lastFrame) && input.references) {
      throw new Error("首尾帧与参考素材不能混用");
    }
    if (capabilities.framesWithReferenceVideos === false && (input.firstFrame || input.lastFrame) && input.referenceVideos > 0) {
      throw new Error("当前模型的首尾帧不能与参考视频混用");
    }
    const total = generationReferenceUrls(next).length;
    if (capabilities.videoRules?.maxReferenceInputs && total > capabilities.videoRules.maxReferenceInputs) {
      throw new Error(`参考素材总数不能超过 ${capabilities.videoRules.maxReferenceInputs} 项`);
    }
    const ratios = videoRatioOptions(capabilities.supportedAspectRatios, capabilities.videoRules, input);
    if (ratios.length && !ratios.includes(next.ratio)) {
      next.ratio = ratios[0];
    }
  }
  return next;
}
