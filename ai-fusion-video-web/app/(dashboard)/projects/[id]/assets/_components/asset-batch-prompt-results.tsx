"use client";

import { useId } from "react";
import { Loader2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { MAX_ASSET_IMAGE_PROMPT_LENGTH } from "./asset-image-prompt-context";
import type { BatchPromptDraft } from "./use-asset-batch-prompts";

function PromptDraftCard({ row, saving, savingAny, onEdit, onRetry, onSave }: {
  row: BatchPromptDraft;
  saving: boolean;
  savingAny: boolean;
  onEdit: (text: string) => void;
  onRetry: () => void;
  onSave: () => void;
}) {
  const field = useId();
  const name = row.item.name || row.asset.name;
  const saved = row.savedInBatch && row.text === row.savedText;
  const tooLong = row.text.length > MAX_ASSET_IMAGE_PROMPT_LENGTH;
  const status = row.status === "queued" ? "等待生成"
    : row.status === "running" ? "生成中"
    : row.status === "failed" ? "生成失败"
    : saved ? "已保存" : "待保存";

  return (
    <section className="flex min-w-0 flex-col gap-3 rounded-xl border border-border/30 bg-card/50 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <h3 className="break-words text-sm font-semibold">{name}</h3>
          <p className="break-words text-xs text-muted-foreground">{row.asset.name} · {row.template.label}</p>
        </div>
        <Badge variant="secondary">
          {row.status === "running" && <Loader2 className="animate-spin motion-reduce:animate-none" />}
          {status}
        </Badge>
      </div>
      {row.status === "ready" && (
        <div className="flex min-w-0 flex-col gap-2">
          <label htmlFor={field} className="text-sm font-medium">{name}的 Markdown 提示词</label>
          <Textarea id={field} rows={5} value={row.text} disabled={saving}
            aria-invalid={tooLong || undefined} aria-describedby={tooLong ? `${field}-error` : undefined}
            className="field-sizing-fixed" onChange={(event) => onEdit(event.target.value)} />
          {tooLong && <p id={`${field}-error`} role="alert" className="text-sm text-destructive">
            生图提示词最多 5000 字符。
          </p>}
        </div>
      )}
      {row.error && <p role="alert" className="text-sm text-destructive">{row.error}</p>}
      {row.status === "failed" && (
        <div className="flex justify-end">
          <Button variant="outline" onClick={onRetry}>重新生成</Button>
        </div>
      )}
      {row.status === "ready" && (
        <div className="flex justify-end">
          <Button variant="outline" disabled={savingAny || saved || tooLong || !row.text.trim()} onClick={onSave}>
            {saving && <Loader2 className="animate-spin motion-reduce:animate-none" />}
            保存提示词
          </Button>
        </div>
      )}
    </section>
  );
}

export function AssetBatchPromptResults({ drafts, savingId, onEdit, onRetry, onSave }: {
  drafts: BatchPromptDraft[];
  savingId: number | null;
  onEdit: (id: number, text: string) => void;
  onRetry: (id: number) => void;
  onSave: (row: BatchPromptDraft) => void;
}) {
  return (
    <div className="flex min-w-0 flex-col gap-3">
      {drafts.map((row) => (
        <PromptDraftCard key={row.item.id} row={row} saving={savingId === row.item.id} savingAny={savingId !== null}
          onEdit={(text) => onEdit(row.item.id, text)} onRetry={() => onRetry(row.item.id)} onSave={() => onSave(row)} />
      ))}
    </div>
  );
}
