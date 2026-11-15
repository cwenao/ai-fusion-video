import type { GenerationCapabilities, ReferenceImageUploadAvailability } from "@/lib/generation-capabilities";
import { workflowInputIssue } from "@/lib/generation-capabilities";
import { videoInputState, videoRatioOptions } from "@/lib/video-model-rules";
import { GENERATION_ASSET_REFERENCE_FIELDS, type GenerationAssetReferenceField } from "./generation-asset-inputs";
import type { GenerationFormState } from "./generation-types";

const fields = Object.keys(GENERATION_ASSET_REFERENCE_FIELDS) as GenerationAssetReferenceField[];
type VideoDraft = Pick<GenerationFormState, GenerationAssetReferenceField | "prompt">;

export function storyboardReferenceIssue(
  form: GenerationFormState,
  capabilities: GenerationCapabilities,
  imageAvailability: ReferenceImageUploadAvailability,
): string | null {
  if (capabilities.unavailable) {
    return capabilities.unavailable;
  }
  for (const field of fields) {
    const values = form[field];
    if (!values.length) {
      continue;
    }
    const config = GENERATION_ASSET_REFERENCE_FIELDS[field];
    if (!capabilities[config.support]) {
      return `当前模型不支持${config.label}`;
    }
    const max = config.maxCount(capabilities);
    if (values.length > max) {
      return `${config.label}最多允许 ${max} 项`;
    }
    if (config.mediaType === "image" && !imageAvailability.supported &&
        values.some((url) => !(capabilities.supportsReferenceImageUrlInput && /^https?:\/\//.test(url)))) {
      return imageAvailability.reason;
    }
  }
  const input = videoInputState(form);
  if (capabilities.videoRules?.lastFrameRequiresFirst && input.lastFrame && !input.firstFrame) {
    return "使用尾帧时，请同时添加首帧图片";
  }
  if (capabilities.exclusiveInputModes && (input.firstFrame || input.lastFrame) && input.references) {
    return "当前模型的首尾帧不能与参考素材混用";
  }
  if (capabilities.framesWithReferenceVideos === false && (input.firstFrame || input.lastFrame) && input.referenceVideos) {
    return "当前模型的首尾帧不能与参考视频混用";
  }
  const images = form.firstFrame.length + form.lastFrame.length + form.referenceImages.length;
  if (capabilities.referenceAudioRequiresVisualInput && input.referenceAudios && !images && !input.referenceVideos) {
    return "参考音频需要搭配图片或视频";
  }
  if (capabilities.maxImageInputs > 0 && images > capabilities.maxImageInputs) {
    return `图片输入总数不能超过 ${capabilities.maxImageInputs} 张`;
  }
  const total = images + input.referenceVideos + input.referenceAudios;
  if (capabilities.videoRules?.maxReferenceInputs && total > capabilities.videoRules.maxReferenceInputs) {
    return `参考素材总数不能超过 ${capabilities.videoRules.maxReferenceInputs} 项`;
  }
  if (images < capabilities.minImageInputs) {
    return `当前模型至少需要 ${capabilities.minImageInputs} 个图片输入`;
  }
  const counts = Object.fromEntries(fields.map((field) => [field, form[field].length]));
  return workflowInputIssue(capabilities, counts);
}

export function storyboardVideoForm(
  base: GenerationFormState,
  draft: VideoDraft,
  capabilities?: GenerationCapabilities,
): GenerationFormState {
  const result = { ...base, prompt: draft.prompt };
  for (const field of fields) {
    result[field] = draft[field];
  }
  if (capabilities) {
    const ratios = videoRatioOptions(capabilities.supportedAspectRatios, capabilities.videoRules, videoInputState(result));
    if (ratios.length && !ratios.includes(result.ratio)) {
      result.ratio = ratios[0];
    }
  }
  return result;
}
