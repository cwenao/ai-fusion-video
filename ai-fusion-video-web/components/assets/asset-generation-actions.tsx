"use client";

import { useId } from "react";
import { useRouter } from "next/navigation";
import { ImagePlus, Video } from "lucide-react";
import type { AssetWithItems } from "@/lib/api/asset";
import { assetReferenceHref, assetReferences } from "@/lib/asset-references";
import { Button } from "@/components/ui/button";

interface AssetGenerationActionsProps {
  asset: AssetWithItems;
  itemId: number;
  blockedReason?: string | null;
}

export function AssetGenerationActions({ asset, itemId, blockedReason }: AssetGenerationActionsProps) {
  const router = useRouter();
  const issueId = useId();
  const item = asset.items.find((candidate) => candidate.id === itemId);
  const references = assetReferences(asset).filter((reference) => reference.itemId === itemId);
  const mode = item?.properties?.sourceGenerationMode;
  const videoItem = mode === "video" || item?.itemType === "generated_video";
  const audioItem = mode === "audio" || item?.itemType === "generated_audio";
  const image = videoItem || audioItem ? undefined : references.find((reference) => reference.mediaType === "image");
  const videoSource = references.find((reference) => reference.mediaType === "video");
  const audioSource = references.find((reference) => reference.mediaType === "audio");
  const videoTarget = videoSource ?? (videoItem ? undefined : image ?? audioSource);
  const missingIssue = "当前子资产缺少原始素材，暂不可用于生成";
  const imageIssue = blockedReason || (videoItem
    ? "视频素材仅可用于生视频"
    : audioItem ? "音频素材仅可用于生视频" : !image ? "当前子资产没有可用图片原件" : null);
  const videoIssue = blockedReason || (!videoTarget ? missingIssue : null);
  const issue = videoIssue || imageIssue;

  return (
    <div className="flex min-w-0 flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2 p-1">
        <Button
          variant="outline"
          disabled={Boolean(imageIssue)}
          title={imageIssue || "将当前已保存子资产用作图片参考"}
          aria-describedby={imageIssue ? issueId : undefined}
          onClick={() => {
            if (image) {
              router.push(assetReferenceHref("image", image));
            }
          }}
        >
          <ImagePlus />
          用于生图
        </Button>
        <Button
          variant="outline"
          disabled={Boolean(videoIssue)}
          title={videoIssue || "将当前已保存子资产用作视频生成参考"}
          aria-describedby={videoIssue ? issueId : undefined}
          onClick={() => {
            if (videoTarget) {
              router.push(assetReferenceHref("video", videoTarget));
            }
          }}
        >
          <Video />
          用于生视频
        </Button>
      </div>
      {issue && <p id={issueId} role="status" className="text-xs text-muted-foreground">{issue}</p>}
    </div>
  );
}
