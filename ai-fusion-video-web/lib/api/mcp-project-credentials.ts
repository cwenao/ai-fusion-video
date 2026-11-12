import { http } from "./client";

export type McpCredentialScope = "read" | "read_write";

export interface McpCredentialIssue {
  label: string;
  scope: McpCredentialScope;
  expiresInSeconds: number;
}

export interface IssuedMcpCredential {
  credentialId: string;
  projectId: string;
  scope: McpCredentialScope;
  expiresAt: string | null;
  token: string;
}

export interface McpCredentialMetadata {
  credentialId: string;
  projectId: string;
  label: string;
  scope: McpCredentialScope;
  createdAt: string;
  expiresAt: string | null;
  revokedAt: string | null;
}

function credentialPath(projectTarget: string, credentialId?: string) {
  const path = `/api/project/${projectTarget}/mcp-credentials`;
  return credentialId === undefined ? path : `${path}/${credentialId}`;
}

export function issueMcpProjectCredential(projectTarget: string, request: McpCredentialIssue) {
  return http.post<never, IssuedMcpCredential>(credentialPath(projectTarget), request);
}

export function listMcpProjectCredentials(projectTarget: string, signal: AbortSignal) {
  return http.get<never, McpCredentialMetadata[]>(credentialPath(projectTarget), { signal });
}

export function renameMcpProjectCredential(projectTarget: string, credentialId: string, label: string) {
  return http.patch<never, boolean>(credentialPath(projectTarget, credentialId), { label });
}

export function deleteMcpProjectCredential(projectTarget: string, credentialId: string) {
  return http.delete<never, boolean>(credentialPath(projectTarget, credentialId));
}
