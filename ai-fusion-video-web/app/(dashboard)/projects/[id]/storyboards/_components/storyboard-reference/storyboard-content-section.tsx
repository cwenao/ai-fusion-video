"use client";

import { useId, useState } from "react";
import { ImageIcon, Loader2, Pencil } from "lucide-react";
import { DialogScrollBody } from "@/components/shared/dialog-scroll-body";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { getApiErrorMessage, getApiErrorStatus } from "@/lib/api/api-error";
import type { StoryboardItem } from "@/lib/api/storyboard";

interface ContentProps {
  item: StoryboardItem;
  onSave: (content: string | null) => Promise<void>;
}

function StoryboardContentDialog({ item, onSave, onClose }: ContentProps & { onClose: () => void }) {
  const id = useId();
  const latest = item.content ?? "";
  const [baseContent, setBaseContent] = useState(latest);
  const [draft, setDraft] = useState(latest);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const contentChanged = latest !== baseContent;
  const value = draft.trim() ? draft : null;
  const canSave = !saving && !contentChanged && draft !== latest && value !== (latest || null);

  const save = async () => {
    if (!canSave) {
      return;
    }
    setSaving(true);
    setError("");
    try {
      await onSave(value);
      onClose();
    } catch (cause) {
      setError(getApiErrorStatus(cause) === 409
        ? "镜头版本已更新，请核对后重试。"
        : getApiErrorMessage(cause));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open onOpenChange={(open) => {
      if (!open && !saving) {
        onClose();
      }
    }}>
      <DialogContent
        className="flex max-h-[calc(100vh-2rem)] flex-col gap-0 overflow-hidden p-0 sm:max-w-lg"
        showCloseButton={!saving}
      >
        <DialogHeader className="shrink-0 border-b border-border/20 px-5 py-4 pr-14">
          <DialogTitle>编辑画面内容</DialogTitle>
          <DialogDescription>镜头 #{item.shotNumber || item.autoShotNumber || item.id}</DialogDescription>
        </DialogHeader>
        <DialogScrollBody className="flex flex-col gap-2 p-5">
          <label htmlFor={id} className="text-sm font-medium">画面内容</label>
          <Textarea
            id={id}
            value={draft}
            disabled={saving}
            aria-invalid={contentChanged || !!error}
            rows={6}
            className="field-sizing-fixed shrink-0"
            onChange={(event) => {
              setDraft(event.target.value);
              setError("");
            }}
          />
          {contentChanged && (
            <div className="flex flex-col items-start gap-2">
              <p role="alert" className="text-sm text-destructive">画面内容已有新版本。</p>
              <p className="whitespace-pre-wrap wrap-anywhere text-sm">{latest || "最新画面内容为空"}</p>
              <Button variant="outline" onClick={() => {
                setBaseContent(latest);
                setError("");
              }}>保留我的草稿</Button>
            </div>
          )}
          {error && !contentChanged && <p role="alert" className="text-sm text-destructive">{error}</p>}
        </DialogScrollBody>
        <div className="flex shrink-0 justify-end gap-2 border-t border-border/20 px-5 py-3">
          <Button variant="ghost" disabled={saving} onClick={onClose}>取消</Button>
          <Button disabled={!canSave} onClick={() => void save()}>
            {saving && <Loader2 className="animate-spin motion-reduce:animate-none" />}
            保存
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

export function StoryboardContentSection({ item, onSave }: ContentProps) {
  const [editing, setEditing] = useState(false);
  return (
    <section className="flex min-w-0 flex-col gap-2 border-t border-border/20 pt-4">
      <div className="flex items-center justify-between gap-2">
        <h4 className="flex items-center gap-1.5 text-xs font-semibold text-muted-foreground">
          <ImageIcon className="size-3" /> 画面内容
        </h4>
        <Button type="button" variant="ghost" size="icon-xs" title="编辑画面内容"
          aria-label="编辑画面内容" onClick={() => setEditing(true)}>
          <Pencil />
        </Button>
      </div>
      <p className="whitespace-pre-wrap wrap-anywhere text-xs leading-relaxed text-foreground/80">
        {item.content || "暂无画面内容"}
      </p>
      {editing && <StoryboardContentDialog item={item} onSave={onSave} onClose={() => setEditing(false)} />}
    </section>
  );
}
