"use client";

import { useId, useState } from "react";
import { Loader2, Sparkles } from "lucide-react";
import { GenerationReviewFields } from "@/components/generation-review/generation-review-fields";
import { useGenerationReviewChoice } from "@/components/generation-review/use-generation-review-choice";
import { DialogScrollBody } from "@/components/shared/dialog-scroll-body";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";

export function FramePromptDialog({
  open, title, defaultPrompt, submitting, onClose, onConfirm,
}: {
  open: boolean;
  title: string;
  defaultPrompt: string;
  submitting: boolean;
  onClose: () => void;
  onConfirm: (prompt: string, reviewModelId?: number) => Promise<void> | void;
}) {
  const [prompt, setPrompt] = useState(defaultPrompt);
  const field = useId();
  const review = useGenerationReviewChoice("image");
  const ready = prompt.trim().length > 0 && !submitting && review.ready;

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next && !submitting) {
          onClose();
        }
      }}
    >
      <DialogContent className="flex max-h-[calc(100vh-2rem)] flex-col gap-0 overflow-hidden p-0 sm:max-w-lg"
        showCloseButton={!submitting}>
        <DialogHeader className="shrink-0 border-b border-border/20 px-5 py-4 pr-14">
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>编辑提示词后提交生成</DialogDescription>
        </DialogHeader>
        <DialogScrollBody className="flex flex-col gap-4 p-5">
          <div className="flex min-w-0 flex-col gap-2">
            <label htmlFor={field} className="text-sm font-medium">首尾帧提示词</label>
            <Textarea id={field} value={prompt} disabled={submitting} rows={8}
              className="min-h-40 [field-sizing:fixed]"
              onChange={(event) => setPrompt(event.target.value)} />
          </div>
          <GenerationReviewFields choice={review} disabled={submitting} />
        </DialogScrollBody>
        <div className="flex shrink-0 items-center justify-end gap-2 border-t border-border/20 px-5 py-3">
          <Button type="button" variant="ghost" onClick={onClose} disabled={submitting}>取消</Button>
          <Button type="button" variant="frame" disabled={!ready} onClick={() => {
            if (ready) {
              void onConfirm(prompt.trim(), review.modelId);
            }
          }}>
            {submitting ? <Loader2 className="animate-spin motion-reduce:animate-none" /> : <Sparkles />}
            提交生成
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
