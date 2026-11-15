import type { StoryboardItem } from "./api/storyboard";
import type { AssetWithItems } from "./api/asset";
import { assetReferences, type AssetReference, type AssetReferenceMediaType } from "./asset-references";

export interface StoryboardVideoIdentity {
  projectId: number;
  shotId: number;
  shotVersion: number;
}

export const STORYBOARD_VIDEO_QUERY_KEYS = ["shotId", "shotVersion", "projectId"] as const;

export function storyboardVideoHref(identity: StoryboardVideoIdentity): string {
  return `/generate/video?${new URLSearchParams({
    projectId: String(identity.projectId),
    shotId: String(identity.shotId),
    shotVersion: String(identity.shotVersion),
  })}`;
}

export function parseStoryboardVideoIdentity(params: URLSearchParams): StoryboardVideoIdentity | null {
  if (!params.has("shotId") && !params.has("shotVersion")) {
    return null;
  }
  const integer = (key: keyof StoryboardVideoIdentity, version = false) => {
    const value = params.get(key);
    if (value === null || !(version ? /^(0|[1-9]\d*)$/ : /^[1-9]\d*$/).test(value) || !Number.isSafeInteger(Number(value))) {
      throw new Error("镜头参数无效，请从分镜重新打开视频工具");
    }
    return Number(value);
  };
  return { projectId: integer("projectId"), shotId: integer("shotId"), shotVersion: integer("shotVersion", true) };
}

export function storyboardShotSources(projectId: number, shot: StoryboardItem, assets: AssetWithItems[]) {
  const itemIds = new Set([
    ...(shot.characterIds ?? []),
    ...(shot.sceneAssetItemId === null ? [] : [shot.sceneAssetItemId]),
    ...(shot.propIds ?? []),
  ]);
  const byId = new Map<number, AssetReference[]>();
  for (const asset of assets) {
    if (asset.projectId !== projectId) {
      continue;
    }
    const items = asset.items.filter((item) => item.assetId === asset.id && itemIds.has(item.id));
    if (!items.length) {
      continue;
    }
    const references = assetReferences({ ...asset, items });
    const masterMedia = references.filter((ref) => ref.itemId === null && ref.mediaType !== "image");
    for (const item of items) {
      byId.set(item.id, [...references.filter((ref) => ref.itemId === item.id), ...masterMedia]);
    }
  }
  if ([...itemIds].some((id) => !byId.has(id))) {
    throw new Error("部分关联资产已移除或无法读取，请检查镜头的关联资产");
  }

  const unique = (values: Array<string | null>) => [...new Set(values.map((value) => value?.trim()).filter((value): value is string => !!value))];
  const firstFrame = unique([shot.firstFrameImageUrl]);
  const lastFrame = unique([shot.lastFrameImageUrl]);
  const shotImages = unique([shot.referenceImageUrl, shot.imageUrl]).filter((url) => !firstFrame.includes(url) && !lastFrame.includes(url));
  const seen: Record<AssetReferenceMediaType, Set<string>> = {
    image: new Set([...firstFrame, ...lastFrame, ...shotImages]),
    video: new Set(),
    audio: new Set(),
  };
  const sources: AssetReference[] = [];
  for (const id of itemIds) {
    for (const source of byId.get(id)!) {
      if (!seen[source.mediaType].has(source.reference)) {
        seen[source.mediaType].add(source.reference);
        sources.push(source);
      }
    }
  }
  return {
    prompt: shot.videoPrompt ?? "",
    firstFrame,
    lastFrame,
    referenceImages: [...shotImages, ...sources.filter((source) => source.mediaType === "image").map((source) => source.reference)],
    referenceVideos: sources.filter((source) => source.mediaType === "video").map((source) => source.reference),
    referenceAudios: sources.filter((source) => source.mediaType === "audio").map((source) => source.reference),
    sources,
  };
}

export function storyboardImageReferences(draft: ReturnType<typeof storyboardShotSources>): string[] {
  const assetImages = new Set(draft.sources.filter((source) => source.mediaType === "image").map((source) => source.reference));
  return [...new Set([...draft.firstFrame, ...draft.lastFrame, ...draft.referenceImages])]
    .filter((reference) => !assetImages.has(reference));
}
