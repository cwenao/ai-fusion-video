"use client";

import { useState } from "react";
import { Loader2, Send, Sparkles } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import type { PipelineTask } from "@/lib/store/pipeline-store";
import { userFacingError } from "@/lib/user-facing-error";

const QUICK_REWRITE = "润色整集剧本，保留剧情和人物设定，改善叙事节奏、动作描写和对白。";

interface EpisodeRewritePanelProps {
  disabled: boolean;
  task: PipelineTask | undefined;
  instruction: string | undefined;
  candidate: string;
  hasOutput: boolean;
  onRewrite: (instruction: string) => void;
  onApply: () => void;
  onDiscard: () => void;
}

export function EpisodeRewritePanel({ disabled, task, instruction, candidate, hasOutput, onRewrite, onApply, onDiscard }: EpisodeRewritePanelProps) {
  const [input, setInput] = useState("");
  const running = task?.status === "running";
  const unavailable = disabled || running;
  let error = "";
  if (task?.status === "error") {
    error = userFacingError(task.state.error);
  } else if (task?.status === "cancelled") {
    error = "本轮改写已取消。";
  } else if (task?.status === "done" && !hasOutput) {
    error = "AI 未返回剧本正文，请重试。";
  } else if (instruction && !task) {
    error = "本轮改写记录已移除。";
  }

  return (
    <section aria-labelledby="episode-rewrite-heading" className="flex min-w-0 flex-col gap-4 rounded-xl border border-border/30 bg-card p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="episode-rewrite-heading" className="text-sm font-semibold">AI 改写</h2>
        <Button variant="ai" disabled={unavailable} onClick={() => onRewrite(QUICK_REWRITE)}>
          <Sparkles />
          一键改写
        </Button>
      </div>
      {instruction && (
        <div className="flex min-w-0 flex-col gap-2 border-t border-border/20 pt-3">
          <p className="text-xs text-muted-foreground">本轮要求</p>
          <p className="whitespace-pre-wrap wrap-anywhere text-sm">{instruction}</p>
          {running && (
            <p role="status" className="flex items-center gap-2 text-sm text-muted-foreground">
              <Loader2 className="size-4 animate-spin motion-reduce:animate-none" />
              {task.state.error ? "正在重新连接改写任务…" : "正在改写…"}
            </p>
          )}
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
        </div>
      )}

      {candidate && (
        <section className="flex min-w-0 flex-col gap-2">
          <h3 id="episode-rewrite-result" className="text-sm font-medium">改写稿</h3>
          <div tabIndex={0} role="region" aria-labelledby="episode-rewrite-result"
            className="max-h-72 overflow-y-auto rounded-lg border border-border/20 bg-background/70 p-3 text-sm leading-6 whitespace-pre-wrap wrap-anywhere focus-visible:outline-2 focus-visible:outline-ring">
            {candidate}
          </div>
          <div className="flex justify-end gap-2">
            <Button variant="ghost" disabled={unavailable} onClick={onDiscard}>舍弃</Button>
            <Button variant="outline" disabled={unavailable} onClick={onApply}>采用改写</Button>
          </div>
        </section>
      )}

      <form className="flex min-w-0 flex-col gap-2" onSubmit={(event) => {
        event.preventDefault();
        if (unavailable || !input.trim()) {
          return;
        }
        onRewrite(input.trim());
        setInput("");
      }}>
        <label htmlFor="episode-rewrite-instruction" className="text-sm font-medium">修改要求</label>
        <Textarea
          id="episode-rewrite-instruction"
          value={input}
          rows={3}
          disabled={unavailable}
          onChange={(event) => setInput(event.target.value)}
          placeholder="例如：缩短开场对白，保留结尾的反转"
          className="resize-y field-sizing-fixed"
        />
        {candidate && <p className="text-xs text-muted-foreground">继续调整时使用当前改写稿。</p>}
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
