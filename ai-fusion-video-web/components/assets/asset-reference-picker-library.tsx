"use client";

import { useState } from "react";
import { Check, Eye, Library, Loader2, RefreshCw, Search } from "lucide-react";
import { OverlayScrollArea } from "@/components/dashboard/overlay-scroll-area";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import type { AssetReference, AssetReferenceMediaType } from "@/lib/asset-references";
import { cn } from "@/lib/utils";
import { ASSET_REFERENCE_MEDIA_LABELS } from "./asset-reference-picker-support";
import { AssetReferencePickerThumbnail } from "./asset-reference-picker-preview";

interface AssetReferencePickerLibraryProps {
  references: AssetReference[];
  selection: AssetReference[];
  mediaTypes: AssetReferenceMediaType[];
  loading: boolean;
  error: string | null;
  disabled: boolean;
  onRetry: () => void;
  onToggle: (reference: AssetReference) => void;
  onPreview: (reference: AssetReference) => void;
}

export function AssetReferencePickerLibrary({
  references,
  selection,
  mediaTypes,
  loading,
  error,
  disabled,
  onRetry,
  onToggle,
  onPreview,
}: AssetReferencePickerLibraryProps) {
  const [search, setSearch] = useState("");
  const [mediaType, setMediaType] = useState<AssetReferenceMediaType | "all">("all");
  const query = search.trim().toLocaleLowerCase();
  const typeItems: { value: AssetReferenceMediaType | "all"; label: string }[] = [
    { value: "all", label: "全部类型" },
    ...mediaTypes.map((type) => ({ value: type, label: ASSET_REFERENCE_MEDIA_LABELS[type] })),
  ];
  const visible = references.filter((reference) =>
    mediaTypes.includes(reference.mediaType)
    && (mediaType === "all" || reference.mediaType === mediaType)
    && (!query || (reference.assetName + " " + reference.name).toLocaleLowerCase().includes(query)),
  );
  const positions = new Map(selection.map((reference, index) => [reference.key, index + 1]));

  return (
    <section className="flex h-full min-h-0 min-w-0 flex-col gap-3" aria-label="素材库">
      <div className="flex items-center justify-between gap-3 px-1">
        <h3 className="flex items-center gap-2 font-medium">
          <Library className="size-4 text-muted-foreground" aria-hidden="true" />
          素材库
        </h3>
        <div className="flex items-center gap-2">
          <span className="text-xs text-muted-foreground" aria-live="polite">
            {loading ? "加载中" : visible.length + " 个素材"}
          </span>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label="刷新素材"
            title="刷新素材"
            disabled={disabled || loading}
            onClick={onRetry}
          >
            <RefreshCw />
          </Button>
        </div>
      </div>
      <div className="flex min-w-0 flex-col gap-3 p-1 sm:flex-row">
        <div className="relative min-w-0 flex-1">
          <Search className="pointer-events-none absolute top-2.5 left-3 size-4 text-muted-foreground" aria-hidden="true" />
          <Input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="搜索资产或素材名称"
            aria-label="搜索资产或素材名称"
            disabled={disabled}
            className="rounded-xl pl-9"
          />
        </div>
        {mediaTypes.length > 1 && (
          <Select
            items={typeItems}
            value={mediaType}
            disabled={disabled}
            onValueChange={(value) => {
              if (value !== null) {
                setMediaType(value);
              }
            }}
          >
            <SelectTrigger className="w-full text-sm sm:w-36" aria-label="素材类型">
              <SelectValue placeholder="筛选类型" />
            </SelectTrigger>
            <SelectContent className="text-sm">
              <SelectGroup>
                {typeItems.map((item) => (
                  <SelectItem key={item.value} value={item.value} className="text-sm">{item.label}</SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        )}
      </div>
      <OverlayScrollArea className="min-h-0 flex-1">
        <div className="p-2">
          {loading ? (
            <div className="flex min-h-40 items-center justify-center gap-2 text-muted-foreground" role="status">
              <Loader2 className="size-4 animate-spin motion-reduce:animate-none" aria-hidden="true" />
              正在加载素材
            </div>
          ) : error !== null ? (
            <div className="flex min-h-40 flex-col items-center justify-center gap-3 text-center">
              <p role="alert" className="break-words text-sm text-destructive">{error}</p>
              <Button type="button" variant="outline" disabled={disabled} onClick={onRetry}>重试</Button>
            </div>
          ) : visible.length === 0 ? (
            <div className="flex min-h-40 flex-col items-center justify-center gap-3 text-center text-muted-foreground">
              <p>{query || mediaType !== "all" ? "没有匹配的素材" : "此来源暂无可用素材"}</p>
              {(search !== "" || mediaType !== "all") && (
                <Button
                  type="button"
                  variant="ghost"
                  disabled={disabled}
                  onClick={() => {
                    setSearch("");
                    setMediaType("all");
                  }}
                >
                  清除筛选
                </Button>
              )}
            </div>
          ) : (
            <ul className="flex flex-col gap-3">
              {visible.map((reference) => {
                const position = positions.get(reference.key);
                return (
                  <li
                    key={reference.key}
                    className={cn(
                      "flex min-w-0 items-center gap-2 rounded-xl border p-3 transition-colors duration-150 motion-reduce:transition-none",
                      position === undefined ? "border-border/30 bg-card hover:border-border/50" : "border-primary/50 bg-primary/5",
                    )}
                  >
                    <button
                      type="button"
                      aria-pressed={position !== undefined}
                      aria-label={(position === undefined ? "选择 " : "取消选择 ") + reference.assetName + " · " + reference.name}
                      disabled={disabled}
                      onClick={() => onToggle(reference)}
                      className="flex min-w-0 flex-1 items-center gap-3 rounded-lg text-left outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50"
                    >
                      <AssetReferencePickerThumbnail reference={reference} />
                      <span className="flex min-w-0 flex-1 flex-col gap-1">
                        <span className="break-words font-medium">{reference.name}</span>
                        <span className="break-words text-xs text-muted-foreground">
                          {reference.assetName} · {ASSET_REFERENCE_MEDIA_LABELS[reference.mediaType]}
                        </span>
                        {position !== undefined && <span className="text-xs text-primary">已选 · 第 {position} 项</span>}
                      </span>
                      <span
                        aria-hidden="true"
                        className={cn(
                          "flex size-5 shrink-0 items-center justify-center rounded border",
                          position === undefined ? "border-border/50" : "border-primary bg-primary text-primary-foreground",
                        )}
                      >
                        {position !== undefined && <Check className="size-3.5" />}
                      </span>
                    </button>
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      aria-label={"预览 " + reference.name}
                      title={"预览" + ASSET_REFERENCE_MEDIA_LABELS[reference.mediaType]}
                      disabled={disabled}
                      onClick={() => onPreview(reference)}
                    >
                      <Eye />
                    </Button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </OverlayScrollArea>
    </section>
  );
}
