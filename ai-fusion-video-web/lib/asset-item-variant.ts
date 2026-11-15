export interface AssetItemVariant {
  timeline: string;
  appearanceState: string;
}

export const MAX_ASSET_VARIANT_LABEL_LENGTH = 80;

export function getAssetItemVariant(properties: Record<string, unknown> | null): AssetItemVariant {
  return {
    timeline: typeof properties?.timeline === "string" ? properties.timeline : "",
    appearanceState: typeof properties?.appearanceState === "string" ? properties.appearanceState : "",
  };
}

export function withAssetItemVariant(
  current: Record<string, unknown> | null,
  variant: AssetItemVariant,
): Record<string, unknown> {
  const result = { ...current };
  for (const field of ["timeline", "appearanceState"] as const) {
    const text = variant[field].trim();
    if (text) {
      result[field] = text;
    } else {
      delete result[field];
    }
  }
  return result;
}

export function assetItemVariantLabel(properties: Record<string, unknown> | null): string {
  const { timeline, appearanceState } = getAssetItemVariant(properties);
  return [timeline, appearanceState].filter(Boolean).join(" · ");
}
