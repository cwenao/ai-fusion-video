"use client";

import { useRef, useState } from "react";
import { toast } from "sonner";
import {
  CircleHelp,
  FileVideo2,
  ImageIcon,
  Library,
  Link2,
  Loader2,
  Music2,
  Plus,
} from "lucide-react";
import { uploadMedia } from "@/lib/api/media";
import { getApiErrorMessage } from "@/lib/api/api-error";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { GENERATION_ASSET_REFERENCE_FIELDS, type GenerationAssetReferenceField } from "./generation-asset-inputs";
import { GenerationReferenceStrip } from "./generation-reference-strip";

type ReferenceMediaType = "image" | "video" | "audio";

interface GenerationReferenceFieldProps {
  field: GenerationAssetReferenceField;
  hint?: string;
  values: string[];
  maxCount: number;
  onChange: (values: string[]) => void;
  onChooseAssets: (field: GenerationAssetReferenceField) => void;
  assetPickerDisabled: boolean;
  uploadDisabledReason?: string;
  manualInputAllowed?: boolean;
}

const MEDIA_CONFIG: Record<
  ReferenceMediaType,
  { accept: string; folder: string; emptyLabel: string }
> = {
  image: {
    accept: "image/*",
    folder: "generation-references",
    emptyLabel: "添加图片",
  },
  video: {
    accept: "video/*",
    folder: "generation-video-references",
    emptyLabel: "添加视频",
  },
  audio: {
    accept: "audio/*",
    folder: "generation-audio-references",
    emptyLabel: "添加音频",
  },
};

export function GenerationReferenceField({
  field,
  hint,
  values,
  maxCount,
  onChange,
  onChooseAssets,
  assetPickerDisabled,
  uploadDisabledReason,
  manualInputAllowed = true,
}: GenerationReferenceFieldProps) {
  const { label, mediaType } = GENERATION_ASSET_REFERENCE_FIELDS[field];
  const config = MEDIA_CONFIG[mediaType];
  const inputRef = useRef<HTMLInputElement>(null);
  const [url, setUrl] = useState("");
  const [showUrl, setShowUrl] = useState(false);
  const [uploading, setUploading] = useState(false);
  const canAdd = values.length < maxCount;
  const canUpload = canAdd && !uploadDisabledReason;

  const uploadFiles = async (files: File[]) => {
    if (!canUpload) {
      return;
    }
    const remaining = maxCount - values.length;
    const selected = files.slice(0, remaining);
    if (!selected.length) {
      return;
    }

    setUploading(true);
    try {
      const urls = await Promise.all(
        selected.map(async (file) => {
          const media = await uploadMedia(file, { requestId: crypto.randomUUID() });
          return `media://${media.id}`;
        }),
      );
      onChange([...values, ...urls]);
    } catch (error) {
      toast.error(getApiErrorMessage(error));
    } finally {
      setUploading(false);
      if (inputRef.current) {
        inputRef.current.value = "";
      }
    }
  };

  const addUrl = () => {
    const value = url.trim();
    if (!value || !canAdd) {
      return;
    }
    onChange([...values, value]);
    setUrl("");
    setShowUrl(false);
  };

  return (
    <section aria-label={label} className="rounded-xl border border-border/40 bg-background/52 p-3">
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-1.5">
          <h4 className="text-xs font-semibold">{label}</h4>
          {hint && (
            <span title={hint}>
              <CircleHelp className="h-3.5 w-3.5 text-muted-foreground" />
            </span>
          )}
        </div>
        <span className="text-[10px] tabular-nums text-muted-foreground">
          {maxCount > 0 && Number.isFinite(maxCount) ? `${values.length}/${maxCount}` : `已添加 ${values.length} 项`}
        </span>
      </div>

      <div className="mt-2 flex min-w-0 flex-col gap-2">
        <GenerationReferenceStrip
          items={values.map((url) => ({ url, label, mediaType }))}
          onRemove={(index) => onChange(values.filter((_, itemIndex) => itemIndex !== index))}
          onMove={(index, direction) => {
            const ordered = [...values];
            [ordered[index], ordered[index + direction]] = [ordered[index + direction], ordered[index]];
            onChange(ordered);
          }}
        />
        <div className="flex flex-wrap gap-2">
          {canAdd && (
            <Button
              type="button"
              variant="outline"
              className="grow"
              disabled={!canUpload || uploading}
              onClick={() => inputRef.current?.click()}
              title={uploadDisabledReason}
            >
              {uploading ? <Loader2 className="animate-spin" /> : mediaType === "image" ? <ImageIcon /> : mediaType === "video" ? <FileVideo2 /> : <Music2 />}
              {config.emptyLabel}
            </Button>
          )}
          <Button
            type="button"
            variant="outline"
            className="grow"
            disabled={!canAdd || uploading || assetPickerDisabled}
            onClick={() => onChooseAssets(field)}
            title={!canAdd ? `${label}数量已达上限` : undefined}
          >
            <Library />
            从资产库选择
          </Button>
        </div>
      </div>

      {uploadDisabledReason && (
        <p className="mt-2 text-[10px] leading-4 text-amber-600">
          {uploadDisabledReason}
          {manualInputAllowed ? "，仍可添加模型支持的公网图片 URL。" : "。"}
        </p>
      )}

      {showUrl ? (
        <div className="mt-3 flex min-w-0 gap-2 p-1">
          <Input
            value={url}
            onChange={(event) => setUrl(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                addUrl();
              }
            }}
            placeholder={
              mediaType === "image" ? "输入图片 URL 或 Data URI" : "输入媒体 URL"
            }
            className="min-w-0"
            autoFocus
          />
          <Button onClick={addUrl} disabled={!url.trim()}>
            <Plus className="h-3 w-3" />
            添加
          </Button>
        </div>
      ) : (
        canAdd && manualInputAllowed && (
          <div className="mt-2 flex justify-end">
            <Button
              type="button"
              variant="ghost"
              size="xs"
              onClick={() => setShowUrl(true)}
            >
              <Link2 className="h-3 w-3" />
              通过 URL 添加
            </Button>
          </div>
        )
      )}

      <input
        ref={inputRef}
        hidden
        multiple={maxCount > 1}
        type="file"
        accept={config.accept}
        onChange={(event) => void uploadFiles(Array.from(event.target.files || []))}
      />
    </section>
  );
}
