"use client";

import { useEffect, useRef } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { IssuedMcpCredential } from "@/lib/api/mcp-project-credentials";
import { formatCredentialExpiry } from "./mcp-credential-format";
import { McpCopyButton } from "./mcp-copy-button";

export function McpCredentialResult({
  credential,
  projectName,
  onDone,
}: {
  credential: IssuedMcpCredential;
  projectName: string;
  onDone: () => void;
}) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    headingRef.current?.focus();
  }, []);

  return (
    <div className="flex min-w-0 flex-col gap-4 p-1">
      <div className="flex flex-col gap-1">
        <h3 ref={headingRef} tabIndex={-1} className="text-base font-semibold">凭证已签发</h3>
        <p className="text-sm break-words text-muted-foreground">
          {projectName} · {credential.scope === "read" ? "只读" : "读写"} · {formatCredentialExpiry(credential.expiresAt)}
        </p>
      </div>
      <div className="flex min-w-0 flex-col gap-2">
        <Label htmlFor="mcp-issued-token">项目凭证</Label>
        <Input id="mcp-issued-token" className="font-mono" value={credential.token}
          aria-describedby="mcp-token-help" readOnly autoComplete="off" spellCheck={false} />
        <p id="mcp-token-help" className="text-xs text-muted-foreground">
          凭证只显示这一次。请复制并通过安全渠道交给第三方。
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <McpCopyButton primary label="复制凭证" value={credential.token} />
        <Button type="button" variant="outline" onClick={onDone}>完成</Button>
      </div>
    </div>
  );
}
