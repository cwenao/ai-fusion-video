import type {
  ComfyUiBindingValueType,
  ComfyUiInputBindings,
} from "./api/comfyui-workflow";
import { comfyUiTestInput } from "./comfyui-test-request";

export interface ComfyUiTestField {
  name: string;
  type: ComfyUiBindingValueType;
  requiredCount: number;
  maximumCount: number;
  indices?: number[];
}

interface ComfyUiTestMediaPolicy {
  label: string;
  mimePrefix: string;
  maxMegabytes: number;
}

const mediaPolicies: Partial<Record<ComfyUiBindingValueType, ComfyUiTestMediaPolicy>> = {
  uploaded_image: { label: "图片", mimePrefix: "image/", maxMegabytes: 20 },
  uploaded_video: { label: "视频", mimePrefix: "video/", maxMegabytes: 200 },
  uploaded_audio: { label: "音频", mimePrefix: "audio/", maxMegabytes: 50 },
};

const labels: Record<string, string> = {
  prompt: "提示词",
  negativePrompt: "负面提示词",
  width: "宽度",
  height: "高度",
  aspectRatio: "宽高比",
  megapixels: "百万像素（MP）",
  resolutionMultiple: "尺寸对齐倍数",
  upscaleMegapixels: "放大百万像素（MP）",
  seed: "随机种子",
  count: "数量",
  duration: "时长（秒）",
  fps: "帧率",
  referenceImages: "参考图片",
  referenceVideos: "参考视频",
  referenceAudios: "参考音频",
  firstFrame: "首帧图片",
  lastFrame: "尾帧图片",
  generateAudio: "生成音频",
};

export function getComfyUiTestFieldLabel(name: string): string {
  return labels[name] ?? name;
}

export function getComfyUiTestMediaPolicy(type: ComfyUiBindingValueType) {
  return mediaPolicies[type];
}

export function getComfyUiTestFields(bindings: ComfyUiInputBindings): ComfyUiTestField[] {
  return Object.entries(bindings).map(([name, items]) => {
    const first = items?.[0];
    if (!Array.isArray(items) || !first
      || items.some((item) => !item || item.valueType !== first.valueType)) {
      throw new Error(`字段 ${name} 的绑定类型不一致，请先修复版本。`);
    }
    const type = first.valueType;
    if (!getComfyUiTestMediaPolicy(type)) {
      return { name, type, requiredCount: 0, maximumCount: 64 };
    }
    const indices = [...new Set(items.map((item) => item.index ?? 0))];
    // 只有非 optional 的 indexed 上传项必须提交；普通字段仍可沿用工作流保存值。
    const requiredCount = items.reduce((minimum, item) =>
      item.optional || item.index == null ? minimum : Math.max(minimum, item.index + 1), 0);
    return {
      name,
      type,
      requiredCount,
      maximumCount: Math.min(64, Math.max(...indices) + 1),
      indices,
    };
  });
}

function validateCount(field: ComfyUiTestField, count: number, requireMinimum: boolean) {
  const label = getComfyUiTestFieldLabel(field.name);
  if (requireMinimum && count < field.requiredCount) {
    throw new Error(field.requiredCount === 1
      ? `${label}：第 1 项必填。`
      : `${label}：至少填写前 ${field.requiredCount} 项。`);
  }
  const maximum = field.maximumCount;
  if (count > maximum) {
    throw new Error(`${label}最多支持 ${maximum} 项，请减少输入。`);
  }
  if (field.indices) {
    for (let index = 0; index < count; index++) {
      if (!field.indices.includes(index)) {
        throw new Error(`${label}第 ${index + 1} 项未绑定，请修复版本或减少输入。`);
      }
    }
  }
}

export function getComfyUiTestInputs(
  fields: ComfyUiTestField[],
  values: Record<string, string>,
): Record<string, unknown> {
  const inputs: Record<string, unknown> = {};
  for (const field of fields) {
    const raw = values[field.name]?.trim();
    const value = raw ? comfyUiTestInput(raw, field.type) : undefined;
    if (field.type === "string_list" || getComfyUiTestMediaPolicy(field.type)) {
      validateCount(field, (value as string[] | undefined)?.length ?? 0, true);
    }
    if (raw) {
      inputs[field.name] = value;
    }
  }
  return inputs;
}

export function validateComfyUiTestUpload(
  field: ComfyUiTestField,
  currentValue: string,
  files: ReadonlyArray<Pick<File, "type" | "size">>,
) {
  const policy = getComfyUiTestMediaPolicy(field.type);
  if (!policy) {
    throw new Error("当前字段不是上传媒体类型。");
  }
  const current = comfyUiTestInput(currentValue, field.type) as string[];
  validateCount(field, current.length + files.length, false);
  if (files.some((file) => !file.type.startsWith(policy.mimePrefix)
    || file.size > policy.maxMegabytes * 1024 * 1024)) {
    throw new Error(`请上传不超过 ${policy.maxMegabytes}MB 的${policy.label}文件。`);
  }
}
