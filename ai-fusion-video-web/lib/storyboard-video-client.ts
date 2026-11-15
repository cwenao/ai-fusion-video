import { assetApi } from "./api/asset";
import { getMediaResource, mediaId, bindShotVideo } from "./api/media";
import { storyboardApi } from "./api/storyboard";
import type { VideoGenerationItem } from "./api/generation";
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
  return { shot, draft: storyboardShotSources(identity.projectId, shot, assets) };
}

export async function writeStoryboardVideo(
  target: StoryboardVideoIdentity,
  item: VideoGenerationItem,
  requestId: string,
) {
  const videoId = mediaId(item.videoUrl);
  if (item.status !== 1 || !videoId) {
    throw new Error("请选择已生成并入库的成片");
  }
  const posterId = mediaId(item.coverUrl);
  const assertMedia = async (id: string, kind: "VIDEO" | "IMAGE") => {
    const media = await getMediaResource(`media://${id}`);
    if (media.projectId !== target.projectId || media.state !== "READY" || media.content.kind !== kind) {
      throw new Error("成片或封面不属于当前项目，或媒体尚未就绪");
    }
  };
  await Promise.all([assertMedia(videoId, "VIDEO"), ...(posterId ? [assertMedia(posterId, "IMAGE")] : [])]);
  const receipt = await bindShotVideo(target.shotId, target.shotVersion, videoId, posterId, requestId);
  return receipt.result;
}

export async function validateStoryboardImages(references: string[]) {
  await Promise.all(references.filter((reference) => mediaId(reference)).map(async (reference) => {
    const media = await getMediaResource(reference);
    if (media.state !== "READY" || media.content.kind.toLowerCase() !== "image") {
      throw new Error("镜头参考图未就绪或类型不匹配，请检查分镜");
    }
  }));
}
