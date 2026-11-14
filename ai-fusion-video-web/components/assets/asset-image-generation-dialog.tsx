"use client";

import { useRef, useState } from "react";
import { Loader2, Sparkles } from "lucide-react";
import {
  ImageModelFields,
  useImageModelOptions,
  type ImageModelOptions,
} from "@/components/dashboard/generation/image-model-fields";
import { DialogScrollBody } from "@/components/shared/dialog-scroll-body";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { getApiErrorMessage } from "@/lib/api/api-error";

interface AssetImageGenerationDialogProps {
  title: string;
  description: string;
  onClose: () => void;
  onConfirm: (options: ImageModelOptions) => void | Promise<void>;
}

export function AssetImageGenerationDialog({
  title,
  description,
  onClose,
  onConfirm,
}: AssetImageGenerationDialogProps) {
  const image = useImageModelOptions(true, true);
  const submittingRef = useRef(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  const submit = async () => {
    if (!image.options || submittingRef.current) {
      return;
    }
    submittingRef.current = true;
    setSubmitting(true);
    setError("");
    try {
      await onConfirm(image.options);
      onClose();
    } catch (cause) {
      setError(getApiErrorMessage(cause));
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  };

  return (
    <Dialog open onOpenChange={(open) => {
      if (!open && !submittingRef.current) {
        onClose();
      }
    }}>
      <DialogContent className="flex max-h-[calc(100vh-2rem)] flex-col gap-0 overflow-hidden p-0 sm:max-w-lg"
        showCloseButton={!submitting}>
        <DialogHeader className="shrink-0 border-b border-border/20 px-5 py-4 pr-14">
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <DialogScrollBody className="flex flex-col gap-3 p-5">
          <ImageModelFields choice={image} disabled={submitting} />
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
        </DialogScrollBody>
        <div className="flex shrink-0 items-center justify-end gap-2 border-t border-border/20 px-5 py-3">
          <Button variant="ghost" disabled={submitting} onClick={onClose}>取消</Button>
          <Button variant="image" disabled={!image.options || submitting} onClick={() => void submit()}>
            {submitting ? <Loader2 className="animate-spin motion-reduce:animate-none" /> : <Sparkles />}
            提交生成
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
