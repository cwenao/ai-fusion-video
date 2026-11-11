"use client";

import { useState } from "react";
import { ArrowLeft, ArrowRight, Eye, FileVideo2, Music2, Trash2 } from "lucide-react";
import { SafeImage } from "@/components/media/managed-image";
import { ManagedVideo } from "@/components/media/managed-video";
import { ManagedAudio } from "@/components/media/managed-audio";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import type { SimpleAttachmentMediaType } from "./generation-types";

interface Reference {
  url: string;
  label: string;
  mediaType: SimpleAttachmentMediaType;
  group?: string;
}

interface Props {
  items: Reference[];
  onRemove: (index: number) => void;
  onMove: (index: number, direction: -1 | 1) => void;
}

export function GenerationReferenceStrip({ items, onRemove, onMove }: Props) {
  const [preview, setPreview] = useState<Reference | null>(null);
  return (
    <>
      <div className="flex gap-2 overflow-x-auto p-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden" aria-label="参考素材顺序">
        {items.map((item, index) => {
          const ordinal = items.slice(0, index + 1).filter((entry) => entry.group === item.group).length;
          return (
            <div key={`${item.group}-${item.url}-${index}`} className="flex w-28 shrink-0 flex-col gap-1">
              <button type="button" onClick={() => setPreview(item)} aria-label={`预览${item.label}${ordinal}`} className="relative grid h-16 w-full place-items-center overflow-hidden rounded-lg border border-border/40 bg-muted/30 outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2">
                {item.mediaType === "image" ? (
                  <SafeImage src={item.url} alt={`${item.label}${ordinal}`} className="h-full w-full object-cover" fallbackType="image" />
                ) : item.mediaType === "video" ? <FileVideo2 className="size-6 text-muted-foreground" /> : <Music2 className="size-6 text-muted-foreground" />}
                <span className="absolute inset-x-0 bottom-0 flex items-center justify-center gap-1 bg-black/60 px-1 py-0.5 text-[10px] text-white">
                  <Eye className="size-3" />{item.label} {ordinal}
                </span>
              </button>
              <div className="flex items-center justify-between gap-2">
                <Button type="button" variant="ghost" size="icon-xs" aria-label={`前移${item.label}${ordinal}`} disabled={index === 0 || item.group !== items[index - 1]?.group} onClick={() => onMove(index, -1)}><ArrowLeft /></Button>
                <Button type="button" variant="ghost" size="icon-xs" aria-label={`后移${item.label}${ordinal}`} disabled={index === items.length - 1 || item.group !== items[index + 1]?.group} onClick={() => onMove(index, 1)}><ArrowRight /></Button>
                <Button type="button" variant="destructive-ghost" size="icon-xs" aria-label={`移除${item.label}${ordinal}`} onClick={() => onRemove(index)}><Trash2 /></Button>
              </div>
            </div>
          );
        })}
      </div>
      <Dialog open={preview !== null} onOpenChange={(open) => {
        if (!open) {
          setPreview(null);
        }
      }}>
        <DialogContent className="flex max-h-[calc(100vh-2rem)] min-w-0 flex-col overflow-hidden sm:max-w-3xl">
          <DialogHeader className="shrink-0">
            <DialogTitle>参考素材预览</DialogTitle>
            <DialogDescription>{preview?.label}</DialogDescription>
          </DialogHeader>
          <div className="min-h-0 overflow-y-auto p-1">
            {preview?.mediaType === "image" ? <SafeImage src={preview.url} alt={preview.label} className="max-h-[65vh] w-full object-contain" fallbackType="image" />
              : preview?.mediaType === "video" ? <ManagedVideo src={preview.url} controls playsInline className="max-h-[65vh] w-full rounded-lg" />
                : preview ? <ManagedAudio src={preview.url} controls className="w-full" /> : null}
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}
