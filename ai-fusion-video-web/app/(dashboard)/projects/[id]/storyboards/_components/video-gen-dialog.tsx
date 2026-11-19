"use client";

import { useMemo, useState } from "react";
import { Video, ImageIcon, Check, Film, FileText } from "lucide-react";
import { GenerationReviewFields } from "@/components/generation-review/generation-review-fields";
import { useGenerationReviewChoice } from "@/components/generation-review/use-generation-review-choice";
import { DialogScrollBody } from "@/components/shared/dialog-scroll-body";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { cn } from "@/lib/utils";
import { SafeImage } from "@/components/media/managed-image";
import type { StoryboardItem } from "@/lib/api/storyboard";

export type VideoGenerateHandler = (itemIds: number[], promptOnly: boolean, reviewModelId?: number) => void;

interface VideoGenDialogProps {
  open: boolean;
  onClose: () => void;
  /** 分镜条目列表 */
  items: StoryboardItem[];
  onConfirm: VideoGenerateHandler;
}

export function VideoGenDialog({
  open,
  onClose,
  items,
  onConfirm,
}: VideoGenDialogProps) {
  const defaultSelected = useMemo(
    () => new Set(items.map((item) => item.id)),
    [items]
  );
  const [selectedOverride, setSelectedOverride] = useState<Set<number> | null>(null);
  const selected = selectedOverride
    ? new Set([...selectedOverride].filter((id) => defaultSelected.has(id))) : defaultSelected;
  const review = useGenerationReviewChoice("video");

  if (!open) {
    return null;
  }

  const toggleItem = (id: number) => {
    setSelectedOverride((prev) => {
      const next = new Set(prev ?? selected);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  const toggleAll = () => {
    if (selected.size === items.length) {
      setSelectedOverride(new Set());
    } else {
      setSelectedOverride(new Set(items.map((item) => item.id)));
    }
  };

  const handleConfirm = (promptOnly: boolean) => {
    if (!selected.size || (!promptOnly && !review.ready)) {
      return;
    }
    onConfirm(Array.from(selected), promptOnly, promptOnly ? undefined : review.modelId);
    onClose();
  };

  /** 获取镜头的画面 URL（优先使用生成的） */
  const getImageUrl = (item: StoryboardItem) => {
    return item.firstFrameImageUrl || item.generatedImageUrl || item.imageUrl || null;
  };

  /** 景别标签 */
  const shotTypeLabels: Record<string, string> = {
    远景: "远景",
    全景: "全景",
    中景: "中景",
    近景: "近景",
    特写: "特写",
  };

  return (
    <Dialog open={open} onOpenChange={(next) => {
      if (!next) {
        onClose();
      }
    }}>
      <DialogContent className="flex max-h-[calc(100vh-2rem)] flex-col gap-0 overflow-hidden p-0 sm:max-w-lg">
        <DialogHeader className="shrink-0 border-b border-border/20 px-5 py-4 pr-14">
          <DialogTitle>{items.length === 1 ? "生成镜头视频" : "批量生成视频"}</DialogTitle>
          <DialogDescription>选择需要生成视频的分镜镜头</DialogDescription>
        </DialogHeader>

        {/* 全选 */}
        {items.length > 0 && (
          <div className="flex shrink-0 items-center justify-between border-b border-border/20 px-5 py-2">
            <button
              onClick={toggleAll}
              className="text-xs text-primary hover:text-primary/80 font-medium transition-colors"
            >
              {selected.size === items.length ? "取消全选" : "全选"}
            </button>
            <span className="text-[10px] text-muted-foreground">
              已选 {selected.size} / {items.length} 个镜头
            </span>
          </div>
        )}

        {/* 镜头列表 */}
        <DialogScrollBody className="flex flex-col gap-2 p-4">
          <GenerationReviewFields choice={review} />
          {items.length === 0 ? (
            <div className="text-center py-8">
              <Film className="h-8 w-8 text-muted-foreground/20 mx-auto mb-2" />
              <p className="text-xs text-muted-foreground">暂无分镜镜头</p>
            </div>
          ) : (
            items.map((item) => {
              const checked = selected.has(item.id);
              const hasVideo = !!(item.videoUrl || item.generatedVideoUrl);
              const hasImage = !!(item.firstFrameImageUrl || item.imageUrl || item.generatedImageUrl);
              const imgUrl = getImageUrl(item);

              return (
                <button
                  key={item.id}
                  onClick={() => toggleItem(item.id)}
                  className={cn(
                    "w-full flex items-center gap-3 px-3 py-2.5 rounded-xl transition-all text-left",
                    checked
                      ? "bg-primary/8 ring-1 ring-primary/20"
                      : "hover:bg-muted/30"
                  )}
                >
                  {/* 选择指示器 */}
                  <div
                    className={cn(
                      "h-5 w-5 rounded-md border-2 flex items-center justify-center shrink-0 transition-all",
                      checked
                        ? "bg-primary border-primary text-primary-foreground"
                        : "border-border/50"
                    )}
                  >
                    {checked && <Check className="h-3 w-3" />}
                  </div>

                  {/* 缩略图 */}
                  <div className="h-12 w-20 rounded-lg bg-muted/30 border border-border/10 overflow-hidden shrink-0 flex items-center justify-center">
                    {imgUrl ? (
                      <SafeImage
                        src={imgUrl}
                        alt={`镜头 ${item.shotNumber || item.autoShotNumber}`}
                        className="w-full h-full object-cover"
                        fallbackType="image"
                      />
                    ) : (
                      <ImageIcon className="h-4 w-4 text-muted-foreground/30" />
                    )}
                  </div>

                  {/* 信息 */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-1.5">
                      <p className="text-xs font-medium">
                        #{item.shotNumber || item.autoShotNumber || "?"}
                      </p>
                      {item.shotType && (
                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-muted/40 text-muted-foreground">
                          {shotTypeLabels[item.shotType] || item.shotType}
                        </span>
                      )}
                      {item.cameraMovement && (
                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-blue-500/10 text-blue-400">
                          {item.cameraMovement}
                        </span>
                      )}
                    </div>
                    <p className="text-[10px] text-muted-foreground truncate mt-0.5">
                      {item.content || "（无画面描述）"}
                    </p>
                    <div className="flex items-center gap-1.5 mt-0.5">
                      {hasImage && (
                        <span className="text-[10px] text-cyan-400/70">
                          有首帧
                        </span>
                      )}
                      {hasVideo && (
                        <span className="text-[10px] text-green-400/70">
                          已有视频
                        </span>
                      )}
                    </div>
                  </div>
                </button>
              );
            })
          )}
        </DialogScrollBody>

        {/* Footer */}
        <div className="flex shrink-0 flex-wrap items-center justify-end gap-2 border-t border-border/20 px-5 py-3">
          <Button type="button" variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button type="button" variant="outline"
            onClick={() => handleConfirm(true)}
            disabled={selected.size === 0}
          >
            <FileText />
            只生提示词 ({selected.size})
          </Button>
          <Button type="button" variant="video"
            onClick={() => handleConfirm(false)}
            disabled={selected.size === 0 || !review.ready}
          >
            <Video />
            生成视频 ({selected.size})
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
