"use client";

import { useSyncExternalStore } from "react";
import { Input } from "@/components/ui/input";
import { getApiBaseUrl } from "@/lib/runtime-config";
import { settingsTypography } from "../../_shared";
import { McpCopyButton } from "./mcp-copy-button";

const subscribe = () => () => {};
const readServiceUrl = () => `${getApiBaseUrl() || window.location.origin}/api/mcp`;
const readServerSnapshot = () => "";

export function McpConnectionDetails() {
  const serviceUrl = useSyncExternalStore(subscribe, readServiceUrl, readServerSnapshot);

  return (
    <section aria-labelledby="mcp-address-title" className="flex min-w-0 flex-col gap-2 px-1">
      <div>
        <h2 id="mcp-address-title" className={settingsTypography.sectionTitle}>服务地址</h2>
        <p className={settingsTypography.sectionDescription}>
          第三方客户端使用 Streamable HTTP，认证方式选择 Bearer，并填入项目凭证。
        </p>
      </div>
      <div className="flex min-w-0 items-center gap-2">
        <Input aria-labelledby="mcp-address-title" className="font-mono" value={serviceUrl} readOnly />
        <McpCopyButton label="复制地址" value={serviceUrl} />
      </div>
    </section>
  );
}
