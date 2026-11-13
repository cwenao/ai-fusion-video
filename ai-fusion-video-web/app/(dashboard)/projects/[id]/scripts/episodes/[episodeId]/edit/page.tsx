"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { ArrowLeft, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { getApiErrorMessage } from "@/lib/api/api-error";
import { scriptApi, type ScriptEpisode } from "@/lib/api/script";
import { episodeWorkspaceHref } from "../../../_components/episode-routes";
import { EpisodeSourceEditor } from "./_components/episode-source-editor";

type LoadState = { status: "loading" } | { status: "ready"; episode: ScriptEpisode } | { status: "error"; message: string };

export default function EditScriptEpisodePage() {
  const params = useParams<{ id: string; episodeId: string }>();
  const projectId = Number(params.id);
  const episodeId = Number(params.episodeId);
  const validIds = Number.isSafeInteger(projectId) && projectId > 0 && Number.isSafeInteger(episodeId) && episodeId > 0;
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!validIds) {
      return;
    }
    let active = true;
    void Promise.all([scriptApi.getByProject(projectId), scriptApi.getEpisode(episodeId)])
      .then(([script, episode]) => {
        if (active) {
          setState(script?.id === episode.scriptId
            ? { status: "ready", episode }
            : { status: "error", message: "该分集不属于当前项目剧本。" });
        }
      })
      .catch((failure: unknown) => {
        if (active) {
          setState({ status: "error", message: getApiErrorMessage(failure) });
        }
      });
    return () => {
      active = false;
    };
  }, [projectId, episodeId, validIds, attempt]);

  const backHref = episodeWorkspaceHref(projectId, episodeId);
  if (validIds && state.status === "ready") {
    return <EpisodeSourceEditor projectId={projectId} episode={state.episode} backHref={backHref} />;
  }

  return (
    <div className="flex flex-col items-start gap-4 p-1">
      <Button variant="ghost" nativeButton={false} render={<Link href={validIds ? backHref : `/projects/${params.id}/scripts`} />}>
        <ArrowLeft />
        返回剧本
      </Button>
      {validIds && state.status === "loading" ? (
        <p role="status" className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="size-4 animate-spin motion-reduce:animate-none" />
          正在读取分集…
        </p>
      ) : (
        <>
          <p role="alert" className="text-sm text-destructive">{state.status === "error" ? state.message : "分集地址无效。"}</p>
          {validIds && <Button variant="outline" onClick={() => {
            setState({ status: "loading" });
            setAttempt((previous) => previous + 1);
          }}>重新读取</Button>}
        </>
      )}
    </div>
  );
}
