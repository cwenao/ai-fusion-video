import type { AssetItem } from "./api/asset";

const ITEM_KEY = "assetPromptItemId";
const VERSION_KEY = "assetPromptItemVersion";
export const ASSET_IMAGE_PROMPT_QUERY_KEYS = [ITEM_KEY, VERSION_KEY] as const;

export interface AssetImagePromptSource {
  itemId: number;
  itemVersion: number;
}

export function getAssetImagePrompt(item: AssetItem | null): string {
  const value = item?.properties?.imagePrompt;
  return typeof value === "string" ? value : "";
}

export function assetImagePromptHref(item: Pick<AssetItem, "id" | "version">): string {
  const params = new URLSearchParams({
    [ITEM_KEY]: String(item.id),
    [VERSION_KEY]: String(item.version),
  });
  return `/generate/image?${params}`;
}

export function parseAssetImagePromptSource(params: URLSearchParams): AssetImagePromptSource {
  const id = params.get(ITEM_KEY);
  const version = params.get(VERSION_KEY);
  if (id === null || !/^[1-9]\d*$/.test(id) || !Number.isSafeInteger(Number(id))
    || version === null || !/^(0|[1-9]\d*)$/.test(version) || !Number.isSafeInteger(Number(version))) {
    throw new Error("提示词来源无效，请从项目资产重新打开生图工具");
  }
  return { itemId: Number(id), itemVersion: Number(version) };
}
