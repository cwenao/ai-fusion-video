"use client";

import { useId, useRef, useState } from "react";
import { Loader2, Send, Sparkles } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { usePipelineStore } from "@/lib/store/pipeline-store";
import { userFacingError } from "@/lib/user-facing-error";

interface ProjectTextAiEditorProps {
  projectId: number;
  kind: "storyboardPrompt" | "assetAppearance";
  title: string;
  context: Record<string, unknown>;
  draft: string;
  quickInstruction: string;
  placeholder: string;
  disabled?: boolean;
  onApply: (value: string) => void;
}

export function ProjectTextAiEditor({
  projectId, kind, title, context, draft, quickInstruction, placeholder, disabled, onApply,
}: ProjectTextAiEditorProps) {
  const field = useId();
  const resultHeading = useId();
  const conversationId = useRef<string>(null);
  const [input, setInput] = useState("");
  const [turn, setTurn] = useState<{ id: string; instruction: string; previous: string } | null>(null);
  const task = usePipelineStore((state) => state.tasks.find((entry) => entry.id === turn?.id));
  const running = task?.status === "running";
  const unavailable = disabled || running;
  const output = task?.status === "done"
    ? task.state.timeline.flatMap((entry) => entry.type === "content" ? [entry.text] : []).join("").trim()
    : "";
  const candidate = output || turn?.previous || "";
  let error = "";
  if (task?.status === "error") {
    error = userFacingError(task.state.error);
  } else if (task?.status === "cancelled") {
    error = "本轮生成已取消。";
  } else if (task?.status === "done" && !output) {
    error = "AI 未返回可用草稿，请重试。";
  } else if (turn && !task) {
    error = "本轮任务记录已移除，请重试。";
  }

  const send = (instruction: string) => {
    if (unavailable || !instruction.trim()) {
      return;
    }
    conversationId.current ??= crypto.randomUUID();
    const id = usePipelineStore.getState().addPipeline({
      label: title,
      projectId,
      request: {
        agentType: "project_text_editor",
        category: "pipeline",
        conversationId: conversationId.current,
        projectId,
        title,
        toolExecutionMode: "DEFAULT",
        message: JSON.stringify({ kind, context, draft: candidate || draft, instruction }),
      },
    });
    setTurn({ id, instruction, previous: candidate });
  };

  const clear = () => {
    setTurn(null);
    conversationId.current = null;
  };

  return (
    <section className="flex min-w-0 flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-medium">AI 草稿</h3>
        <Button variant="ai" onClick={() => send(quickInstruction)} disabled={unavailable}>
          <Sparkles />
          {candidate || draft.trim() ? "润色草稿" : "生成草稿"}
        </Button>
      </div>
      {turn && (
        <div className="flex min-w-0 flex-col gap-1">
          <p className="text-xs text-muted-foreground">本轮要求</p>
          <p className="whitespace-pre-wrap wrap-anywhere text-sm">{turn.instruction}</p>
        </div>
      )}
      {running && <p role="status" className="flex items-center gap-2 text-sm text-muted-foreground">
        <Loader2 className="size-4 animate-spin motion-reduce:animate-none" />
        {task.state.error ? "正在重新连接…" : "正在生成草稿…"}
      </p>}
      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
      {candidate && (
        <div className="flex min-w-0 flex-col gap-2">
          <h4 id={resultHeading} className="text-sm font-medium">AI 候选稿</h4>
          <div role="region" aria-labelledby={resultHeading} tabIndex={0}
            className="max-h-64 overflow-y-auto whitespace-pre-wrap wrap-anywhere rounded-lg border border-border/30 bg-background/70 p-3 text-sm leading-6 focus-visible:outline-2 focus-visible:outline-ring">
            {candidate}
          </div>
          <div className="flex justify-end gap-2">
            <Button variant="ghost" disabled={unavailable} onClick={clear}>舍弃</Button>
            <Button variant="outline" disabled={unavailable} onClick={() => {
              onApply(candidate);
              clear();
            }}>采用草稿</Button>
          </div>
        </div>
      )}
      <form className="flex min-w-0 flex-col gap-2" onSubmit={(event) => {
        event.preventDefault();
        if (!unavailable && input.trim()) {
          send(input.trim());
          setInput("");
        }
      }}>
        <label htmlFor={field} className="text-sm font-medium">本轮修改要求</label>
        <Textarea id={field} value={input} rows={3} disabled={unavailable} placeholder={placeholder}
          className="field-sizing-fixed"
          onChange={(event) => setInput(event.target.value)} />
        <div className="flex justify-end">
          <Button type="submit" variant="secondary" disabled={unavailable || !input.trim()}>
            <Send />
            发送
          </Button>
        </div>
      </form>
    </section>
  );
}
