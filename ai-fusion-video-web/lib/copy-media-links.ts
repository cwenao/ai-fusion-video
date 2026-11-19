import { resolveMediaUrl } from "@/lib/api/client";
import { issueMediaCopyLink, mediaId } from "@/lib/api/media";

async function browserMediaLink(source: string): Promise<string> {
  const id = mediaId(source);
  const reference = id ? (await issueMediaCopyLink(id)).url : source;
  const url = reference?.startsWith("//") ? reference : resolveMediaUrl(reference);
  if (!url) {
    throw new Error("媒体地址无效");
  }
  let link: URL;
  try {
    link = new URL(url, url.startsWith("/") ? window.location.origin : undefined);
  } catch {
    throw new Error("媒体地址无效");
  }
  if (link.protocol !== "http:" && link.protocol !== "https:") {
    throw new Error("媒体没有可在浏览器打开的链接");
  }
  return link.href;
}

export async function copyMediaLinks(sources: readonly string[]) {
  const clipboard = navigator.clipboard;
  if (!clipboard?.write || typeof ClipboardItem !== "function") {
    throw new Error("当前页面无法访问剪贴板，请使用支持剪贴板的浏览器并通过 HTTPS 打开");
  }

  const linksBySource = new Map([...new Set(sources)].map((source) => [source, browserMediaLink(source)]));
  const content = Promise.all(sources.map((source) => linksBySource.get(source)!)).then(
    (links) => new Blob([links.join("\n")], { type: "text/plain" }),
  );
  // 在点击的同步阶段发起剪贴板写入，签发媒体链接后再提供文本。
  const item = new ClipboardItem({ "text/plain": content });
  try {
    await Promise.all([content, clipboard.write([item])]);
  } catch (error) {
    if (error instanceof DOMException && error.name === "NotAllowedError") {
      throw new Error("浏览器未允许写入剪贴板，请在当前页面重试", { cause: error });
    }
    throw error;
  }
}
