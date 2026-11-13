"use client";

import { useCallback, useRef, useState } from "react";
import { Image as ImageIcon, Loader2, Sparkles, X, ZoomIn } from "lucide-react";
import ImageInput from "@/components/dashboard/image-input";
import { SafeImage } from "@/components/media/managed-image";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DialogScrollBody } from "@/components/shared/dialog-scroll-body";
import { GenerationReviewBadge } from "@/components/generation-review/generation-review-badge";
import { FramePromptDialog } from "./storyboard-frame-prompt-dialog";
import type { FrameGenerateHandler } from "./storyboard-reference/reference-shared";
import { resolveMediaUrl } from "@/lib/api/client";
import { toastApiError } from "@/lib/api/toast-api-error";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { cn } from "@/lib/utils";
import type { Project } from "@/lib/api/project";
import type { StoryboardFrameType, StoryboardItem } from "@/lib/api/storyboard";

type FrameUpdateHandler = (
  item: StoryboardItem,
  frameType: StoryboardFrameType,
  imageUrl: string | null
) => Promise<void> | void;

function getFrameLabel(frameType: StoryboardFrameType) {
  return frameType === "first" ? "首帧" : "尾帧";
}

function buildDefaultFramePrompt(
  item: StoryboardItem,
  project: Project | null | undefined,
  frameType: StoryboardFrameType
) {
  const style =
    [
      project?.artStyleDescription,
      project?.artStyleImagePrompt,
      project?.artStyle,
    ].find((text) => text && text.trim()) || "高质量精细画面";
  const shotLabel = item.shotNumber || item.autoShotNumber || String(item.id);
  const parts = [
    `项目画风：${style}`,
    `镜头：${shotLabel}`,
    item.shotType ? `景别：${item.shotType}` : null,
    item.content ? `画面内容：${item.content}` : null,
    item.sceneExpectation ? `画面期望：${item.sceneExpectation}` : null,
    item.dialogue ? `对白/旁白：${item.dialogue}` : null,
    item.cameraMovement ? `运镜：${item.cameraMovement}` : null,
    item.cameraAngle ? `机位角度：${item.cameraAngle}` : null,
  ].filter(Boolean);

  const frameInstruction =
    frameType === "first"
      ? "生成该镜头视频的首帧定格图，表现动作开始前或刚开始的关键瞬间，画面要能自然作为视频开场。"
      : "生成该镜头视频的尾帧定格图，表现动作完成后的结尾状态，画面要能自然作为视频结束帧。";

  return `${frameInstruction}\n${parts.join("\n")}`;
}

export function buildDefaultBatchFramePrompt(frameType: StoryboardFrameType) {
  const frameInstruction =
    frameType === "first"
      ? "生成每个分镜镜头视频的首帧定格图，表现动作开始前或刚开始的关键瞬间，画面要能自然作为视频开场。"
      : "生成每个分镜镜头视频的尾帧定格图，表现动作完成后的结尾状态，画面要能自然作为视频结束帧。";

  return `${frameInstruction}\n请结合每个镜头自身的画面内容、画面期望、对白/旁白、景别、运镜、机位角度、角色、道具、场景和项目画风补足构图、主体关系、环境氛围与细节。保持同一分镜集内角色、道具、场景和画面风格一致。`;
}

export function FrameReferenceSection({
  item,
  project,
  frameType,
  imageUrl,
  prompt,
  className,
  onUpdateFrame,
  onGenerateFrame,
  onGenerateSubmitted,
  onPreviewImage,
}: {
  item: StoryboardItem;
  project?: Project | null;
  frameType: StoryboardFrameType;
  imageUrl: string | null;
  prompt: string | null;
  className?: string;
  onUpdateFrame?: FrameUpdateHandler;
  onGenerateFrame?: FrameGenerateHandler;
  onGenerateSubmitted?: () => void;
  onPreviewImage?: (url: string, title: string) => void;
}) {
  const label = getFrameLabel(frameType);
  const [promptOpen, setPromptOpen] = useState(false);
  const [updating, setUpdating] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const skipNextChangeConfirmRef = useRef(false);
  const defaultPrompt = buildDefaultFramePrompt(item, project, frameType);
  const { confirm } = useConfirm();

  const confirmOverwrite = useCallback(async () => {
    if (!imageUrl) return true;
    return await confirm({
      title: "覆盖参考图",
      description: `${label}参考图已存在，确认覆盖吗？`,
      variant: "warning",
      confirmText: "确认覆盖",
    });
  }, [confirm, imageUrl, label]);

  const handleChange = async (nextValue: string) => {
    if (!onUpdateFrame) return;
    const nextUrl = nextValue.trim() || null;
    const currentUrl = imageUrl?.trim() || null;
    if (
      nextUrl &&
      currentUrl &&
      nextUrl !== currentUrl &&
      !skipNextChangeConfirmRef.current &&
      !(await confirmOverwrite())
    ) {
      return;
    }
    skipNextChangeConfirmRef.current = false;
    try {
      setUpdating(true);
      await onUpdateFrame(item, frameType, nextUrl);
    } catch (err) {
      console.error(`更新${label}失败:`, err);
      toastApiError(err, `更新${label}失败，请重试`);
    } finally {
      setUpdating(false);
    }
  };

  const handleOpenPrompt = async () => {
    if (!onGenerateFrame) return;
    if (!(await confirmOverwrite())) return;
    setPromptOpen(true);
  };

  const handleConfirmPrompt = async (nextPrompt: string, reviewModelId?: number) => {
    if (!onGenerateFrame) {
      return;
    }
    try {
      setSubmitting(true);
      await onGenerateFrame(item, frameType, nextPrompt, reviewModelId);
      setPromptOpen(false);
      setSubmitting(false);
      onGenerateSubmitted?.();
    } catch (err) {
      console.error(`提交${label}生成失败:`, err);
      toastApiError(err, `提交${label}生成失败，请重试`);
      setSubmitting(false);
    }
  };

  return (
    <div className={cn("border-t border-border/20 pt-4", className)}>
      <div className="flex items-center justify-between gap-2 mb-2">
        <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider flex items-center gap-1.5">
          <ImageIcon className="h-3 w-3" /> {label}参考图
        </h4>
        <div className="flex items-center gap-2">
          <GenerationReviewBadge source={imageUrl} target={frameType === "first" ? "SHOT_FIRST_FRAME" : "SHOT_LAST_FRAME"}
            targetId={item.id} />
          {imageUrl && onPreviewImage && (
            <button
              type="button"
              onClick={() => onPreviewImage(imageUrl, `${label}参考图`)}
              className="h-7 w-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-muted/40 transition-colors"
              title={`预览${label}`}
            >
              <ZoomIn className="h-3.5 w-3.5" />
            </button>
          )}
          {onGenerateFrame && (
            <button
              type="button"
              onClick={handleOpenPrompt}
              disabled={submitting}
              className="h-7 w-7 rounded-lg flex items-center justify-center text-primary hover:bg-primary/10 transition-colors disabled:opacity-40"
              title={`AI生成${label}`}
            >
              {submitting ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
              ) : (
                <Sparkles className="h-3.5 w-3.5" />
              )}
            </button>
          )}
        </div>
      </div>

      <div className={cn(updating && "opacity-70 pointer-events-none")}>
        <ImageInput
          value={imageUrl || ""}
          onChange={(nextUrl) => {
            void handleChange(nextUrl);
          }}
          previewHeight="h-96"
          previewContainerClassName="mx-auto w-[360px] max-w-full bg-muted/20"
          previewImageClassName="object-contain"
          onPreviewClick={
            imageUrl && onPreviewImage
              ? () => onPreviewImage(imageUrl, `${label}参考图`)
              : undefined
          }
          uploadSubDir="storyboard-frames"
          placeholder={`粘贴${label}图片链接...`}
          beforeUpload={async () => {
            const ok = await confirmOverwrite();
            skipNextChangeConfirmRef.current = ok;
            if (ok) {
              window.setTimeout(() => {
                skipNextChangeConfirmRef.current = false;
              }, 5000);
            }
            return ok;
          }}
        />
      </div>

      {prompt && (
        <p className="mt-2 text-[10px] text-muted-foreground/60 line-clamp-4 leading-relaxed">
          {prompt}
        </p>
      )}

      <FramePromptDialog
        key={`${item.id}-${frameType}-${promptOpen ? "open" : "closed"}`}
        open={promptOpen}
        title={`AI生成${label}`}
        defaultPrompt={defaultPrompt}
        submitting={submitting}
        onClose={() => setPromptOpen(false)}
        onConfirm={handleConfirmPrompt}
      />
    </div>
  );
}

export function StoryboardFrameReferenceDialog({
  open,
  item,
  project,
  initialFrameType = "first",
  onClose,
  onUpdateFrame,
  onGenerateFrame,
}: {
  open: boolean;
  item: StoryboardItem | null;
  project?: Project | null;
  initialFrameType?: StoryboardFrameType;
  onClose: () => void;
  onUpdateFrame?: FrameUpdateHandler;
  onGenerateFrame?: FrameGenerateHandler;
}) {
  const [activeFrameType, setActiveFrameType] =
    useState<StoryboardFrameType>(initialFrameType);
  const [previewImageUrl, setPreviewImageUrl] = useState<string | null>(null);
  const [previewImageTitle, setPreviewImageTitle] = useState("");

  const handlePreviewImage = useCallback((url: string, title: string) => {
    setPreviewImageUrl(url);
    setPreviewImageTitle(title);
  }, []);

  if (!open || !item) return null;

  const shotLabel = item.shotNumber || item.autoShotNumber || String(item.id);
  const activeImageUrl =
    activeFrameType === "first"
      ? item.firstFrameImageUrl
      : item.lastFrameImageUrl;
  const activePrompt =
    activeFrameType === "first" ? item.firstFramePrompt : item.lastFramePrompt;

  return (
    <>
      <Dialog open={open} onOpenChange={(next) => {
        if (!next) {
          onClose();
        }
      }}>
        <DialogContent className="flex h-[min(780px,calc(100vh-2rem))] max-h-[calc(100vh-2rem)] flex-col gap-0 overflow-hidden p-0 sm:max-w-3xl">
          <DialogHeader className="shrink-0 border-b border-border/20 px-5 py-4 pr-14">
            <DialogTitle>镜头 #{shotLabel} 首尾帧</DialogTitle>
            <DialogDescription>设置该镜号视频生成时使用的首帧和尾帧参考</DialogDescription>
          </DialogHeader>

          <div className="px-5 pt-4 shrink-0">
            <div className="grid grid-cols-2 gap-2 rounded-lg bg-muted/25 p-1">
              {(["first", "last"] as StoryboardFrameType[]).map((frameType) => {
                const label = getFrameLabel(frameType);
                const hasFrame =
                  frameType === "first"
                    ? !!item.firstFrameImageUrl
                    : !!item.lastFrameImageUrl;
                const active = activeFrameType === frameType;
                return (
                  <button
                    key={frameType}
                    type="button"
                    onClick={() => setActiveFrameType(frameType)}
                    className={cn(
                      "h-9 rounded-md px-3 text-xs font-medium transition-all flex items-center justify-center gap-2",
                      active
                        ? "bg-background text-foreground shadow-sm"
                        : "text-muted-foreground hover:text-foreground"
                    )}
                  >
                    <span>{label}</span>
                    <span
                      className={cn(
                        "h-1.5 w-1.5 rounded-full",
                        hasFrame ? "bg-emerald-500" : "bg-muted-foreground/30"
                      )}
                    />
                  </button>
                );
              })}
            </div>
          </div>

          <DialogScrollBody className="flex-1 p-5">
            <FrameReferenceSection
              key={`${item.id}-${activeFrameType}`}
              item={item}
              project={project}
              frameType={activeFrameType}
              imageUrl={activeImageUrl}
              prompt={activePrompt}
              className="border-t-0 pt-0"
              onUpdateFrame={onUpdateFrame}
              onGenerateFrame={onGenerateFrame}
              onPreviewImage={handlePreviewImage}
            />
          </DialogScrollBody>
        </DialogContent>
      </Dialog>

      {previewImageUrl && (
        <div
          className="modal-overlay fixed inset-0 z-[10020] flex items-center justify-center p-4 animate-in fade-in duration-200"
          onClick={() => setPreviewImageUrl(null)}
        >
          <div
            className="relative max-w-[90vw] max-h-[90vh] flex flex-col items-center gap-3"
            onClick={(event) => event.stopPropagation()}
          >
            <button
              onClick={() => setPreviewImageUrl(null)}
              className="absolute -top-12 right-0 rounded-full border border-border/40 bg-background/80 p-1.5 text-foreground shadow-xl backdrop-blur-xl transition-colors hover:bg-background"
              type="button"
            >
              <X className="h-5 w-5" />
            </button>
            <SafeImage
              src={resolveMediaUrl(previewImageUrl)}
              alt={previewImageTitle}
              fallbackType="image"
              className="max-w-full max-h-[80vh] rounded-lg object-contain shadow-2xl border border-border/40 select-none pointer-events-none"
            />
            <p className="rounded-full border border-border/40 bg-background/80 px-3 py-1.5 text-xs font-medium text-foreground shadow-xl backdrop-blur-xl">
              {previewImageTitle}
            </p>
          </div>
        </div>
      )}
    </>
  );
}
