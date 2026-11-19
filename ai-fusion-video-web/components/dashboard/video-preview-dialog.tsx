"use client";
import { CopyMediaLinksButton } from "@/components/media/copy-media-links-button";
import { ManagedMediaLink } from "@/components/media/managed-media-link";
import { ManagedVideo } from "@/components/media/managed-video";
import { Button } from "@/components/ui/button";
import { Download, PlayCircle } from "lucide-react";

interface VideoPreviewDialogProps {
  open: boolean;
  title: string;
  videoUrl: string | null;
  onClose: () => void;
}

export function VideoPreviewDialog({
  open,
  title,
  videoUrl,
  onClose,
}: VideoPreviewDialogProps) {
  if (!open || !videoUrl) {
    return null;
  }

  return (
    <div
      onClick={onClose}
      className="modal-overlay fixed inset-0 z-50 flex items-center justify-center p-4"
    >
      <div
        onClick={(event) => event.stopPropagation()}
        className="modal-surface relative w-full max-w-4xl overflow-hidden rounded-2xl border border-border/40"
      >
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border/20 px-4 py-3">
          <h3 className="flex items-center gap-2 text-sm font-semibold">
            <PlayCircle className="h-4 w-4 text-emerald-500" />
            {title}
          </h3>
          <div className="flex items-center gap-2">
            <CopyMediaLinksButton sources={[videoUrl]} label="复制视频链接" size="xs" variant="outline">
              复制链接
            </CopyMediaLinksButton>
            <Button
              variant="outline"
              size="xs"
              nativeButton={false}
              render={<ManagedMediaLink href={videoUrl} target="_blank" rel="noreferrer" download />}
            >
              <Download />
              下载视频
            </Button>
            <Button type="button" variant="ghost" size="xs" onClick={onClose}>
              关闭
            </Button>
          </div>
        </div>
        <ManagedVideo
          src={videoUrl}
          controls
          autoPlay
          playsInline
          className="w-full max-h-[75vh] bg-black"
        />
      </div>
    </div>
  );
}
