"use client";

import { useState } from "react";
import Link from "next/link";
import { Loader2, Pencil } from "lucide-react";
import { ScriptSourcePreview } from "@/components/dashboard/script-source-preview";
import { Button } from "@/components/ui/button";
import { useCommandRequestKey } from "@/hooks/use-command-request-key";
import { scriptApi, type ScriptEpisode } from "@/lib/api/script";

interface EpisodeFieldsProps {
  episode: ScriptEpisode;
  onSaved?: (episode: ScriptEpisode) => void;
}

export function EpisodeDetailsEditor({ episode, scenesCount, editHref, onSaved }: EpisodeFieldsProps & {
  scenesCount: number;
  editHref: string;
}) {
  const rawContent = episode.rawContent;
  const hasSource = Boolean(rawContent?.trim());

  return (
    <div className="mb-5 flex flex-col gap-4">
      <div className="flex items-center justify-between gap-3">
        <span className="text-sm text-muted-foreground">第 {episode.episodeNumber} 集</span>
        <span className="text-xs text-muted-foreground">共 {scenesCount} 场</span>
      </div>
      <EpisodeField key={`title:${episode.id}`} episode={episode} field="title" label="分集标题" onSaved={onSaved} />
      <EpisodeField key={`synopsis:${episode.id}`} episode={episode} field="synopsis" label="剧情概述" onSaved={onSaved} />
      <section className="flex min-w-0 flex-col gap-2 border-t border-border/20 pt-3">
        <div className="flex items-center justify-between gap-2">
          <h2 id="episode-source-heading" className="text-sm font-medium">分集剧本原文</h2>
          <Button variant="ghost" nativeButton={false} render={<Link href={editHref} />}
            aria-label={hasSource ? "编辑分集剧本原文" : "填写分集剧本原文"}>
            <Pencil />
            {hasSource ? "编辑" : "填写"}
          </Button>
        </div>
        {rawContent?.trim() ? (
          <div tabIndex={0} role="region" aria-labelledby="episode-source-heading"
            className="max-h-48 min-w-0 overflow-auto rounded-lg border border-border/20 bg-background/70 p-3 focus-visible:outline-2 focus-visible:outline-ring">
            <ScriptSourcePreview content={rawContent} />
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">本集原文未填写</p>
        )}
      </section>
    </div>
  );
}

function EpisodeField({ episode, field, label, onSaved }: EpisodeFieldsProps & { field: "title" | "synopsis"; label: string }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState("");
  const [version, setVersion] = useState(episode.version);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const requests = useCommandRequestKey();
  const value = episode[field] || "";

  async function save() {
    const text = draft.trim();
    if (field === "title" && !text) {
      setError("请输入分集标题");
      return;
    }
    const payload = { id: episode.id, version, [field]: text };
    setSaving(true);
    try {
      const updated = await scriptApi.updateEpisode(payload, requests.forPayload(payload));
      requests.clear();
      onSaved?.(updated);
      setEditing(false);
      setError("");
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "保存失败，草稿已保留");
    } finally {
      setSaving(false);
    }
  }

  if (!editing) {
    return <div className="flex items-start gap-2">
      <div className="min-w-0 grow">
        <p className="text-xs text-muted-foreground mb-1">{label}</p>
        <p className={field === "title" ? "text-base font-semibold" : "text-sm text-muted-foreground whitespace-pre-wrap"}>{value || "未填写"}</p>
      </div>
      <Button variant="ghost" size="icon-sm" title={`编辑${label}`} onClick={() => {
        setDraft(value);
        setVersion(episode.version);
        setEditing(true);
      }}>
        <Pencil />
      </Button>
    </div>;
  }

  return <div className="flex min-w-0 flex-col gap-2">
    <label className="text-sm font-medium" htmlFor={`episode-${episode.id}-${field}`}>{label}</label>
    <textarea id={`episode-${episode.id}-${field}`} rows={field === "title" ? 1 : 4} autoFocus value={draft}
      onChange={event => setDraft(event.target.value)} disabled={saving}
      className="w-full rounded-xl border border-border/40 bg-background p-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      onKeyDown={event => {
        if ((event.ctrlKey || event.metaKey) && event.key === "Enter") {
          event.preventDefault();
          void save();
        }
      }} />
    {episode.version !== version && <div className="rounded-lg border border-border/20 bg-background/70 p-3 space-y-2">
      <p className="text-xs text-muted-foreground">分集已有新修改，当前已保存内容：</p>
      <p className="text-sm whitespace-pre-wrap">{value || "未填写"}</p>
      <Button variant="secondary" size="sm" onClick={() => {
        setVersion(episode.version);
        setError("");
      }}>基于此版本继续编辑</Button>
    </div>}
    {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
    <div className="flex justify-end gap-2">
      <Button variant="outline" disabled={saving} onClick={() => {
        setEditing(false);
        setError("");
        requests.clear();
      }}>取消</Button>
      <Button disabled={saving} onClick={save}>{saving && <Loader2 className="animate-spin" />}保存</Button>
    </div>
  </div>;
}
