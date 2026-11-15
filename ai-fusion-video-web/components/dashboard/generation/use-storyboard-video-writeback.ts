"use client";

import { useRef, useState } from "react";
import { toast } from "sonner";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { useCommandRequestKey } from "@/hooks/use-command-request-key";
import { getApiErrorMessage, getApiErrorStatus } from "@/lib/api/api-error";
import type { VideoGenerationItem } from "@/lib/api/generation";
import { writeStoryboardVideo } from "@/lib/storyboard-video-client";
import type { useGenerationAssetReferences } from "./use-generation-asset-references";

export function useStoryboardVideoWriteback(assets: ReturnType<typeof useGenerationAssetReferences>) {
  const { confirm } = useConfirm();
  const requests = useCommandRequestKey();
  const lock = useRef(false);
  const [pendingItemId, setPendingItemId] = useState<number | null>(null);
  const projectId = assets.projectId;
  const shot = assets.context?.shot;

  if (projectId === null || !shot || assets.storyboardImport) {
    return undefined;
  }
  const label = shot.shotNumber || shot.autoShotNumber || String(shot.id);
  const target = { projectId, shotId: shot.id, shotVersion: shot.version };

  return {
    projectId,
    label,
    currentVideo: shot.generatedVideoUrl,
    pendingItemId,
    select: async (item: VideoGenerationItem) => {
      if (lock.current || item.videoUrl === shot.generatedVideoUrl) {
        return;
      }
      lock.current = true;
      setPendingItemId(item.id);
      try {
        if (shot.generatedVideoUrl && !await confirm({
          title: `替换镜头 ${label} 的成片？`,
          description: "将使用所选视频替换该镜头当前的生成视频。",
          confirmText: "替换成片",
        })) {
          return;
        }
        const requestId = requests.forPayload({ target, itemId: item.id, itemVersion: item.version });
        const saved = await writeStoryboardVideo(target, item, requestId);
        requests.clear();
        assets.updateShot(saved);
        toast.success(`已回填到镜头 ${label}`);
      } catch (error) {
        toast.error(getApiErrorStatus(error) === 409
          ? "镜头已更新，请从分镜重新打开视频工具"
          : getApiErrorMessage(error));
      } finally {
        lock.current = false;
        setPendingItemId(null);
      }
    },
  };
}

export type StoryboardVideoWriteback = NonNullable<ReturnType<typeof useStoryboardVideoWriteback>>;
