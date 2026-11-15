"use client";

import { useId, useRef, useState } from "react";
import { Sparkles } from "lucide-react";
import { ScriptSourcePreview } from "@/components/dashboard/script-source-preview";
import { DialogScrollBody } from "@/components/shared/dialog-scroll-body";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import type { Asset } from "@/lib/api/asset";
import type { AssetItemVariant } from "@/lib/asset-item-variant";
import { AssetImagePromptAiEditor } from "./asset-image-prompt-ai-editor";
import { MAX_ASSET_IMAGE_PROMPT_LENGTH } from "./asset-image-prompt-context";
import { defaultAssetImagePromptTemplate } from "./asset-image-prompt-templates";

interface AssetImagePromptFieldProps {
  projectId: number;
  asset: Asset;
  itemName: string;
  itemType: string;
  appearance: string;
  variant: AssetItemVariant;
  value: string;
  disabled: boolean;
  onChange: (value: string) => void;
}

export function AssetImagePromptField({
  projectId, asset, itemName, itemType, appearance, variant, value, disabled, onChange,
}: AssetImagePromptFieldProps) {
  const field = useId();
  const sourceRef = useRef<HTMLTextAreaElement>(null);
  const aiRef = useRef<HTMLButtonElement>(null);
  const returnToSource = useRef(false);
  const [preview, setPreview] = useState(false);
  const [aiOpen, setAiOpen] = useState(false);
  const [templateId, setTemplateId] = useState(() => defaultAssetImagePromptTemplate(asset.type, itemType));
  const overflow = value.length > MAX_ASSET_IMAGE_PROMPT_LENGTH;

  return (
    <section className="flex min-w-0 flex-col gap-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <label id={`${field}-label`} htmlFor={field} className="text-sm font-medium">生图提示词</label>
        <div className="flex flex-wrap items-center gap-2">
          <Button type="button" variant="outline" disabled={disabled || (!preview && !value.trim())}
            onClick={() => setPreview(!preview)}>
            {preview ? "编辑源码" : "预览"}
          </Button>
          <Button ref={aiRef} type="button" variant="ai" disabled={disabled || !itemName}
            onClick={() => {
              returnToSource.current = false;
              setAiOpen(true);
            }}>
            <Sparkles />
            AI 写提示词
          </Button>
        </div>
      </div>
      <p id={`${field}-help`} className="text-xs text-muted-foreground">
        支持 Markdown。保存后用于生成当前子资产的图片。
      </p>
      {preview ? (
        <div role="region" aria-labelledby={`${field}-label`} tabIndex={0}
          className="max-h-80 overflow-auto rounded-lg border border-border/20 bg-background/70 p-3 focus-visible:outline-2 focus-visible:outline-ring">
          <ScriptSourcePreview content={value} />
        </div>
      ) : (
        <Textarea ref={sourceRef} id={field} value={value} rows={5} disabled={disabled}
          aria-describedby={`${field}-help${overflow ? ` ${field}-error` : ""}`}
          aria-invalid={overflow || undefined}
          placeholder="描述主体、构图、光线和画风"
          className="field-sizing-fixed"
          onChange={(event) => onChange(event.target.value)} />
      )}
      {overflow && (
        <p id={`${field}-error`} role="alert" className="text-sm text-destructive">生图提示词最多 5000 字符。</p>
      )}
      <Dialog open={aiOpen} onOpenChange={setAiOpen}>
        <DialogContent className="flex max-h-[calc(100vh-2rem)] flex-col gap-0 overflow-hidden p-0 sm:max-w-lg"
          finalFocus={() => returnToSource.current ? sourceRef.current : aiRef.current}>
          <DialogHeader className="shrink-0 border-b border-border/20 px-5 py-4 pr-14">
            <DialogTitle>AI 写生图提示词</DialogTitle>
            <DialogDescription>{itemName}</DialogDescription>
          </DialogHeader>
          <DialogScrollBody className="p-5">
            {aiOpen && (
              <AssetImagePromptAiEditor
                projectId={projectId}
                asset={asset}
                item={{ name: itemName, type: itemType, appearance, variant }}
                draft={value}
                templateId={templateId}
                onSelect={setTemplateId}
                onApply={(draft) => {
                  onChange(draft);
                  setPreview(false);
                  returnToSource.current = true;
                  setAiOpen(false);
                }}
              />
            )}
          </DialogScrollBody>
        </DialogContent>
      </Dialog>
    </section>
  );
}
