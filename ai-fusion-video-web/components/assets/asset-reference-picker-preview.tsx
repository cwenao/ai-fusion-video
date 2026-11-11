"use client";

import { ImageIcon, Music2, Video } from "lucide-react";
import { SafeImage } from "@/components/media/managed-image";
import { ManagedVideo } from "@/components/media/managed-video";
import { ManagedAudio } from "@/components/media/managed-audio";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { DialogScrollBody } from "@/components/shared/dialog-scroll-body";
import type { AssetReference } from "@/lib/asset-references";
import { ASSET_REFERENCE_MEDIA_LABELS } from "./asset-reference-picker-support";

export function AssetReferencePickerThumbnail({ reference }: { reference: AssetReference }) {
  const Icon = reference.mediaType === "video" ? Video : reference.mediaType === "audio" ? Music2 : ImageIcon;
  return (
    <span className="flex size-16 shrink-0 items-center justify-center overflow-hidden rounded-lg bg-muted">
      {reference.previewUrl !== null ? (
        <SafeImage src={reference.previewUrl} alt="" loading="lazy" className="size-full object-cover" />
      ) : (
        <Icon aria-hidden="true" className="size-6 text-muted-foreground" />
      )}
    </span>
  );
}

interface AssetReferencePickerPreviewProps {
  reference: AssetReference | null;
  onClose: () => void;
}

export function AssetReferencePickerPreview({ reference, onClose }: AssetReferencePickerPreviewProps) {
  return (
    <Dialog
      open={reference !== null}
      onOpenChange={(open) => {
        if (!open) {
          onClose();
        }
      }}
    >
      {reference !== null && (
        <DialogContent className="flex max-h-[calc(100vh-2rem)] flex-col overflow-hidden rounded-2xl sm:max-w-4xl">
          <DialogHeader className="shrink-0 pr-8">
            <DialogTitle className="break-words">{reference.name}</DialogTitle>
            <DialogDescription className="break-words">
              {reference.assetName} · {ASSET_REFERENCE_MEDIA_LABELS[reference.mediaType]}
            </DialogDescription>
          </DialogHeader>
          <DialogScrollBody>
            <div className="flex min-w-0 items-center justify-center rounded-lg border border-border/20 bg-muted/30 p-2">
              {reference.mediaType === "image" && (
                <SafeImage
                  src={reference.reference}
                  alt={reference.name}
                  className="max-h-[65vh] w-full object-contain"
                />
              )}
              {reference.mediaType === "video" && (
                <ManagedVideo
                  src={reference.reference}
                  controls
                  playsInline
                  preload="metadata"
                  aria-label={reference.name}
                  className="max-h-[65vh] w-full rounded-lg"
                />
              )}
              {reference.mediaType === "audio" && (
                <ManagedAudio
                  src={reference.reference}
                  controls
                  preload="metadata"
                  aria-label={reference.name}
                  className="w-full min-w-0"
                />
              )}
            </div>
          </DialogScrollBody>
        </DialogContent>
      )}
    </Dialog>
  );
}
