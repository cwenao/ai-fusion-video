"use client";

import { useId, useRef, useState } from "react";
import { Loader2 } from "lucide-react";
import { toast } from "sonner";
import { DialogScrollBody } from "@/components/shared/dialog-scroll-body";
import { Button } from "@/components/ui/button";
import { ConfirmDialogModal } from "@/components/ui/confirm-dialog";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { getApiErrorMessage, getApiErrorStatus } from "@/lib/api/api-error";
import type { Project } from "@/lib/api/project";
import type { StoryboardPromptField, StoryboardPromptVersion, StoryboardScene } from "@/lib/api/storyboard";
import { StoryboardPromptChat } from "./storyboard-prompt-chat";
import { storyboardPromptFields, storyboardPromptLabels, type StoryboardPromptEditorData } from "./storyboard-prompt-fields";
import { StoryboardPromptHistory } from "./storyboard-prompt-history";

type Draft = { base: string; text: string };
type Preview = StoryboardPromptVersion | "latest";

function promptValue(text: string): string {
  return text.trim() ? text : "";
}

export function StoryboardPromptEditor({
  item, projectId, project, scene, initialField, onSave, onClose,
}: StoryboardPromptEditorData & {
  projectId: number;
  project?: Project | null;
  scene?: StoryboardScene;
  initialField: StoryboardPromptField;
  onClose: () => void;
}) {
  const promptId = useId();
  const titleRef = useRef<HTMLHeadingElement>(null);
  const [field, setField] = useState(initialField);
  const [drafts, setDrafts] = useState<Partial<Record<StoryboardPromptField, Draft>>>({});
  const [preview, setPreview] = useState<Preview | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirmClose, setConfirmClose] = useState(false);
  const [error, setError] = useState("");

  const latest = item[field] ?? "";
  const entry = drafts[field];
  const draft = entry?.text ?? latest;
  const dirty = promptValue(draft) !== promptValue(latest);
  const conflict = dirty && entry?.base !== latest;
  const hasChanges = storyboardPromptFields.some((key) => {
    const saved = item[key] ?? "";
    return promptValue(drafts[key]?.text ?? saved) !== promptValue(saved);
  });

  const previewingLatest = preview === "latest";
  const displayedPrompt = previewingLatest ? latest : preview?.prompt ?? draft;

  const changeDraft = (text: string) => {
    setDrafts((previous) => ({
      ...previous,
      [field]: text === latest ? undefined : { base: previous[field]?.base ?? latest, text },
    }));
    setError("");
  };

  const save = async () => {
    if (saving || preview || conflict || !dirty) {
      return;
    }
    setError("");
    setSaving(true);
    try {
      await onSave(item, field, promptValue(draft) || null);
      setDrafts((previous) => ({ ...previous, [field]: undefined }));
      toast.success("提示词已保存");
    } catch (cause) {
      setError(getApiErrorStatus(cause) === 409
        ? "镜头版本已变化，请核对后重试。"
        : getApiErrorMessage(cause));
    } finally {
      setSaving(false);
    }
  };

  const close = () => {
    if (saving) {
      return;
    }
    if (hasChanges) {
      setConfirmClose(true);
    } else {
      onClose();
    }
  };

  const context = {
    画面: item.content,
    画面期望: item.sceneExpectation,
    对白: item.dialogue,
    景别: item.shotType,
    运镜: item.cameraMovement,
    机位: item.cameraAngle,
    时长: item.duration,
    场次: scene?.sceneHeading,
    地点: scene?.location,
    时间: scene?.timeOfDay,
    项目画风: project?.artStyleDescription || project?.artStyle,
  };

  return (
    <Dialog open onOpenChange={(open) => {
      if (!open) {
        close();
      }
    }}>
      <DialogContent initialFocus={titleRef} showCloseButton={!saving}
        className="flex h-[calc(100dvh-2rem)] max-h-[calc(100vh-2rem)] flex-col gap-0 overflow-hidden
          rounded-2xl p-0 sm:max-w-[calc(100%-2rem)] 2xl:max-w-[1440px] motion-reduce:animate-none">
        <DialogHeader className="shrink-0 gap-1.5 border-b border-border/20 px-4 py-4 pr-14 md:px-5 md:pr-14">
          <DialogTitle ref={titleRef} tabIndex={-1} className="text-lg font-semibold">编辑提示词</DialogTitle>
          <DialogDescription className="truncate">
            镜头 #{item.shotNumber || item.autoShotNumber || item.id}
            {scene?.sceneHeading && ` · ${scene.sceneHeading}`}
          </DialogDescription>
        </DialogHeader>
        <DialogScrollBody className="flex-1 scroll-p-2 p-0 md:grid md:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]
          md:grid-rows-[minmax(22rem,1fr)]">
          <section aria-label="提示词工作区" className="flex min-w-0 flex-col gap-3 p-4 md:min-h-0 md:p-5">
            <div role="group" aria-label="提示词用途" className="flex flex-wrap gap-2">
              {storyboardPromptFields.map((option) => (
                <Button key={option} type="button" size="sm" variant={field === option ? "secondary" : "ghost"}
                  aria-pressed={field === option} disabled={saving} onClick={() => {
                    setField(option);
                    setPreview(null);
                    setError("");
                  }}>
                  {storyboardPromptLabels[option]}
                </Button>
              ))}
            </div>
            {conflict && !previewingLatest && (
              <div className="flex flex-wrap items-center gap-2">
                <p role="alert" className="text-sm text-destructive">提示词已有新版本。</p>
                <Button type="button" size="xs" variant="outline" onClick={() => setPreview("latest")}>查看最新</Button>
              </div>
            )}
            {error && !conflict && <p role="alert" className="text-sm text-destructive">{error}</p>}
            <div className="flex flex-col gap-3 md:min-h-0 md:flex-1 lg:flex-row">
              <StoryboardPromptHistory itemId={item.id} field={field} version={item.version}
                editing={preview === null} selectedVersion={previewingLatest ? undefined : preview?.version}
                disabled={saving} onEdit={() => setPreview(null)} onPreview={setPreview} />
              <div className="flex min-w-0 flex-col gap-2 md:min-h-0 md:flex-1">
                <label htmlFor={promptId} className="sr-only">{storyboardPromptLabels[field]}正文</label>
                {preview && (
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="text-sm text-muted-foreground">
                      {previewingLatest ? "最新提示词" : `历史版本 ${preview.version}`}
                    </span>
                    <div className="flex flex-wrap gap-2">
                      {previewingLatest && (
                        <Button type="button" size="xs" variant="ghost" onClick={() => {
                          setDrafts((previous) => ({ ...previous, [field]: { base: latest, text: draft } }));
                          setPreview(null);
                          setError("");
                        }}>保留我的草稿</Button>
                      )}
                      <Button type="button" size="xs" variant="outline" onClick={() => {
                        changeDraft(displayedPrompt);
                        setPreview(null);
                      }}>
                        {previewingLatest ? "载入最新版本" : "载入草稿"}
                      </Button>
                    </div>
                  </div>
                )}
                <Textarea key={`${field}:${previewingLatest ? "latest" : preview?.version ?? "draft"}`}
                  id={promptId} value={displayedPrompt} disabled={saving} readOnly={preview !== null}
                  aria-invalid={preview === null && (conflict || !!error)}
                  placeholder={previewingLatest ? "最新提示词为空" : "输入可用于生成的提示词"}
                  className="h-72 scroll-m-2 field-sizing-fixed leading-6 md:h-auto md:min-h-0 md:flex-1"
                  onChange={(event) => changeDraft(event.target.value)} />
              </div>
            </div>
          </section>
          {storyboardPromptFields.map((key) => (
            <StoryboardPromptChat key={key} projectId={projectId} label={storyboardPromptLabels[key]} context={context}
              draft={drafts[key]?.text ?? item[key] ?? ""} visible={field === key}
              disabled={saving || preview !== null} onApply={changeDraft} />
          ))}
        </DialogScrollBody>
        <div className="flex shrink-0 flex-wrap items-center justify-between gap-2 border-t border-border/20 px-4 py-3 md:px-5">
          <span role="status" className="text-xs text-muted-foreground">{hasChanges ? "有未保存的修改" : ""}</span>
          <div className="ml-auto flex items-center gap-2">
            <Button type="button" variant="ghost" disabled={saving} onClick={close}>关闭</Button>
            <Button type="button" disabled={saving || preview !== null || conflict || !dirty} onClick={() => void save()}>
              {saving && <Loader2 className="animate-spin motion-reduce:animate-none" />}
              保存提示词
            </Button>
          </div>
        </div>
        {confirmClose && (
          <ConfirmDialogModal open options={{
            title: "放弃未保存的修改？",
            confirmText: "放弃修改",
            cancelText: "继续编辑",
            variant: "warning",
          }} onResponse={(discard) => {
            if (discard) {
              onClose();
            } else {
              setConfirmClose(false);
            }
          }} />
        )}
      </DialogContent>
    </Dialog>
  );
}
