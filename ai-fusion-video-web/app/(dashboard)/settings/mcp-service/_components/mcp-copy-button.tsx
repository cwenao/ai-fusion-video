"use client";

import { Copy } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";

export function McpCopyButton({
  value,
  label,
  primary = false,
}: {
  value: string;
  label: string;
  primary?: boolean;
}) {
  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      toast.success("已复制");
    } catch {
      toast.error("无法访问剪贴板，请选中文本手动复制");
    }
  }

  return (
    <Button type="button" variant={primary ? "default" : "outline"} disabled={!value} onClick={copy}>
      <Copy />
      {label}
    </Button>
  );
}
