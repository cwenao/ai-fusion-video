"use client";

import { useMemo, useState } from "react";
import { Sparkles, ImageIcon, Check } from "lucide-react";
import { cn } from "@/lib/utils";
import { getAssetImagePrompt } from "@/lib/asset-image-prompt-source";
import { resolveMediaUrl } from "@/lib/api/client";
import type { AssetItem } from "@/lib/api/asset";
import { SafeImage } from "@/components/media/managed-image";
import { ImageModelFields, useImageModelOptions, type ImageModelOptions } from "@/components/dashboard/generation/image-model-fields";
import { GenerationReviewFields } from "@/components/generation-review/generation-review-fields";
import { useGenerationReviewChoice } from "@/components/generation-review/use-generation-review-choice";
import { DialogScrollBody } from "@/components/shared/dialog-scroll-body";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";

/** 带主资产信息的子资产 */
export interface AssetItemWithInfo {
  item: AssetItem;
  parentName: string;
  parentType: string;
  assetId: number;
}

/** 选中的子资产信息 */
export interface SelectedAssetItem {
  assetId: number;
  itemId: number;
}

interface BatchGenDialogProps {
  open: boolean;
  onClose: () => void;
  /** 子资产列表（带主资产信息） */
  assetItems: AssetItemWithInfo[];
  onConfirm: (selected: SelectedAssetItem[], image: ImageModelOptions, reviewModelId?: number) => void;
}

const typeLabels: Record<string, string> = {
  character: "角色",
  scene: "场景",
  prop: "道具",
  costume: "服装",
  vehicle: "载具",
};

const typeColors: Record<string, { bg: string; text: string }> = {
  character: { bg: "bg-blue-500/10", text: "text-blue-400" },
  scene: { bg: "bg-green-500/10", text: "text-green-400" },
  prop: { bg: "bg-amber-500/10", text: "text-amber-400" },
  costume: { bg: "bg-purple-500/10", text: "text-purple-400" },
  vehicle: { bg: "bg-teal-500/10", text: "text-teal-400" },
};

export function BatchGenDialog({
  open,
  onClose,
  assetItems,
  onConfirm,
}: BatchGenDialogProps) {
  const defaultSelected = useMemo(
    () => new Set(assetItems.map((asset) => asset.item.id)),
    [assetItems]
  );
  const [selectedOverride, setSelectedOverride] = useState<Set<number> | null>(null);
  const selected = selectedOverride
    ? new Set([...selectedOverride].filter((id) => defaultSelected.has(id))) : defaultSelected;
  const missingPromptCount = assetItems.filter(({ item }) =>
    selected.has(item.id) && !getAssetImagePrompt(item).trim()).length;
  const review = useGenerationReviewChoice("image");
  const image = useImageModelOptions(open, true);

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
    if (selected.size === assetItems.length) {
      setSelectedOverride(new Set());
    } else {
      setSelectedOverride(new Set(assetItems.map((a) => a.item.id)));
    }
  };

  const handleConfirm = () => {
    if (!selected.size || missingPromptCount > 0 || !image.options || !review.ready) {
      return;
    }
    const result: SelectedAssetItem[] = assetItems
      .filter((a) => selected.has(a.item.id))
      .map((a) => ({ assetId: a.assetId, itemId: a.item.id }));
    onConfirm(result, image.options, review.modelId);
    onClose();
  };

  // 按主资产分组展示子资产
  const grouped = new Map<number, { name: string; type: string; items: AssetItemWithInfo[] }>();
  for (const ai of assetItems) {
    const existing = grouped.get(ai.assetId);
    if (existing) {
      existing.items.push(ai);
    } else {
      grouped.set(ai.assetId, { name: ai.parentName, type: ai.parentType, items: [ai] });
    }
  }

  return (
    <Dialog open={open} onOpenChange={(next) => {
      if (!next) {
        onClose();
      }
    }}>
      <DialogContent className="flex max-h-[calc(100vh-2rem)] flex-col gap-0 overflow-hidden p-0 sm:max-w-lg">
        <DialogHeader className="shrink-0 border-b border-border/20 px-5 py-4 pr-14">
          <DialogTitle>批量生图</DialogTitle>
          <DialogDescription>选择需要生成图片的子资产</DialogDescription>
        </DialogHeader>

        {/* 全选 */}
        {assetItems.length > 0 && (
          <div className="flex shrink-0 items-center justify-between border-b border-border/20 px-5 py-2">
            <button
              onClick={toggleAll}
              className="text-xs text-primary hover:text-primary/80 font-medium transition-colors"
            >
              {selected.size === assetItems.length ? "取消全选" : "全选"}
            </button>
            <span className="text-[10px] text-muted-foreground">
              已选 {selected.size} / {assetItems.length}
            </span>
          </div>
        )}

        {/* 子资产列表（按主资产分组） */}
        <DialogScrollBody className="flex flex-col gap-3 p-4">
          {missingPromptCount > 0 && (
            <p role="status" className="text-sm text-muted-foreground">
              已选子资产中有 {missingPromptCount} 个缺少生图提示词，请到资产管理保存提示词或取消选中。
            </p>
          )}
          <ImageModelFields choice={image} />
          <GenerationReviewFields choice={review} />
          {assetItems.length === 0 ? (
            <div className="text-center py-8">
              <ImageIcon className="h-8 w-8 text-muted-foreground/20 mx-auto mb-2" />
              <p className="text-xs text-muted-foreground">暂无子资产</p>
            </div>
          ) : (
            Array.from(grouped.entries()).map(([assetId, group]) => {
              const colors = typeColors[group.type] || typeColors.prop;
              return (
                <div key={assetId} className="space-y-1">
                  {/* 主资产标题 */}
                  <div className="flex items-center gap-2 px-2 py-1">
                    <span
                      className={cn(
                        "text-[10px] px-1.5 py-0.5 rounded font-medium",
                        colors.bg,
                        colors.text
                      )}
                    >
                      {typeLabels[group.type] || group.type}
                    </span>
                    <span className="text-xs font-medium text-muted-foreground">
                      {group.name}
                    </span>
                    <span className="text-[10px] text-muted-foreground/50 ml-auto">
                      {group.items.length} 个子资产
                    </span>
                  </div>

                  {/* 该主资产下的子资产 */}
                  {group.items.map((ai) => {
                    const checked = selected.has(ai.item.id);
                    return (
                      <button
                        key={ai.item.id}
                        onClick={() => toggleItem(ai.item.id)}
                        className={cn(
                          "w-full flex items-center gap-3 px-3 py-2.5 rounded-xl transition-all text-left ml-1",
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
                        <SafeImage
                          src={resolveMediaUrl(ai.item.imageUrl) || undefined}
                          alt={ai.item.name || ai.parentName}
                          className="h-10 w-10 rounded-lg object-cover"
                          fallbackType={
                            ai.parentType === "character"
                              ? "avatar"
                              : ai.parentType === "scene"
                              ? "scene"
                              : ai.parentType === "prop"
                              ? "prop"
                              : "image"
                          }
                        />

                        {/* 信息 */}
                        <div className="flex-1 min-w-0">
                          <p className="text-xs font-medium truncate">
                            {ai.item.name || ai.parentName}
                          </p>
                          <div className="flex items-center gap-1.5 mt-0.5">
                            {ai.item.itemType && (
                              <span className="text-[10px] px-1.5 py-0.5 rounded bg-muted/40 text-muted-foreground">
                                {ai.item.itemType === "initial"
                                  ? "初始图"
                                  : ai.item.itemType === "three_view"
                                    ? "三视图"
                                    : ai.item.itemType === "variant"
                                      ? "变体"
                                      : ai.item.itemType}
                              </span>
                            )}
                            {!getAssetImagePrompt(ai.item).trim() && (
                              <span className="text-xs text-muted-foreground">缺少生图提示词</span>
                            )}
                            {ai.item.imageUrl && (
                              <span className="text-[10px] text-green-400/70">
                                已有图片
                              </span>
                            )}
                          </div>
                        </div>
                      </button>
                    );
                  })}
                </div>
              );
            })
          )}
        </DialogScrollBody>

        {/* Footer */}
        <div className="flex shrink-0 items-center justify-end gap-2 border-t border-border/20 px-5 py-3">
          <Button type="button" variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button type="button" variant="image"
            onClick={handleConfirm}
            disabled={selected.size === 0 || missingPromptCount > 0 || !image.options || !review.ready}
          >
            <Sparkles />
            开始生成 ({selected.size})
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
