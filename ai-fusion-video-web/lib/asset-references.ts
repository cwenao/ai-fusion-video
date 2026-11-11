import type { AssetWithItems } from "./api/asset";

export type AssetReferenceMediaType = "image" | "video" | "audio";
export type AssetReferenceField = "coverUrl" | "imageUrl" | "properties.sourceMediaUrl" | "properties.videoUrl" | "properties.audioUrl";

export interface AssetReferenceIdentity {
  assetId: number;
  assetVersion: number;
  itemId: number | null;
  itemVersion: number | null;
  projectId: number | null;
  sourceField: AssetReferenceField;
}

export interface AssetReference extends AssetReferenceIdentity {
  key: string;
  assetName: string;
  name: string;
  mediaType: AssetReferenceMediaType;
  reference: string;
  previewUrl: string | null;
  mediaVersion?: number;
}

function text(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

/** Match the backend MediaSourceFields contract. A video poster is never an original video. */
export function assetReferences(asset: AssetWithItems): AssetReference[] {
  const references: AssetReference[] = [];
  const add = (itemId: number | null, itemVersion: number | null, name: string, sourceField: AssetReferenceField, mediaType: AssetReferenceMediaType, value: unknown, previewUrl: string | null) => {
    const reference = text(value);
    if (!reference) {
      return;
    }
    if (references.some((item) => item.itemId === itemId && item.reference === reference && item.mediaType === mediaType)) {
      return;
    }
    references.push({
      key: `${asset.id}:${itemId ?? "master"}:${sourceField}`,
      assetId: asset.id,
      assetVersion: asset.version,
      itemId,
      itemVersion,
      projectId: asset.projectId,
      sourceField,
      assetName: asset.name,
      name,
      mediaType,
      reference,
      previewUrl: mediaType === "image" ? reference : previewUrl,
    });
  };
  for (const item of asset.items) {
    const mode = item.properties?.sourceGenerationMode;
    const video = mode === "video" || item.itemType === "generated_video";
    const audio = mode === "audio" || item.itemType === "generated_audio";
    const name = item.name || asset.name;
    if (mode === "video" || mode === "audio") {
      add(item.id, item.version, name, "properties.sourceMediaUrl", mode, item.properties?.sourceMediaUrl, text(item.imageUrl));
    }
    add(item.id, item.version, `${name} · 视频`, "properties.videoUrl", "video", item.properties?.videoUrl, text(item.imageUrl));
    add(item.id, item.version, `${name} · 音频`, "properties.audioUrl", "audio", item.properties?.audioUrl, null);
    if (!video && !audio) {
      add(item.id, item.version, name, "imageUrl", "image", item.imageUrl, null);
    }
  }
  const mode = asset.properties?.sourceGenerationMode;
  if (mode === "image" || mode === "video" || mode === "audio") {
    add(null, null, asset.name, "properties.sourceMediaUrl", mode, asset.properties?.sourceMediaUrl, text(asset.coverUrl));
  }
  add(null, null, `${asset.name} · 视频`, "properties.videoUrl", "video", asset.properties?.videoUrl, text(asset.coverUrl));
  add(null, null, `${asset.name} · 音频`, "properties.audioUrl", "audio", asset.properties?.audioUrl, null);
  const cover = text(asset.coverUrl);
  if (cover && !references.some((item) => item.reference === cover || item.previewUrl === cover)) {
    add(null, null, `${asset.name} · 封面图片`, "coverUrl", "image", cover, null);
  }
  return references;
}

export function assetReferenceHref(mode: "image" | "video", reference: AssetReference): string {
  const params = new URLSearchParams({
    assetId: String(reference.assetId),
    assetVersion: String(reference.assetVersion),
    sourceField: reference.sourceField,
  });
  if (reference.itemId !== null) {
    params.set("itemId", String(reference.itemId));
    params.set("itemVersion", String(reference.itemVersion));
  }
  if (reference.projectId !== null) {
    params.set("projectId", String(reference.projectId));
  }
  return `/generate/${mode}?${params}`;
}

export const ASSET_REFERENCE_QUERY_KEYS = ["assetId", "assetVersion", "itemId", "itemVersion", "projectId", "sourceField"] as const;
const SOURCE_FIELDS: AssetReferenceField[] = ["coverUrl", "imageUrl", "properties.sourceMediaUrl", "properties.videoUrl", "properties.audioUrl"];

export function parseAssetReferenceIdentity(params: Pick<URLSearchParams, "get">): AssetReferenceIdentity | null {
  if (ASSET_REFERENCE_QUERY_KEYS.every((key) => params.get(key) === null)) {
    return null;
  }
  const integer = (key: string, version = false): number => {
    const raw = params.get(key);
    const valid = version ? /^(0|[1-9]\d*)$/ : /^[1-9]\d*$/;
    if (raw === null || !valid.test(raw) || !Number.isSafeInteger(Number(raw))) {
      throw new Error("资产引用参数无效，请从资产详情重新选择");
    }
    return Number(raw);
  };
  const sourceField = params.get("sourceField") as AssetReferenceField;
  if (!SOURCE_FIELDS.includes(sourceField)) {
    throw new Error("资产媒体字段无效，请从资产详情重新选择");
  }
  const hasItem = params.get("itemId") !== null || params.get("itemVersion") !== null;
  return {
    assetId: integer("assetId"),
    assetVersion: integer("assetVersion", true),
    itemId: hasItem ? integer("itemId") : null,
    itemVersion: hasItem ? integer("itemVersion", true) : null,
    projectId: params.get("projectId") !== null ? integer("projectId") : null,
    sourceField,
  };
}

export function resolveAssetReferenceIdentity(asset: AssetWithItems, identity: AssetReferenceIdentity): AssetReference {
  if (asset.id !== identity.assetId || asset.projectId !== identity.projectId) {
    throw new Error("资产所属项目已变化，请重新选择");
  }
  if (identity.itemId !== null && !asset.items.some((item) => item.id === identity.itemId && item.assetId === asset.id)) {
    throw new Error("子资产不属于当前资产，请重新选择");
  }
  const reference = assetReferences(asset).find((item) => item.itemId === identity.itemId && item.sourceField === identity.sourceField);
  if (!reference) {
    throw new Error("资产原始媒体不可用，请重新选择");
  }
  if (asset.version !== identity.assetVersion || reference.itemVersion !== identity.itemVersion) {
    throw new Error("资产版本已变化，请重新选择");
  }
  return reference;
}
