"use client";

import { useRef, useState, type ChangeEvent, type ComponentProps } from "react";
import { FileUp, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { ScriptSourcePreview } from "./script-source-preview";

const MAX_FILE_BYTES = 8_000_000;

interface ScriptSourceFieldProps extends Omit<ComponentProps<typeof Textarea>, "id" | "value" | "onChange"> {
  id: string;
  label: string;
  value: string;
  reading: boolean;
  onReadingChange: (reading: boolean) => void;
  onValueChange: (value: string) => void;
}

export function ScriptSourceField({
  id, label, value, reading, onReadingChange, onValueChange, disabled, ...textareaProps
}: ScriptSourceFieldProps) {
  const input = useRef<HTMLInputElement>(null);
  const [preview, setPreview] = useState(false);
  const [importError, setImportError] = useState("");
  const busy = disabled || reading;

  async function importMarkdown(event: ChangeEvent<HTMLInputElement>) {
    const file = event.currentTarget.files?.[0];
    event.currentTarget.value = "";
    if (!file) {
      return;
    }
    setImportError("");
    if (!/\.(md|markdown)$/i.test(file.name)) {
      setImportError("请选择 .md 或 .markdown 文件。");
      return;
    }
    if (file.size > MAX_FILE_BYTES) {
      setImportError("文件不能超过 8 MB。");
      return;
    }

    onReadingChange(true);
    try {
      const content = new TextDecoder("utf-8", { fatal: true }).decode(await file.arrayBuffer());
      if (!content.trim() || content.includes("\0")) {
        setImportError("文件没有可导入的剧本文本。");
        return;
      }
      onValueChange(content);
      setPreview(false);
    } catch {
      setImportError("无法读取文件，请检查文件是否为 UTF-8 编码。");
    } finally {
      onReadingChange(false);
    }
  }

  return (
    <div className="flex min-w-0 flex-col gap-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <label id={`${id}-label`} htmlFor={id} className="text-sm font-medium">{label}</label>
        <div className="flex items-center gap-2">
          <input ref={input} type="file" accept=".md,.markdown" aria-label="Markdown 文件"
            hidden disabled={busy} onChange={importMarkdown} />
          <Button type="button" variant="ghost" disabled={busy} onClick={() => input.current?.click()}
            title="导入 .md 文件到编辑框">
            {reading ? <Loader2 className="animate-spin motion-reduce:animate-none" /> : <FileUp />}
            {reading ? "读取中" : "导入 MD"}
          </Button>
          <Button type="button" variant="outline" disabled={busy || (!preview && !value.trim())}
            onClick={() => setPreview(!preview)}>
            {preview ? "编辑源码" : "预览"}
          </Button>
        </div>
      </div>
      {preview ? (
        <div role="region" aria-label={`${label}预览`} tabIndex={0}
          className="max-h-96 min-w-0 overflow-auto rounded-lg border border-border/20 bg-background/70 p-3 focus-visible:outline-2 focus-visible:outline-ring">
          <ScriptSourcePreview content={value} />
        </div>
      ) : (
        <Textarea {...textareaProps} id={id} value={value} disabled={busy} onChange={(event) => {
          setImportError("");
          onValueChange(event.target.value);
        }} />
      )}
      {importError && <p role="alert" className="text-sm text-destructive">{importError}</p>}
    </div>
  );
}
