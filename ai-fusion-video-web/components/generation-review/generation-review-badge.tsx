"use client";

import { useEffect, useState } from "react";
import { ClipboardCheck } from "lucide-react";
import { DialogScrollBody } from "@/components/shared/dialog-scroll-body";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { generationReviewApi, type GenerationReviewReport, type GenerationReviewTarget } from "@/lib/api/generation-review";
import { mediaId } from "@/lib/api/media";

const verdictLabels = {
  MATCH: "符合要求",
  NEEDS_CHANGES: "需要修改",
  UNCERTAIN: "无法确认",
};

function stateLabel(report: GenerationReviewReport | null | undefined) {
  if (report?.result) {
    return verdictLabels[report.result.verdict];
  }
  switch (report?.state) {
    case "PENDING": return "等待审查";
    case "RUNNING": return "审查中";
    case "FAILED": return "审查失败";
    default: return report === null ? "暂无审查" : "读取审查…";
  }
}

export function GenerationReviewBadge({
  source, target, targetId, expected = false,
}: {
  source?: string | null;
  target?: GenerationReviewTarget;
  targetId?: number;
  expected?: boolean;
}) {
  const [attempt, setAttempt] = useState(0);
  const [value, setValue] = useState<{ key: string; report?: GenerationReviewReport | null; error?: boolean }>();
  const key = `${source}:${target}:${targetId}`;

  useEffect(() => {
    if (!source?.startsWith("media://")) {
      return;
    }
    const request = new AbortController();
    let timer: number | undefined;
    const load = async () => {
      try {
        const id = mediaId(source);
        if (!id) {
          return;
        }
        const scope = target && targetId !== undefined ? { target, targetId } : undefined;
        const report = await generationReviewApi.result(id, scope, request.signal);
        if (request.signal.aborted) {
          return;
        }
        setValue({ key, report: report ?? null });
        if (report?.state === "PENDING" || report?.state === "RUNNING") {
          timer = window.setTimeout(load, 2500);
        }
      } catch {
        if (!request.signal.aborted) {
          setValue({ key, error: true });
        }
      }
    };
    void load();
    return () => {
      request.abort();
      window.clearTimeout(timer);
    };
  }, [key, source, target, targetId, attempt]);

  const current = value?.key === key ? value : undefined;
  if (!source?.startsWith("media://") || (!expected && !current?.report && !current?.error)) {
    return null;
  }
  const report = current?.report;
  const label = current?.error ? "查看审查" : stateLabel(report);
  const result = report?.result;

  return (
    <Dialog>
      <DialogTrigger render={<Button type="button" size="xs" variant="outline" aria-label={`AI 审查：${label}`} />}>
        <ClipboardCheck />
        {label}
      </DialogTrigger>
      <DialogContent className="flex max-h-[calc(100vh-2rem)] flex-col overflow-hidden sm:max-w-lg">
        <DialogHeader className="shrink-0 pr-8">
          <DialogTitle>AI 审查</DialogTitle>
          <DialogDescription>{report ? `审查模型：${report.modelName}` : "查看这份素材的审查结果"}</DialogDescription>
        </DialogHeader>
        <DialogScrollBody className="flex flex-col gap-4">
          {current?.error ? (
            <div className="flex items-center gap-2">
              <p role="alert" className="text-sm text-destructive">无法读取审查结果。</p>
              <Button type="button" size="sm" variant="outline" onClick={() => setAttempt((value) => value + 1)}>
                重试
              </Button>
            </div>
          ) : (
            <>
              <p role="status" className="text-base font-semibold">{label}</p>
              {report?.errorMessage && <p className="text-sm text-destructive">{report.errorMessage}</p>}
              {result && (
                <>
                  <p className="whitespace-pre-wrap break-words text-sm">{result.summary}</p>
                  {result.findings.length > 0 && (
                    <ol className="flex list-decimal flex-col gap-3 pl-5">
                      {result.findings.map((finding, index) => (
                        <li key={index} className="break-words text-sm">
                          <p className="font-medium">{finding.issue}</p>
                          <p className="text-muted-foreground">依据：{finding.evidence}</p>
                          <p>建议：{finding.suggestion}</p>
                        </li>
                      ))}
                    </ol>
                  )}
                  {result.limitations.length > 0 && (
                    <div className="flex flex-col gap-2 text-xs text-muted-foreground">
                      <p className="font-medium">审查限制</p>
                      <ul className="list-disc space-y-1 pl-4">
                        {result.limitations.map((item) => <li key={item} className="break-words">{item}</li>)}
                      </ul>
                    </div>
                  )}
                </>
              )}
            </>
          )}
        </DialogScrollBody>
      </DialogContent>
    </Dialog>
  );
}
