import type { Metadata } from "next";
import { settingsTypography } from "../_shared";
import { McpConnectionDetails } from "./_components/mcp-connection-details";
import { McpProjectCredentials } from "./_components/mcp-project-credentials";

export const metadata: Metadata = { title: "MCP服务" };

export default function McpServicePage() {
  return (
    <div className="flex min-w-0 flex-col gap-6">
      <h1 className={settingsTypography.pageTitle}>MCP服务</h1>
      <McpConnectionDetails />
      <McpProjectCredentials />
    </div>
  );
}
