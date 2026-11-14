"use client";

import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ArrowLeft, Loader2 } from "lucide-react";
import { toast } from "sonner";
import { ScriptSourceField } from "@/components/dashboard/script-source-field";
import { ScriptSourcePreview } from "@/components/dashboard/script-source-preview";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { useCommandRequestKey } from "@/hooks/use-command-request-key";
import { getApiErrorMessage, getApiErrorStatus } from "@/lib/api/api-error";
import { scriptApi, type ScriptEpisode } from "@/lib/api/script";
import { usePipelineStore } from "@/lib/store/pipeline-store";
import { EpisodeRewritePanel } from "./episode-rewrite-panel";

interface RewriteTurn {
  taskId: string;
  instruction: string;
  previousCandidate: string;
}

export function EpisodeSourceEditor({ projectId, episode, backHref }: {
  projectId: number;
  episode: ScriptEpisode;
  backHref: string;
}) {
  const router = useRouter();
  const { confirm } = useConfirm();
  const requests = useCommandRequestKey();
  const sourceRef = useRef<HTMLTextAreaElement>(null);
  const [saved, setSaved] = useState(episode);
  const [draft, setDraft] = useState(episode.rawContent ?? "");
  const [reading, setReading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState("");
  const [latest, setLatest] = useState<ScriptEpisode | null>(null);
  const [turn, setTurn] = useState<RewriteTurn | null>(null);
  const conversationId = useRef<string | null>(null);
  const rewriteTask = usePipelineStore((state) => state.tasks.find((task) => task.id === turn?.taskId));
  const rewriting = rewriteTask?.status === "running";
  const output = rewriteTask?.status === "done"
    ? rewriteTask.state.timeline.flatMap((item) => item.type === "content" ? [item.text] : []).join("")
    : "";
  const hasOutput = Boolean(output.trim());
  const candidate = hasOutput ? output : (turn?.previousCandidate ?? "");
  const rewriteSource = candidate || draft;
  const dirty = draft !== (saved.rawContent ?? "");
  const episodeLabel = `第 ${saved.episodeNumber} 集`;

  async function save() {
    if (saving || reading || !dirty || latest) {
      return;
    }
    const payload = { id: saved.id, version: saved.version, rawContent: draft || null };
    setSaveError("");
    setSaving(true);
    try {
      const updated = await scriptApi.updateEpisode(payload, requests.forPayload(payload));
      requests.clear();
      setSaved(updated);
      toast.success("分集剧本已保存");
    } catch (failure) {
      if (getApiErrorStatus(failure) === 409) {
        try {
          setLatest(await scriptApi.getEpisode(saved.id));
          setSaveError("该分集已有新修改，请核对已保存的原文。");
        } catch (readFailure) {
          setSaveError(`读取最新分集失败。${getApiErrorMessage(readFailure)}`);
        }
      } else {
        setSaveError(getApiErrorMessage(failure));
      }
    } finally {
      setSaving(false);
    }
  }

  function rewrite(instruction: string) {
    if (saving || reading || rewriting || !rewriteSource.trim()) {
      return;
    }
    conversationId.current ??= crypto.randomUUID();
    const title = `AI 改写 · ${episodeLabel}`;
    const taskId = usePipelineStore.getState().addPipeline({
      label: title,
      projectId,
      request: {
        agentType: "script_episode_rewrite",
        conversationId: conversationId.current,
        category: "pipeline",
        projectId,
        title,
        toolExecutionMode: "DEFAULT",
        message: JSON.stringify({ draft: rewriteSource, instruction }),
      },
    });
    setTurn({ taskId, instruction, previousCandidate: candidate });
  }

  async function goBack() {
    if (dirty || candidate || rewriting) {
      const accepted = await confirm({
        title: "返回分集",
        description: "离开后，本页未保存的内容会丢失。",
        variant: "warning",
        confirmText: "返回",
      });
      if (!accepted) {
        return;
      }
    }
    router.push(backHref);
  }

  return (
    <div className="flex min-w-0 flex-col gap-4 p-1">
      <header className="flex flex-col gap-3 border-b border-border/20 pb-4">
        <div className="flex items-center justify-between gap-2">
          <Button variant="ghost" onClick={goBack} disabled={saving || reading}>
            <ArrowLeft />
            返回剧本
          </Button>
          <div className="flex items-center gap-2">
            <span role="status" className="text-xs text-muted-foreground">
              {dirty ? "未保存" : saved.rawContent?.trim() ? "已保存" : "未填写"}
            </span>
            <Button onClick={save} disabled={saving || reading || !dirty || latest !== null}>
              {saving && <Loader2 className="animate-spin motion-reduce:animate-none" />}
              {saving ? "保存中" : "保存"}
            </Button>
          </div>
        </div>
        <div className="flex min-w-0 flex-col gap-1">
          <h1 className="text-xl font-semibold">编辑分集剧本</h1>
          <p className="text-sm text-muted-foreground wrap-anywhere">
            {episodeLabel}{saved.title && saved.title !== episodeLabel && ` · ${saved.title}`}
          </p>
        </div>
      </header>

      <div className="grid min-w-0 items-start gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(0,22rem)]">
        <section className="flex min-w-0 flex-col gap-2">
          <ScriptSourceField
            id="episode-source-draft"
            label="分集剧本原文"
            ref={sourceRef}
            value={draft}
            onValueChange={(content) => {
              setDraft(content);
              if (!latest) {
                setSaveError("");
              }
            }}
            reading={reading}
            onReadingChange={setReading}
            disabled={saving}
            aria-invalid={Boolean(saveError)}
            aria-describedby={saveError ? "episode-source-error" : undefined}
            className="h-72 min-h-48 resize-y field-sizing-fixed leading-7 sm:h-96 xl:h-128"
            placeholder="填写或粘贴本集纯文本或 Markdown 剧本"
          />
          {saveError && (
            <p id="episode-source-error" role="alert" className="text-sm text-destructive">{saveError}</p>
          )}
          {latest && (
            <section className="flex min-w-0 flex-col gap-2 rounded-lg border border-border/20 bg-background/70 p-3">
              <h2 id="latest-episode-source" className="text-sm font-medium">当前已保存的原文</h2>
              <div tabIndex={0} role="region" aria-labelledby="latest-episode-source"
                className="max-h-48 min-w-0 overflow-auto focus-visible:outline-2 focus-visible:outline-ring">
                {latest.rawContent?.trim() ? <ScriptSourcePreview content={latest.rawContent} /> : "本集原文未填写"}
              </div>
              <div>
                <Button variant="outline" onClick={() => {
                  setSaved(latest);
                  setLatest(null);
                  setSaveError("");
                }}>保留草稿并继续编辑</Button>
              </div>
            </section>
          )}
        </section>

        <EpisodeRewritePanel
          disabled={saving || reading || !rewriteSource.trim()}
          task={rewriteTask}
          instruction={turn?.instruction}
          candidate={candidate}
          hasOutput={hasOutput}
          onRewrite={rewrite}
          onApply={() => {
            setDraft(candidate);
            setTurn(null);
            sourceRef.current?.focus();
          }}
          onDiscard={() => setTurn(null)}
        />
      </div>
    </div>
  );
}
