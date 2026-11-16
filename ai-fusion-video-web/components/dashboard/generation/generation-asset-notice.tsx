"use client";

import { FolderOpen, Loader2 } from "lucide-react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import type { useGenerationAssetReferences } from "./use-generation-asset-references";

export function GenerationAssetNotice({ assets, writebackBusy }: {
  assets: ReturnType<typeof useGenerationAssetReferences>;
  writebackBusy: boolean;
}) {
  return (
    <div className="flex min-w-0 flex-col gap-2" aria-live="polite">
      {assets.busy && <p className="flex items-center gap-2 text-sm text-muted-foreground"><Loader2 className="size-4 animate-spin" />
        {assets.promptImport ? "正在带入提示词…" : "正在载入参考素材…"}
      </p>}
      {assets.context && (
        <div className="flex min-w-0 flex-wrap items-center gap-2 rounded-lg border border-border/30 bg-card/50 p-2">
          <FolderOpen className="size-4 shrink-0 text-muted-foreground" />
          <span className="min-w-0 flex-1 truncate text-sm">
            {assets.context.id === null ? assets.context.name : `项目：${assets.context.name}`}
            {assets.isStoryboard && " · 来自分镜"}
          </span>
          {assets.isStoryboard && (
            <Button variant="ghost" nativeButton={false} role="link"
              render={<Link href={`/projects/${assets.context.id}/storyboards`} />}>
              返回分镜
            </Button>
          )}
          <Button type="button" variant="ghost" onClick={assets.clear} disabled={assets.busy || writebackBusy} title="清空参考素材并退出当前项目">
            {assets.context.id !== null && !assets.hasReferences && !assets.isStoryboard ? "退出项目" : "清空参考"}
          </Button>
        </div>
      )}
      {assets.referenceIssue && (
        <p role="alert" className="text-sm text-destructive">{assets.referenceIssue}。请切换模型或调整参考素材。</p>
      )}
      {assets.error && (
        <div role="alert" className="flex min-w-0 flex-wrap items-center gap-2 py-2">
          <p className="min-w-0 flex-1 text-sm text-destructive">{assets.error}</p>
          <Button type="button" variant="outline" onClick={assets.retryImport}>重试带入</Button>
          <Button type="button" variant="ghost" onClick={assets.dismissError}>关闭提示</Button>
        </div>
      )}
    </div>
  );
}
