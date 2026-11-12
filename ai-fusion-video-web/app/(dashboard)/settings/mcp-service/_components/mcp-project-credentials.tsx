"use client";

import { useEffect, useState } from "react";
import { Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { getApiErrorMessage } from "@/lib/api/api-error";
import { projectApi, type Project } from "@/lib/api/project";
import { useAuthStore } from "@/lib/store/auth-store";
import { settingsTypography } from "../../_shared";
import { McpCredentialForm } from "./mcp-credential-form";
import { McpCredentialList } from "./mcp-credential-list";

type ProjectsState =
  | { status: "loading" }
  | { status: "failed"; message: string }
  | { status: "ready"; projects: Project[] };

export function McpProjectCredentials() {
  const userId = useAuthStore((state) => state.user?.id);
  return <ProjectCredentialPanel key={userId} />;
}

function ProjectCredentialPanel() {
  const [state, setState] = useState<ProjectsState>({ status: "loading" });
  const [retry, setRetry] = useState(0);
  const [projectId, setProjectId] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let cancelled = false;
    projectApi.list().then(
      (projects) => {
        if (!cancelled) {
          setState({ status: "ready", projects });
        }
      },
      (error: unknown) => {
        if (!cancelled) {
          setState({ status: "failed", message: getApiErrorMessage(error) });
        }
      },
    );
    return () => {
      cancelled = true;
    };
  }, [retry]);

  function reloadProjects() {
    setState({ status: "loading" });
    setRetry((value) => value + 1);
  }

  return (
    <section
      aria-labelledby="mcp-credentials-title"
      className="flex min-w-0 flex-col gap-4 rounded-xl border border-border/30 bg-card p-4 sm:p-5"
    >
      <h2 id="mcp-credentials-title" className={settingsTypography.sectionTitle}>签发项目凭证</h2>
      {state.status === "loading" && (
        <p role="status" className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 aria-hidden="true" className="size-4 animate-spin" />
          正在加载项目
        </p>
      )}
      {state.status === "failed" && (
        <div className="flex flex-col items-start gap-2">
          <p role="alert" className="text-sm text-destructive">加载项目失败。{state.message}</p>
          <Button type="button" variant="outline" onClick={reloadProjects}>重试</Button>
        </div>
      )}
      {state.status === "ready" && (
        <>
          <McpCredentialForm
            projects={state.projects}
            projectId={projectId}
            onProjectChange={setProjectId}
            onIssued={() => setRevision((value) => value + 1)}
          />
          <div className="flex flex-col gap-2 border-t border-border/20 pt-4">
            <h3 className="text-base font-semibold">已签发凭证</h3>
            {projectId === null ? (
              <p className="text-sm text-muted-foreground">选择项目后查看凭证。</p>
            ) : (
              <McpCredentialList key={`${projectId}:${revision}`} projectId={projectId} />
            )}
          </div>
        </>
      )}
    </section>
  );
}
