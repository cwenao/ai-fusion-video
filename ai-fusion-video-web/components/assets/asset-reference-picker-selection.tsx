"use client";

import { ArrowDown, ArrowUp, Eye, ListOrdered, X } from "lucide-react";
import { OverlayScrollArea } from "@/components/dashboard/overlay-scroll-area";
import { Button } from "@/components/ui/button";
import type { AssetReference } from "@/lib/asset-references";
import { ASSET_REFERENCE_MEDIA_LABELS } from "./asset-reference-picker-support";

interface AssetReferencePickerSelectionProps {
  selection: AssetReference[];
  disabled: boolean;
  onMove: (key: string, direction: -1 | 1) => void;
  onRemove: (key: string) => void;
  onClear: () => void;
  onPreview: (reference: AssetReference) => void;
}

export function AssetReferencePickerSelection({
  selection,
  disabled,
  onMove,
  onRemove,
  onClear,
  onPreview,
}: AssetReferencePickerSelectionProps) {
  return (
    <section
      className="flex h-full min-h-0 min-w-0 flex-col gap-3 rounded-xl border border-border/20 bg-muted/20 p-3"
      aria-label="已选素材和顺序"
    >
      <div className="flex shrink-0 items-center justify-between gap-2 px-1">
        <h3 className="flex items-center gap-2 font-medium">
          <ListOrdered className="size-4 text-muted-foreground" aria-hidden="true" />
          已选 {selection.length} 项
        </h3>
        <Button
          type="button"
          variant="destructive-ghost"
          disabled={disabled || selection.length === 0}
          onClick={onClear}
        >
          清空
        </Button>
      </div>
      <p className="px-1 text-xs text-muted-foreground">按编号顺序带入，可上移或下移调整。</p>
      <OverlayScrollArea className="min-h-0 flex-1">
        {selection.length === 0 ? (
          <p className="flex min-h-32 items-center justify-center p-2 text-center text-sm text-muted-foreground">
            从素材库选择要使用的素材
          </p>
        ) : (
          <ol className="flex flex-col gap-3 p-2">
            {selection.map((reference, index) => (
              <li key={reference.key} className="flex min-w-0 flex-col gap-3 rounded-lg border border-border/20 bg-background/70 p-3">
                <div className="flex min-w-0 items-start gap-3">
                  <span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-medium text-primary" aria-hidden="true">
                    {index + 1}
                  </span>
                  <div className="flex min-w-0 flex-1 flex-col gap-1">
                    <span className="break-words text-sm font-medium">{reference.name}</span>
                    <span className="break-words text-xs text-muted-foreground">
                      {reference.assetName} · {ASSET_REFERENCE_MEDIA_LABELS[reference.mediaType]}
                    </span>
                  </div>
                </div>
                <div className="flex items-center justify-between gap-2">
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label={"预览第 " + (index + 1) + " 项 " + reference.name}
                    title="预览"
                    disabled={disabled}
                    onClick={() => onPreview(reference)}
                  >
                    <Eye />
                  </Button>
                  <div className="flex items-center gap-2">
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      aria-label={"上移 " + reference.name}
                      title="上移"
                      disabled={disabled || index === 0}
                      onClick={() => onMove(reference.key, -1)}
                    >
                      <ArrowUp />
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      aria-label={"下移 " + reference.name}
                      title="下移"
                      disabled={disabled || index === selection.length - 1}
                      onClick={() => onMove(reference.key, 1)}
                    >
                      <ArrowDown />
                    </Button>
                    <Button
                      type="button"
                      variant="destructive-ghost"
                      size="icon"
                      aria-label={"移除 " + reference.name}
                      title="移除"
                      disabled={disabled}
                      onClick={() => onRemove(reference.key)}
                    >
                      <X />
                    </Button>
                  </div>
                </div>
              </li>
            ))}
          </ol>
        )}
      </OverlayScrollArea>
    </section>
  );
}
