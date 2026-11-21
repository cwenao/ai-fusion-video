"use client";
import { useState } from "react";
import { CopyMediaLinksButton } from "@/components/media/copy-media-links-button";
import { ManagedVideo } from "@/components/media/managed-video";
import { Button } from "@/components/ui/button";
import { downloadMedia } from "@/lib/api/media";
import { toastApiError } from "@/lib/api/toast-api-error";
import { Download, Loader2, PlayCircle } from "lucide-react";

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
  const [downloading, setDownloading] = useState(false);
  if (!open || !videoUrl) {
    return null;
  }

  async function handleDownload(source: string) {
    setDownloading(true);
    try {
      await downloadMedia(source);
    } catch (error) {
      toastApiError(error, "下载视频失败");
    } finally {
      setDownloading(false);
    }
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
              type="button"
              variant="outline"
              size="xs"
              disabled={downloading}
              aria-busy={downloading || undefined}
              onClick={() => void handleDownload(videoUrl)}
            >
              {downloading ? <Loader2 className="animate-spin motion-reduce:animate-none" /> : <Download />}
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
