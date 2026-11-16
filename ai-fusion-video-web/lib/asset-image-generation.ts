import type { ImageModelOptions } from "@/components/dashboard/generation/image-model-fields";
import { usePipelineStore } from "@/lib/store/pipeline-store";

interface AssetImageGenerationRequest {
  projectId: number;
  assetIds: number[];
  itemIds: number[];
  image: ImageModelOptions;
  label: string;
  reviewModelId?: number;
  storyboardSceneId?: number;
  onComplete?: () => void;
}

export function enqueueAssetImageGeneration({
  projectId,
  assetIds,
  itemIds,
  image,
  label,
  reviewModelId,
  storyboardSceneId,
  onComplete,
}: AssetImageGenerationRequest) {
  const { addPipeline, setNotificationOpen } = usePipelineStore.getState();
  addPipeline({
    label,
    projectId,
    request: {
      agentType: "asset_image_gen",
      toolExecutionMode: "FULL_ACCESS",
      projectId,
      generationReviewModelId: reviewModelId,
      context: {
        selectedAssetIds: assetIds,
        selectedAssetItemIds: itemIds,
        assetImageModelId: image.modelId,
        assetImageAspectRatio: image.aspectRatio,
        assetImageResolution: image.resolution,
        storyboardSceneId,
      },
    },
    onComplete,
  });
  setNotificationOpen(true);
}
