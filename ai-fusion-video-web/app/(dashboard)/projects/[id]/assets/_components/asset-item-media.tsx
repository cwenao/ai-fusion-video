"use client";

import { useRef, useState } from "react";
import { ImagePlus, Images, Loader2, Upload } from "lucide-react";
import type { AssetItem } from "@/lib/api/asset";
import type { AssetReference } from "@/lib/asset-references";
import { Button } from "@/components/ui/button";
import { SafeImage } from "@/components/media/managed-image";
import { ManagedVideo } from "@/components/media/managed-video";
import { ManagedAudio } from "@/components/media/managed-audio";
import { cn } from "@/lib/utils";

interface AssetItemMediaProps {
  item: AssetItem | null;
  references: AssetReference[];
  assetType: string;
  name: string;
  imageUrl: string;
  saving: boolean;
  uploading: boolean;
  generationDisabled: boolean;
  generationBlockedReason?: string;
  generationLabel: string;
  error: string;
  onImageChange: (value: string) => void;
  onUpload: (file: File) => Promise<void>;
  onGenerate: () => void;
}

export function AssetItemMedia({
  item, references, assetType, name, imageUrl, saving, uploading,
  generationDisabled, generationBlockedReason, generationLabel, error, onImageChange, onUpload, onGenerate,
}: AssetItemMediaProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const video = references.find((reference) => reference.mediaType === "video");
  const audio = references.find((reference) => reference.mediaType === "audio");
  const mode = item?.properties?.sourceGenerationMode;
  const isVideo = Boolean(video) || mode === "video" || item?.itemType === "generated_video";
  const isAudio = !isVideo && (mode === "audio" || item?.itemType === "generated_audio"
    || (Boolean(audio) && !references.some((reference) => reference.mediaType === "image")));
  const original = isVideo ? video : isAudio ? audio : undefined;
  const hasMedia = isVideo || isAudio ? Boolean(original) : Boolean(imageUrl);
  const fallbackType = assetType === "character" ? "avatar" : assetType === "scene"
    ? "scene" : assetType === "prop" ? "prop" : "image";
  const openUpload = () => {
    if (!saving && !uploading) {
      inputRef.current?.click();
    }
  };

  return (
    <div className="min-w-0 p-5">
      <div className="mb-3 flex items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-semibold">{isVideo ? "原始视频" : isAudio ? "原始音频" : "资产图"}</h3>
          <span className="rounded-md bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
            {hasMedia ? "已添加" : isVideo || isAudio ? "原件缺失" : "待添加"}
          </span>
        </div>
        {imageUrl && (
          <Button variant="ghost" size="xs" disabled={saving || uploading} onClick={() => onImageChange("")}>
            {isVideo || isAudio ? "移除封面" : "移除图片"}
          </Button>
        )}
      </div>

      {isVideo || isAudio ? (
        <div className="mx-auto flex aspect-[16/10] w-full max-w-[480px] items-center justify-center rounded-2xl border border-border/50 bg-muted/15 p-3">
          {original ? isVideo ? (
            <ManagedVideo
              key={original.reference}
              src={original.reference}
              poster={imageUrl || undefined}
              controls
              playsInline
              preload="metadata"
              aria-label={name || "子资产视频"}
              className="h-full w-full rounded-lg object-contain"
            />
          ) : (
            <ManagedAudio
              key={original.reference}
              src={original.reference}
              controls
              preload="metadata"
              aria-label={name || "子资产音频"}
              className="w-full"
            />
          ) : (
            <p role="status" className="text-sm text-muted-foreground">
              {isVideo ? "视频原件不可用" : "音频原件不可用"}
            </p>
          )}
        </div>
      ) : (
        <div
          role="button"
          tabIndex={saving || uploading ? -1 : 0}
          aria-label={imageUrl ? "子资产图片预览" : "上传子资产图片"}
          aria-disabled={saving || uploading}
          onClick={() => {
            if (!imageUrl) {
              openUpload();
            }
          }}
          onKeyDown={(event) => {
            if (!imageUrl && (event.key === "Enter" || event.key === " ")) {
              event.preventDefault();
              openUpload();
            }
          }}
          onDragEnter={(event) => {
            event.preventDefault();
            setDragging(true);
          }}
          onDragOver={(event) => event.preventDefault()}
          onDragLeave={() => setDragging(false)}
          onDrop={(event) => {
            event.preventDefault();
            setDragging(false);
            const file = event.dataTransfer.files?.[0];
            if (file) {
              void onUpload(file);
            }
          }}
          className={cn(
            "group relative mx-auto aspect-[16/10] w-full max-w-[480px] overflow-hidden rounded-2xl border bg-muted/15 outline-none transition-colors focus-visible:ring-2 focus-visible:ring-ring/50",
            dragging ? "border-primary bg-primary/5 ring-2 ring-primary/15" : "border-border/50",
            !imageUrl && "cursor-pointer border-dashed hover:border-primary/35 hover:bg-primary/5",
          )}
        >
          {imageUrl ? (
            <SafeImage src={imageUrl} alt={name || "子资产"} className="h-full w-full object-contain p-3" fallbackType={fallbackType} />
          ) : (
            <div className="flex h-full flex-col items-center justify-center text-center text-muted-foreground">
              <span className="grid h-12 w-12 place-items-center rounded-2xl bg-primary/8 text-primary/65">
                {uploading ? <Loader2 className="h-5 w-5 animate-spin" /> : <Images className="h-5 w-5" />}
              </span>
              <p className="mt-3 text-sm font-medium text-foreground/75">{dragging ? "松开即可上传" : "拖入图片或点击上传"}</p>
            </div>
          )}
        </div>
      )}

      {error && <p role="alert" className="mt-2 text-xs text-destructive">{error}</p>}
      <div className="mx-auto mt-3 grid w-full max-w-[480px] grid-cols-2 gap-2 p-1">
        <Button variant="outline" onClick={openUpload} disabled={saving || uploading}>
          {uploading ? <Loader2 className="animate-spin" /> : <Upload />}
          {isVideo || isAudio ? imageUrl ? "替换封面" : "上传封面" : imageUrl ? "替换图片" : "上传图片"}
        </Button>
        <Button variant="image" disabled={generationDisabled} onClick={onGenerate}>
          {saving ? <Loader2 className="animate-spin" /> : <ImagePlus />}
          {generationLabel}
        </Button>
      </div>
      {generationBlockedReason && <p className="text-center text-xs text-muted-foreground">{generationBlockedReason}</p>}
      <input
        ref={inputRef}
        hidden
        type="file"
        accept="image/*"
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (file) {
            void onUpload(file);
          }
          event.target.value = "";
        }}
      />
    </div>
  );
}
