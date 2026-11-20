"use client";

import { useEffect, useState } from "react";
import { History } from "lucide-react";
import { Button } from "@/components/ui/button";
import { getApiErrorMessage } from "@/lib/api/api-error";
import { storyboardApi, type StoryboardPromptField, type StoryboardPromptVersion } from "@/lib/api/storyboard";

const dateFormat = new Intl.DateTimeFormat("zh-CN", {
  dateStyle: "short",
  timeStyle: "short",
});

type HistoryResponse = {
  request: string;
  versions: StoryboardPromptVersion[];
  error?: string;
};

export function StoryboardPromptHistory({
  itemId, field, version, editing, selectedVersion, disabled, onEdit, onPreview,
}: {
  itemId: number;
  field: StoryboardPromptField;
  version: number;
  editing: boolean;
  selectedVersion?: number;
  disabled: boolean;
  onEdit: () => void;
  onPreview: (entry: StoryboardPromptVersion) => void;
}) {
  const [retry, setRetry] = useState(0);
  const [response, setResponse] = useState<HistoryResponse | null>(null);
  const request = `${itemId}:${field}:${version}:${retry}`;
  const history = response?.request === request ? response : null;

  useEffect(() => {
    const controller = new AbortController();
    storyboardApi.listPromptHistory(itemId, field, controller.signal).then((versions) => {
      if (!controller.signal.aborted) {
        setResponse({ request, versions });
      }
    }).catch((cause: unknown) => {
      if (!controller.signal.aborted) {
        setResponse({ request, versions: [], error: getApiErrorMessage(cause) });
      }
    });
    return () => controller.abort();
  }, [itemId, field, request]);

  return (
    <aside aria-label="提示词历史" className="flex min-w-0 shrink-0 flex-col gap-2 rounded-2xl border border-border/40
      bg-popover/80 p-2.5 shadow-xl backdrop-blur-xl lg:order-last lg:w-32 lg:self-start">
      <div className="flex items-center gap-1.5 px-1 text-xs font-medium text-muted-foreground">
        <History className="size-3.5" aria-hidden />
        最近版本
      </div>
      <div className="flex flex-wrap gap-2 lg:flex-col">
        <Button type="button" size="xs" variant={editing ? "secondary" : "ghost"} aria-pressed={editing}
          className="justify-start" disabled={disabled} onClick={onEdit}>
          编辑稿
        </Button>
        {history?.versions.map((entry) => (
          <Button key={entry.version} type="button" size="xs"
            variant={selectedVersion === entry.version ? "secondary" : "ghost"}
            aria-pressed={selectedVersion === entry.version} className="justify-start"
            title={`替换于 ${dateFormat.format(new Date(entry.replacedAt))}`}
            disabled={disabled} onClick={() => onPreview(entry)}>
            版本 {entry.version}
          </Button>
        ))}
      </div>
      {!history && <p role="status" className="px-1 text-xs text-muted-foreground">加载中…</p>}
      {history?.error && (
        <div className="flex flex-wrap items-center gap-2">
          <p role="alert" className="wrap-anywhere px-1 text-xs text-destructive">{history.error}</p>
          <Button type="button" size="xs" variant="outline" disabled={disabled} onClick={() => setRetry((n) => n + 1)}>
            重试
          </Button>
        </div>
      )}
      {history && !history.error && history.versions.length === 0 && (
        <p className="px-1 text-xs text-muted-foreground">暂无历史版本</p>
      )}
    </aside>
  );
}
