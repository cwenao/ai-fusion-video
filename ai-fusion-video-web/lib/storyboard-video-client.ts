import { assetApi } from "./api/asset";
import { getMediaResource, mediaId } from "./api/media";
import { storyboardApi } from "./api/storyboard";
import { storyboardShotSources, type StoryboardVideoIdentity } from "./storyboard-video-source";

export async function loadStoryboardVideo(identity: StoryboardVideoIdentity) {
  const [storyboard, shot] = await Promise.all([
    storyboardApi.getByProject(identity.projectId),
    storyboardApi.getItem(identity.shotId),
  ]);
  if (!storyboard || storyboard.projectId !== identity.projectId || shot.storyboardId !== storyboard.id) {
    throw new Error("镜头不属于当前项目，请从分镜重新打开视频工具");
  }
  if (shot.version !== identity.shotVersion) {
    throw new Error("镜头已更新，请从分镜重新打开视频工具");
  }
  const hasAssets = shot.characterIds?.length || shot.sceneAssetItemId !== null || shot.propIds?.length;
  const assets = hasAssets ? await assetApi.listWithItems(identity.projectId) : [];
  return storyboardShotSources(identity.projectId, shot, assets);
}

export async function validateStoryboardImages(references: string[]) {
  await Promise.all(references.filter((reference) => mediaId(reference)).map(async (reference) => {
    const media = await getMediaResource(reference);
    if (media.state !== "READY" || media.content.kind.toLowerCase() !== "image") {
      throw new Error("镜头参考图未就绪或类型不匹配，请检查分镜");
    }
  }));
}
