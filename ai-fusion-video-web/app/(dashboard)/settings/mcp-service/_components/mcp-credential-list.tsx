"use client";

import { useEffect, useState } from "react";
import { Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { getApiErrorMessage } from "@/lib/api/api-error";
import { listMcpProjectCredentials, type McpCredentialMetadata } from "@/lib/api/mcp-project-credentials";
import { McpCredentialListItem } from "./mcp-credential-list-item";

type ListState =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; credentials: McpCredentialMetadata[] };

export function McpCredentialList({ projectId }: { projectId: string }) {
  const [state, setState] = useState<ListState>({ status: "loading" });
  const [retry, setRetry] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    listMcpProjectCredentials(projectId, controller.signal).then(
      (credentials) => {
        if (!controller.signal.aborted) {
          setState({ status: "ready", credentials: credentials.filter((item) => item.revokedAt === null) });
        }
      },
      (error: unknown) => {
        if (!controller.signal.aborted) {
          setState({ status: "error", message: getApiErrorMessage(error) });
        }
      },
    );
    return () => controller.abort();
  }, [projectId, retry]);

  function updateList(update: (credentials: McpCredentialMetadata[]) => McpCredentialMetadata[]) {
    setState((current) => {
      if (current.status === "ready") {
        return { status: "ready", credentials: update(current.credentials) };
      }
      return current;
    });
  }

  if (state.status === "loading") {
    return (
      <p role="status" className="flex items-center gap-2 text-sm text-muted-foreground">
        <Loader2 aria-hidden="true" className="size-4 animate-spin" />
        正在加载凭证
      </p>
    );
  }

  if (state.status === "error") {
    return (
      <div className="flex flex-col items-start gap-2">
        <p role="alert" className="text-sm text-destructive">加载凭证失败。{state.message}</p>
        <Button type="button" variant="outline" onClick={() => {
          setState({ status: "loading" });
          setRetry((value) => value + 1);
        }}>
          重试
        </Button>
      </div>
    );
  }

  if (state.credentials.length === 0) {
    return <p className="text-sm text-muted-foreground">暂无已签发凭证。</p>;
  }

  return (
    <ul aria-label="已签发凭证" className="divide-y divide-border/20">
      {state.credentials.map((credential) => (
        <McpCredentialListItem
          key={credential.credentialId}
          credential={credential}
          projectId={projectId}
          onRenamed={(label) => updateList((items) => items.map((item) =>
            item.credentialId === credential.credentialId ? { ...item, label } : item))}
          onDeleted={() => updateList((items) => items.filter((item) => item.credentialId !== credential.credentialId))}
        />
      ))}
    </ul>
  );
}
