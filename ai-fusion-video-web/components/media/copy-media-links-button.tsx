"use client";

import { useState, type ComponentProps, type MouseEvent, type ReactNode } from "react";
import { Copy, Loader2 } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { toastApiError } from "@/lib/api/toast-api-error";
import { copyMediaLinks } from "@/lib/copy-media-links";

interface CopyMediaLinksButtonProps {
  sources: readonly string[];
  label: string;
  size?: ComponentProps<typeof Button>["size"];
  variant?: "ghost" | "secondary" | "outline";
  children?: ReactNode;
}

export function CopyMediaLinksButton({
  sources,
  label,
  size = "icon-xs",
  variant = "ghost",
  children,
}: CopyMediaLinksButtonProps) {
  const [copying, setCopying] = useState(false);

  async function handleCopy(event: MouseEvent<HTMLButtonElement>) {
    event.stopPropagation();
    setCopying(true);
    try {
      await copyMediaLinks(sources);
      toast.success(sources.length === 1 ? "已复制链接" : `已复制 ${sources.length} 个链接`);
    } catch (error) {
      toastApiError(error, "复制链接失败");
    } finally {
      setCopying(false);
    }
  }

  return (
    <Button
      type="button"
      variant={variant}
      size={size}
      disabled={copying || sources.length === 0}
      aria-busy={copying || undefined}
      aria-label={label}
      title={sources.length ? label : "暂无可复制的链接"}
      onClick={handleCopy}
    >
      {copying ? <Loader2 className="animate-spin motion-reduce:animate-none" /> : <Copy />}
      {children}
    </Button>
  );
}
