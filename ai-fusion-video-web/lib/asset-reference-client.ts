import { assetApi } from "./api/asset";
import { getMediaResource, mediaId } from "./api/media";
import { resolveAssetReferenceIdentity, type AssetReference, type AssetReferenceIdentity } from "./asset-references";

export async function loadAssetReference(identity: AssetReferenceIdentity): Promise<AssetReference> {
  const [asset, items] = await Promise.all([assetApi.get(identity.assetId), assetApi.listItems(identity.assetId)]);
  return resolveAssetReferenceIdentity({ ...asset, items }, identity);
}

/** Re-read the authorized asset and the actual managed resource, retaining only stable references. */
export async function validateAssetReferences(references: AssetReference[]): Promise<AssetReference[]> {
  const assets = new Map<number, ReturnType<typeof assetApi.get>>();
  const items = new Map<number, ReturnType<typeof assetApi.listItems>>();
  for (const reference of references) {
    if (!assets.has(reference.assetId)) {
      assets.set(reference.assetId, assetApi.get(reference.assetId));
      items.set(reference.assetId, assetApi.listItems(reference.assetId));
    }
  }
  return Promise.all(references.map(async (reference) => {
    const [asset, children] = await Promise.all([assets.get(reference.assetId)!, items.get(reference.assetId)!]);
    const current = resolveAssetReferenceIdentity({ ...asset, items: children }, reference);
    if (current.reference !== reference.reference || current.mediaType !== reference.mediaType) {
      throw new Error(`“${reference.name}”的媒体已变化，请重新选择`);
    }
    if (mediaId(current.reference)) {
      const media = await getMediaResource(current.reference);
      if (media.state !== "READY" || media.content.kind.toLowerCase() !== current.mediaType) {
        throw new Error(`“${reference.name}”的媒体未就绪或类型不匹配`);
      }
      if (reference.mediaVersion !== undefined && reference.mediaVersion !== media.version) {
        throw new Error(`“${reference.name}”的媒体版本已变化，请重新选择`);
      }
      return { ...current, mediaVersion: media.version };
    }
    return current;
  }));
}
