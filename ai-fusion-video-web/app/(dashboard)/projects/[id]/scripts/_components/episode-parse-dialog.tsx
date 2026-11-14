"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Loader2, Sparkles } from "lucide-react";
import { ScriptSourcePreview } from "@/components/dashboard/script-source-preview";
import { DialogScrollBody } from "@/components/shared/dialog-scroll-body";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { getApiErrorMessage } from "@/lib/api/api-error";
import { scriptApi, type ScriptEpisode } from "@/lib/api/script";

interface EpisodeParseDialogProps {
  episodeId: number;
  editHref: string;
  onClose: () => void;
  onStartParse: (episode: ScriptEpisode) => void;
}

export function EpisodeParseDialog({ episodeId, editHref, onClose, onStartParse }: EpisodeParseDialogProps) {
  const [episode, setEpisode] = useState<ScriptEpisode | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState("");
  const [loadAttempt, setLoadAttempt] = useState(0);
  const rawContent = episode?.rawContent;
  const hasSource = Boolean(rawContent?.trim());
  const hasScenes = Boolean(episode && episode.totalScenes > 0);

  useEffect(() => {
    let active = true;
    void scriptApi.getEpisode(episodeId).then((current) => {
      if (active) {
        setEpisode(current);
      }
    }).catch((failure: unknown) => {
      if (active) {
        setError(getApiErrorMessage(failure));
      }
    });
    return () => {
      active = false;
    };
  }, [episodeId, loadAttempt]);

  async function startParse() {
    if (!episode || !hasSource || starting || (hasScenes && !confirmed)) {
      return;
    }
    setStarting(true);
    setError("");
    try {
      const current = await scriptApi.getEpisode(episodeId);
      if (current.version !== episode.version) {
        setEpisode(current);
        setConfirmed(false);
        setError("该分集已更新，请核对最新原文和场次数后再解析。");
        return;
      }
      onStartParse(current);
      onClose();
    } catch (failure) {
      setError(getApiErrorMessage(failure));
    } finally {
      setStarting(false);
    }
  }

  return (
    <Dialog open onOpenChange={(open) => {
      if (!open && !starting) {
        onClose();
      }
    }}>
      <DialogContent showCloseButton={false} className="flex max-h-[calc(100vh-2rem)] flex-col gap-4 overflow-hidden rounded-2xl sm:max-w-2xl">
        <DialogHeader className="shrink-0">
          <DialogTitle>{episode ? `AI 解析 · 第 ${episode.episodeNumber} 集` : "AI 解析分集"}</DialogTitle>
          <DialogDescription>使用已保存的分集原文生成场次和对白。</DialogDescription>
        </DialogHeader>
        <DialogScrollBody className="flex flex-col gap-4">
          {!episode && !error && <p role="status" className="text-sm text-muted-foreground">正在读取分集原文…</p>}
          {episode && (
            <>
              {hasScenes && hasSource && (
                <div className="flex flex-col gap-2 rounded-lg border border-destructive/30 bg-destructive/5 p-3">
                  <p className="text-sm">解析将覆盖本集已有的 {episode.totalScenes} 个场次及其对白。</p>
                  <label className="flex items-center gap-2 text-sm">
                    <Checkbox checked={confirmed} onCheckedChange={setConfirmed} disabled={starting} />
                    确认覆盖已有场次和对白
                  </label>
                </div>
              )}
              <section aria-labelledby="episode-parse-source" className="flex min-w-0 flex-col gap-2">
                <h3 id="episode-parse-source" className="text-sm font-medium">已保存的剧本原文</h3>
                {rawContent?.trim() ? (
                  <div className="min-w-0 overflow-x-auto rounded-lg border border-border/20 bg-background/70 p-3">
                    <ScriptSourcePreview content={rawContent} />
                  </div>
                ) : (
                  <p className="text-sm text-muted-foreground">请填写并保存本集原文后再解析。</p>
                )}
              </section>
            </>
          )}
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
        </DialogScrollBody>
        <DialogFooter className="shrink-0">
          <Button variant="outline" onClick={onClose} disabled={starting}>取消</Button>
          {episode ? (
            hasSource ? (
              <Button variant="ai" onClick={startParse} disabled={starting || (hasScenes && !confirmed)}>
                {starting ? <Loader2 className="animate-spin motion-reduce:animate-none" /> : <Sparkles />}
                {starting ? "核对原文中" : "开始解析"}
              </Button>
            ) : (
              <Button nativeButton={false} render={<Link href={editHref} />}>填写原文</Button>
            )
          ) : (
            error && <Button onClick={() => {
              setError("");
              setLoadAttempt((attempt) => attempt + 1);
            }}>重新读取</Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
