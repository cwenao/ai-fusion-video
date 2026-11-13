"use client";

import { useId, useState } from "react";
import { ChevronRight, Loader2 } from "lucide-react";
import { ProjectTextAiEditor } from "@/components/project-text/project-text-ai-editor";
import { DialogScrollBody } from "@/components/shared/dialog-scroll-body";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { getApiErrorMessage, getApiErrorStatus } from "@/lib/api/api-error";
import type { Project } from "@/lib/api/project";
import type { StoryboardItem, StoryboardScene } from "@/lib/api/storyboard";

const promptLabels = {
  videoPrompt: "视频提示词",
  firstFramePrompt: "首帧提示词",
  lastFramePrompt: "尾帧提示词",
} as const;

type PromptField = keyof typeof promptLabels;
type SavePrompt = (item: StoryboardItem, field: PromptField, value: string | null) => Promise<void>;

interface Context {
  item: StoryboardItem;
  projectId: number;
  project?: Project | null;
  scene?: StoryboardScene;
  onSave: SavePrompt;
}

function StoryboardPromptEditor({ item, projectId, project, scene, field, onSave, onClose }: Context & {
  field: PromptField;
  onClose: () => void;
}) {
  const id = useId();
  const [basePrompt, setBasePrompt] = useState(item[field] ?? "");
  const [draft, setDraft] = useState(item[field] ?? "");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const label = promptLabels[field];
  const latest = item[field] ?? "";
  const promptChanged = latest !== basePrompt;
  const save = async () => {
    if (saving || promptChanged || draft.trim() === latest.trim()) {
      return;
    }
    setError("");
    setSaving(true);
    try {
      await onSave(item, field, draft.trim() || null);
      onClose();
    } catch (cause) {
      setError(getApiErrorStatus(cause) === 409
        ? "镜头版本已变化，请核对当前内容后重试。"
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
      <DialogContent className="flex max-h-[calc(100vh-2rem)] flex-col gap-0 overflow-hidden p-0 sm:max-w-3xl"
        showCloseButton={!saving}>
        <DialogHeader className="shrink-0 border-b border-border/20 px-5 py-4 pr-14">
          <DialogTitle>编辑{label}</DialogTitle>
          <DialogDescription>镜头 #{item.shotNumber || item.autoShotNumber || item.id}</DialogDescription>
        </DialogHeader>
        <DialogScrollBody className="grid gap-5 p-5 md:grid-cols-[minmax(0,1.1fr)_minmax(0,0.9fr)]">
          <div className="flex min-w-0 flex-col gap-2">
            <label htmlFor={id} className="text-sm font-medium">{label}</label>
            <Textarea id={id} value={draft} disabled={saving} aria-invalid={promptChanged || !!error} rows={9} className="field-sizing-fixed"
              onChange={(event) => setDraft(event.target.value)} />
            {promptChanged && <div className="flex flex-col items-start gap-2">
              <p role="alert" className="text-sm text-destructive">这条提示词已有新版本。</p>
              <p className="whitespace-pre-wrap wrap-anywhere text-sm">{latest || "最新提示词为空"}</p>
              <Button variant="outline" onClick={() => {
                setBasePrompt(latest);
                setError("");
              }}>保留我的草稿</Button>
            </div>}
            {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
          </div>
          <ProjectTextAiEditor
            projectId={projectId}
            kind="storyboardPrompt"
            title={`AI 编辑 · ${label}`}
            context={{
              用途: label, 画面: item.content, 画面期望: item.sceneExpectation,
              对白: item.dialogue, 景别: item.shotType, 运镜: item.cameraMovement,
              机位: item.cameraAngle, 时长: item.duration,
              场次: scene?.sceneHeading, 地点: scene?.location, 时间: scene?.timeOfDay,
              项目画风: project?.artStyleDescription || project?.artStyle,
            }}
            draft={draft}
            quickInstruction={draft.trim() ? "润色当前提示词，保留镜头设定和创作意图。" : "按当前镜头内容写一条可直接用于生成的提示词。"}
            placeholder="例如：保留人物动作，改成更克制的光线与色调"
            disabled={saving}
            onApply={setDraft}
          />
        </DialogScrollBody>
        <div className="flex shrink-0 justify-end gap-2 border-t border-border/20 px-5 py-3">
          <Button variant="ghost" disabled={saving} onClick={onClose}>取消</Button>
          <Button disabled={saving || promptChanged || draft.trim() === latest.trim()} onClick={() => void save()}>
            {saving && <Loader2 className="animate-spin motion-reduce:animate-none" />}
            保存
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

export function StoryboardPromptCards(props: Context) {
  const [editing, setEditing] = useState<PromptField | null>(null);
  const heading = useId();
  return (
    <section aria-labelledby={heading} className="flex min-w-0 flex-col gap-2 p-4">
      <h3 id={heading} className="text-sm font-semibold">提示词</h3>
      <ul className="grid gap-2">
        {(Object.keys(promptLabels) as PromptField[]).map((field) => (
          <li key={field}>
            <button type="button" onClick={() => setEditing(field)}
              className="flex w-full min-w-0 flex-col gap-1 rounded-xl border border-border/30 bg-card p-3 text-left transition-colors hover:border-border/50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring">
              <span className="flex w-full items-center justify-between gap-2 text-sm font-medium">
                {promptLabels[field]}
                <ChevronRight aria-hidden className="size-4 shrink-0 text-muted-foreground" />
              </span>
              <span className="line-clamp-2 text-xs leading-relaxed wrap-anywhere text-muted-foreground">
                {props.item[field]?.trim() || "点击编写"}
              </span>
            </button>
          </li>
        ))}
      </ul>
      {editing && <StoryboardPromptEditor {...props} field={editing} onClose={() => setEditing(null)} />}
    </section>
  );
}
