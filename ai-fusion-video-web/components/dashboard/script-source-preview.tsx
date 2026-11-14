"use client";

import { XMarkdown } from "@ant-design/x-markdown";
import { useTheme } from "next-themes";

const MARKDOWN_CONFIG = { gfm: true, breaks: true };
const SANITIZE_CONFIG = { FORBID_TAGS: ["img"], FORBID_ATTR: ["style"] };

export function ScriptSourcePreview({ content }: { content: string }) {
  const { resolvedTheme } = useTheme();

  return (
    <XMarkdown
      content={content}
      config={MARKDOWN_CONFIG}
      dompurifyConfig={SANITIZE_CONFIG}
      escapeRawHtml
      openLinksInNewTab
      rootClassName={resolvedTheme === "dark" ? "x-markdown-dark" : "x-markdown-light"}
      className="min-w-0 wrap-anywhere [--heading-color:var(--foreground)] [--text-color:var(--foreground)] [--margin-block:0_0_0.75rem_0] [&>:last-child]:mb-0 [&_pre]:overflow-x-auto [&_table]:max-w-full"
    />
  );
}
