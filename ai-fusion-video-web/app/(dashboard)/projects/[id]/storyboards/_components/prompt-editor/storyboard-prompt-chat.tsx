"use client";

import { useEffect, useId, useRef, useState } from "react";
import { Loader2, MessageSquareText, Send } from "lucide-react";
import { useShallow } from "zustand/react/shallow";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { enqueueProjectText, projectTextOutput } from "@/lib/project-text-generation";
import { usePipelineStore, type PipelineTask } from "@/lib/store/pipeline-store";
import { userFacingError } from "@/lib/user-facing-error";

type PromptReply = { reply: string; prompt: string | null };

function parseReply(output: string): PromptReply | null {
  try {
    const result: unknown = JSON.parse(output);
    if (typeof result !== "object" || result === null || !("reply" in result) || !("prompt" in result)
      || typeof result.reply !== "string" || !result.reply.trim()) {
      return null;
    }
    const { reply, prompt } = result;
    if (prompt === null || (typeof prompt === "string" && prompt.trim())) {
      return { reply, prompt };
    }
    return null;
  } catch {
    return null;
  }
}

function AgentReply({ task, disabled, onApply }: {
  task: PipelineTask | undefined;
  disabled: boolean;
  onApply: (value: string) => void;
}) {
  if (task?.status === "running" || task?.state.contentLoading) {
    return (
      <p role="status" className="flex items-center gap-2 text-sm text-muted-foreground">
        <Loader2 className="size-4 shrink-0 animate-spin motion-reduce:animate-none" />
        {task.state.error ? "正在重新连接…" : "Agent 正在回复…"}
      </p>
    );
  }
  if (task?.status === "done" && task.state.contentLoaded === false) {
    return (
      <div className="flex flex-wrap items-center gap-2">
        <p role="alert" className="text-sm text-destructive">回复加载中断。</p>
        <Button type="button" size="xs" variant="outline"
          onClick={() => usePipelineStore.getState().loadPipelineContent(task.id)}>重试读取</Button>
      </div>
    );
  }
  if (task?.status === "cancelled") {
    return <p className="text-sm text-muted-foreground">本轮对话已取消。</p>;
  }
  if (task?.status === "error") {
    return <p role="alert" className="wrap-anywhere text-sm text-destructive">{userFacingError(task.state.error)}</p>;
  }
  const answer = parseReply(projectTextOutput(task));
  if (!answer) {
    return <p role="alert" className="text-sm text-destructive">未取得可用回复，请重新发送。</p>;
  }
  const { reply, prompt } = answer;
  return (
    <div className="flex min-w-0 flex-col gap-2">
      <p className="whitespace-pre-wrap wrap-anywhere text-sm leading-6">{reply}</p>
      {prompt && (
        <div className="flex flex-col items-start gap-2 rounded-lg border border-border/20 bg-background/70 p-3">
          <details className="w-full min-w-0 text-sm">
            <summary className="cursor-pointer rounded-sm font-medium">查看建议稿</summary>
            <p className="mt-2 whitespace-pre-wrap wrap-anywhere leading-6">{prompt}</p>
          </details>
          <Button type="button" size="xs" variant="outline" disabled={disabled} onClick={() => onApply(prompt)}>
            应用到草稿
          </Button>
        </div>
      )}
    </div>
  );
}

export function StoryboardPromptChat({ projectId, label, context, draft, disabled, visible, onApply }: {
  projectId: number;
  label: string;
  context: Record<string, unknown>;
  draft: string;
  disabled: boolean;
  visible: boolean;
  onApply: (value: string) => void;
}) {
  const composerId = useId();
  const conversationId = useRef<string>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const [input, setInput] = useState("");
  const [turns, setTurns] = useState<{ id: string; message: string }[]>([]);
  const tasks = usePipelineStore(useShallow((state) => turns.map(({ id }) => state.tasks.find((task) => task.id === id))));
  const task = tasks.at(-1);
  const running = task?.status === "running";
  const awaitingContent = task?.status === "done" && task.state.contentLoaded === false;
  const taskId = task?.id;
  const unavailable = disabled || running;

  useEffect(() => {
    if (visible && taskId && awaitingContent) {
      usePipelineStore.getState().loadPipelineContent(taskId);
    }
  }, [visible, taskId, awaitingContent]);

  useEffect(() => {
    if (visible && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [visible, tasks, turns.length]);

  const send = () => {
    const message = input.trim();
    if (!message || unavailable) {
      return;
    }
    conversationId.current ??= crypto.randomUUID();
    const id = enqueueProjectText({
      projectId,
      kind: "storyboardPromptChat",
      title: `分镜提示词对话 · ${label}`,
      context: { ...context, 用途: label },
      draft,
      instruction: message,
      conversationId: conversationId.current,
    });
    setTurns((previous) => [...previous, { id, message }]);
    setInput("");
  };

  return (
    <section aria-label="Agent 对话" className={visible
      ? "flex h-96 min-h-0 min-w-0 flex-col border-t border-border/20 bg-muted/20 md:h-auto md:border-t-0 md:border-l"
      : "hidden"}>
      <h3 className="flex shrink-0 items-center gap-2 px-4 py-4 text-sm font-semibold md:px-5">
        <MessageSquareText className="size-4 text-muted-foreground" aria-hidden />
        Agent 对话
      </h3>
      <div ref={scrollRef} role="log" aria-label="对话记录"
        className="min-h-0 flex-1 overflow-y-auto scroll-p-4 px-4 pb-4 md:px-5">
        {turns.length === 0 && (
          <p className="pt-1 text-sm leading-6 text-muted-foreground">可以讨论镜头写法，或请 Agent 修改提示词。</p>
        )}
        <ol className="flex min-w-0 flex-col gap-4">
          {turns.map((turn, index) => (
            <li key={turn.id} className="flex min-w-0 flex-col gap-3">
              <p className="max-w-[90%] self-end whitespace-pre-wrap wrap-anywhere rounded-xl bg-primary/10 px-3 py-2 text-sm">
                <span className="sr-only">你</span>{turn.message}
              </p>
              <AgentReply task={tasks[index]} disabled={unavailable} onApply={onApply} />
            </li>
          ))}
        </ol>
      </div>
      <form className="flex shrink-0 min-w-0 flex-col gap-2 border-t border-border/20 p-4 md:px-5" onSubmit={(event) => {
        event.preventDefault();
        send();
      }}>
        <label htmlFor={composerId} className="sr-only">给 Agent 发消息</label>
        <Textarea id={composerId} value={input} disabled={unavailable} rows={3}
          placeholder="例如，这段运镜怎么改得更自然？" className="scroll-m-2 field-sizing-fixed"
          onChange={(event) => setInput(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
              event.preventDefault();
              event.currentTarget.form?.requestSubmit();
            }
          }} />
        <div className="flex justify-end">
          <Button type="submit" variant="secondary" size="sm" disabled={unavailable || !input.trim()}>
            <Send />
            发送
          </Button>
        </div>
      </form>
    </section>
  );
}
