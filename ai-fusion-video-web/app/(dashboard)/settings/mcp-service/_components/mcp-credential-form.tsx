"use client";

import { useRef, useState, type FormEvent } from "react";
import { KeyRound, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { getApiErrorMessage, getApiErrorStatus } from "@/lib/api/api-error";
import {
  issueMcpProjectCredential,
  type IssuedMcpCredential,
  type McpCredentialScope,
} from "@/lib/api/mcp-project-credentials";
import type { Project } from "@/lib/api/project";
import { McpCredentialResult } from "./mcp-credential-result";

const scopeItems: { value: McpCredentialScope; label: string }[] = [
  { value: "read", label: "只读" },
  { value: "read_write", label: "读写（含素材上传）" },
];

const maxExpirySeconds = 604800;
const maxLabelLength = 80;

export function McpCredentialForm({
  projects,
  projectId,
  onProjectChange,
  onIssued,
}: {
  projects: Project[];
  projectId: string | null;
  onProjectChange: (projectId: string | null) => void;
  onIssued: () => void;
}) {
  const [label, setLabel] = useState("");
  const [scope, setScope] = useState<McpCredentialScope>("read");
  const [expiry, setExpiry] = useState("3600");
  const [attempted, setAttempted] = useState(false);
  const [saving, setSaving] = useState(false);
  const [issueError, setIssueError] = useState<string | null>(null);
  const [issued, setIssued] = useState<{ credential: IssuedMcpCredential; projectName: string } | null>(null);
  const pending = useRef(false);

  const projectItems = [
    { value: "all", label: "全部项目" },
    ...projects.map((project) => ({
      value: String(project.id),
      label: `${project.name}（#${project.id}）`,
    })),
  ];
  const project = projectItems.find((item) => item.value === projectId);
  const allProjectsSelected = projectId === "all";
  const trimmedLabel = label.trim();
  const expirySeconds = Number(expiry);
  const validExpiry = expiry.trim() !== "" && Number.isInteger(expirySeconds)
    && expirySeconds >= 0 && expirySeconds <= maxExpirySeconds;
  const projectError = attempted && !project;
  const labelError = attempted && !trimmedLabel;
  const expiryError = attempted && !validExpiry;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending.current) {
      return;
    }
    setAttempted(true);
    setIssueError(null);
    if (!project || !trimmedLabel || !validExpiry) {
      return;
    }
    pending.current = true;
    setSaving(true);
    try {
      const credential = await issueMcpProjectCredential(project.value, {
        label: trimmedLabel,
        scope,
        expiresInSeconds: expirySeconds,
      });
      setIssued({ credential, projectName: project.label });
      onIssued();
    } catch (error) {
      setIssueError(getApiErrorStatus(error) === 404
        ? "签发接口不可用，请确认 MCP 服务已启用且项目仍可访问。"
        : getApiErrorMessage(error));
    } finally {
      pending.current = false;
      setSaving(false);
    }
  }

  if (issued) {
    return (
      <McpCredentialResult
        credential={issued.credential}
        projectName={issued.projectName}
        onDone={() => {
          setIssued(null);
          setAttempted(false);
        }}
      />
    );
  }

  return (
    <form className="min-w-0" noValidate onSubmit={submit}>
      <fieldset disabled={saving} className="grid min-w-0 gap-4 p-1 sm:grid-cols-2">
        <div className="flex min-w-0 flex-col gap-2">
          <Label htmlFor="mcp-project">项目</Label>
          <Select items={projectItems} value={projectId} onValueChange={onProjectChange} disabled={saving}>
            <SelectTrigger id="mcp-project" className="w-full" aria-invalid={projectError}
              aria-describedby={projectError ? "mcp-project-error" : allProjectsSelected ? "mcp-all-projects-help" : undefined}>
              <SelectValue placeholder="选择要授权的项目" />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {projectItems.map((item) => (
                  <SelectItem key={item.value} value={item.value}>{item.label}</SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
          {projectError && <p id="mcp-project-error" role="alert" className="text-xs text-destructive">请选择项目。</p>}
          {allProjectsSelected && (
            <p id="mcp-all-projects-help" className="text-xs text-muted-foreground">
              覆盖当前空间中你有权访问的项目。读写凭证只能修改你有编辑权限的项目。
            </p>
          )}
        </div>

        <div className="flex min-w-0 flex-col gap-2">
          <Label htmlFor="mcp-credential-label">凭证名称</Label>
          <Input id="mcp-credential-label" maxLength={maxLabelLength} value={label}
            onChange={(event) => setLabel(event.target.value)} placeholder="例如第三方接入"
            aria-invalid={labelError} aria-describedby={labelError ? "mcp-label-error" : undefined} />
          {labelError && <p id="mcp-label-error" role="alert" className="text-xs text-destructive">请输入凭证名称。</p>}
        </div>

        <div className="flex min-w-0 flex-col gap-2">
          <Label htmlFor="mcp-credential-scope">访问权限</Label>
          <Select items={scopeItems} value={scope} disabled={saving} onValueChange={(value) => {
            if (value !== null) {
              setScope(value);
            }
          }}>
            <SelectTrigger id="mcp-credential-scope" className="w-full">
              <SelectValue placeholder="选择访问权限" />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {scopeItems.map((item) => (
                  <SelectItem key={item.value} value={item.value}>{item.label}</SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        </div>

        <div className="flex min-w-0 flex-col gap-2">
          <Label htmlFor="mcp-credential-expiry">有效期（秒）</Label>
          <Input id="mcp-credential-expiry" type="number" min={0} max={maxExpirySeconds} step={1} value={expiry}
            onChange={(event) => setExpiry(event.target.value)} aria-invalid={expiryError}
            aria-describedby={expiryError ? "mcp-expiry-error" : "mcp-expiry-help"} />
          {expiryError ? (
            <p id="mcp-expiry-error" role="alert" className="text-xs text-destructive">请输入 0 至 604800 之间的整数。</p>
          ) : (
            <p id="mcp-expiry-help" className="text-xs text-muted-foreground">0 为永久有效，3600 秒为一小时，最长 7 天。</p>
          )}
        </div>

        <div className="flex flex-col items-start gap-2 sm:col-span-2">
          {issueError && <p role="alert" className="text-sm text-destructive">签发失败。{issueError}</p>}
          <Button type="submit" disabled={saving}>
            {saving ? <Loader2 className="animate-spin" /> : <KeyRound />}
            {saving ? "签发中" : "签发凭证"}
          </Button>
        </div>
      </fieldset>
    </form>
  );
}
