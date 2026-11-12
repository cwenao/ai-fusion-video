"use client";

import { useId, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { getApiErrorMessage } from "@/lib/api/api-error";
import {
  deleteMcpProjectCredential,
  renameMcpProjectCredential,
  type McpCredentialMetadata,
} from "@/lib/api/mcp-project-credentials";
import { formatCredentialExpiry } from "./mcp-credential-format";

export function McpCredentialListItem({
  credential,
  projectId,
  onRenamed,
  onDeleted,
}: {
  credential: McpCredentialMetadata;
  projectId: string;
  onRenamed: (label: string) => void;
  onDeleted: () => void;
}) {
  const { confirm } = useConfirm();
  const inputId = useId();
  const [draft, setDraft] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function rename(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const label = draft?.trim();
    if (!label) {
      setError("请输入凭证名称。");
      return;
    }
    setError(null);
    setPending(true);
    try {
      await renameMcpProjectCredential(projectId, credential.credentialId, label);
      onRenamed(label);
      setDraft(null);
    } catch (failure) {
      setError(getApiErrorMessage(failure));
    } finally {
      setPending(false);
    }
  }

  async function remove() {
    setError(null);
    const confirmed = await confirm({
      title: "删除项目凭证",
      description: `删除「${credential.label}」后，使用该凭证的客户端将立即失去访问权限。`,
      variant: "destructive",
    });
    if (!confirmed) {
      return;
    }
    setPending(true);
    try {
      await deleteMcpProjectCredential(projectId, credential.credentialId);
      onDeleted();
    } catch (failure) {
      setError(getApiErrorMessage(failure));
    } finally {
      setPending(false);
    }
  }

  return (
    <li className="flex min-w-0 flex-col gap-2 p-3">
      {draft === null ? (
        <div className="flex min-w-0 flex-wrap items-center justify-between gap-3">
          <div className="flex min-w-0 flex-col gap-1">
            <p className="text-sm font-medium break-words">{credential.label}</p>
            <p className="text-xs text-muted-foreground">
              {credential.scope === "read" ? "只读" : "读写"} · {formatCredentialExpiry(credential.expiresAt)}
            </p>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            <Button type="button" variant="outline" disabled={pending} onClick={() => {
              setError(null);
              setDraft(credential.label);
            }}>
              改名
            </Button>
            <Button type="button" variant="destructive-ghost" disabled={pending} onClick={remove}>
              {pending ? "删除中" : "删除"}
            </Button>
          </div>
        </div>
      ) : (
        <form className="flex min-w-0 flex-col gap-2" onSubmit={rename}>
          <Label htmlFor={inputId}>凭证名称</Label>
          <div className="flex flex-wrap items-center gap-2">
            <Input
              id={inputId}
              className="min-w-0 flex-1"
              value={draft}
              maxLength={80}
              autoFocus
              disabled={pending}
              aria-invalid={error !== null}
              aria-describedby={error ? `${inputId}-error` : undefined}
              onChange={(event) => {
                setDraft(event.target.value);
                setError(null);
              }}
            />
            <div className="flex shrink-0 items-center gap-2">
              <Button type="submit" disabled={pending}>{pending ? "保存中" : "保存"}</Button>
              <Button type="button" variant="outline" disabled={pending} onClick={() => {
                setError(null);
                setDraft(null);
              }}>
                取消
              </Button>
            </div>
          </div>
        </form>
      )}
      {error && <p id={`${inputId}-error`} role="alert" className="text-sm text-destructive">{error}</p>}
    </li>
  );
}
