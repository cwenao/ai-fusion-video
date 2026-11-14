"use client";

import { useId, useState, type FormEvent } from "react";
import { DialogScrollBody } from "@/components/shared/dialog-scroll-body";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { getApiErrorMessage } from "@/lib/api/api-error";
import {
  PROMPT_TEMPLATE_CATEGORIES,
  type PromptTemplate,
  type PromptTemplateCategory,
  type PromptTemplateInput,
} from "@/lib/api/prompt-template";

const categories = [...PROMPT_TEMPLATE_CATEGORIES];
const emptyTemplate: PromptTemplateInput = { name: "", category: "general", markdown: "" };

export function PromptTemplateEditor({ template, onClose, onSave }: {
  template: PromptTemplate | null;
  onClose: () => void;
  onSave: (input: PromptTemplateInput) => Promise<void>;
}) {
  const field = useId();
  const [draft, setDraft] = useState<PromptTemplateInput>(() => template ?? emptyTemplate);
  const [saving, setSaving] = useState(false);
  const [attempted, setAttempted] = useState(false);
  const [error, setError] = useState("");
  const nameInvalid = !draft.name.trim();
  const markdownInvalid = !draft.markdown.trim();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setAttempted(true);
    setError("");
    if (nameInvalid || markdownInvalid || saving) {
      return;
    }
    setSaving(true);
    try {
      await onSave({ name: draft.name.trim(), category: draft.category, markdown: draft.markdown });
    } catch (cause) {
      setError(getApiErrorMessage(cause));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Dialog open onOpenChange={(open) => {
      if (!open && !saving) {
        onClose();
      }
    }}>
      <DialogContent showCloseButton={false} className="flex max-h-[calc(100vh-2rem)] flex-col gap-0 overflow-hidden p-0 sm:max-w-xl">
        <DialogHeader className="shrink-0 border-b border-border/20 px-5 py-4">
          <DialogTitle>{template ? "编辑模板" : "新建模板"}</DialogTitle>
          <DialogDescription>AI 会按模板正文组织资产生图提示词。</DialogDescription>
        </DialogHeader>
        <form noValidate className="flex min-h-0 flex-col" onSubmit={(event) => void submit(event)}>
          <DialogScrollBody className="flex flex-col gap-4 p-5">
            <div className="flex min-w-0 flex-col gap-2">
              <label htmlFor={`${field}-name`} className="text-sm font-medium">模板名称</label>
              <Input id={`${field}-name`} required maxLength={80} disabled={saving} value={draft.name}
                aria-invalid={attempted && nameInvalid || undefined}
                aria-describedby={attempted && nameInvalid ? `${field}-name-error` : undefined}
                onChange={(event) => setDraft({ ...draft, name: event.target.value })} />
              {attempted && nameInvalid && (
                <p id={`${field}-name-error`} role="alert" className="text-sm text-destructive">请输入模板名称</p>
              )}
            </div>
            <div className="flex min-w-0 flex-col gap-2">
              <label htmlFor={`${field}-category`} className="text-sm font-medium">适用资产</label>
              <Select items={categories} value={draft.category} disabled={saving}
                onValueChange={(category: PromptTemplateCategory | null) => {
                  if (category !== null) {
                    setDraft({ ...draft, category });
                  }
                }}>
                <SelectTrigger id={`${field}-category`} className="w-full"><SelectValue placeholder="选择适用资产" /></SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {categories.map(({ value, label }) => <SelectItem key={value} value={value}>{label}</SelectItem>)}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </div>
            <div className="flex min-w-0 flex-col gap-2">
              <label htmlFor={`${field}-markdown`} className="text-sm font-medium">Markdown 正文</label>
              <Textarea id={`${field}-markdown`} required maxLength={5000} disabled={saving} rows={9}
                className="field-sizing-fixed"
                aria-invalid={attempted && markdownInvalid || undefined}
                aria-describedby={attempted && markdownInvalid ? `${field}-markdown-error` : undefined}
                value={draft.markdown} placeholder={"## 主体特征\n描述资产最容易辨认的细节。\n\n## 构图与光线\n结合项目画风和画面比例。"}
                onChange={(event) => setDraft({ ...draft, markdown: event.target.value })} />
              {attempted && markdownInvalid && (
                <p id={`${field}-markdown-error`} role="alert" className="text-sm text-destructive">请输入 Markdown 模板正文</p>
              )}
            </div>
            {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
          </DialogScrollBody>
          <div className="flex shrink-0 justify-end gap-2 border-t border-border/20 px-5 py-3">
            <Button type="button" variant="outline" disabled={saving} onClick={onClose}>取消</Button>
            <Button type="submit" disabled={saving}>{saving ? "保存中…" : "保存"}</Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
