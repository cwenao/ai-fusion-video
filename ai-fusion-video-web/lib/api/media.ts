import { http, resolveMediaUrl } from "./client";
import type { StoryboardItem } from "./storyboard";

export interface ManagedMedia {
  id: string;
  projectId: number | null;
  name: string;
  version: number;
  state: "STAGING" | "READY" | "FAILED" | "DELETED";
  content: {
    kind: "IMAGE" | "VIDEO" | "AUDIO" | "TEXT" | "DOCUMENT";
    mimeType: string;
    byteSize: number;
    width: number | null;
    height: number | null;
    durationMs: number | null;
    hasAudio: boolean;
  };
}
export interface MediaAccess {
  resourceId: string;
  url: string;
  expiresAt: string;
  download: boolean;
  playbackUrl?: string;
}

export function mediaId(reference: string | null | undefined): string | null {
  if (!reference?.toLowerCase().startsWith("media://")) return null;
  const match =
    /^media:\/\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/.exec(
      reference,
    );
  if (!match) throw new Error("媒体引用格式无效");
  return match[1];
}

export function accessMedia(
  id: string,
  download = false,
  signal?: AbortSignal,
) {
  return http.post<never, MediaAccess>(
    `/api/media-resources/${id}/access`,
    null,
    { params: { download }, signal },
  );
}

export function issueMediaCopyLink(id: string) {
  return http.post<never, Pick<MediaAccess, "url" | "expiresAt">>(
    `/api/media-resources/${id}/copy-link`,
    null,
    { timeout: 240_000 },
  );
}

export async function mediaAccessUrl(
  source: string,
  download = false,
): Promise<string> {
  const id = mediaId(source);
  if (!id) return resolveMediaUrl(source) ?? "";
  return resolveMediaUrl((await accessMedia(id, download)).url) ?? "";
}

export async function downloadMedia(source: string, name?: string) {
  const url = await mediaAccessUrl(source, true);
  const link = document.createElement("a");
  link.href = url;
  link.download = name ?? "";
  link.rel = "noopener";
  link.click();
}

export async function uploadMedia(
  file: File,
  options: {
    requestId: string;
    projectId?: number;
    canvasId?: string;
    signal?: AbortSignal;
  },
) {
  const data = new FormData();
  data.append("file", file);
  data.append("requestId", options.requestId);
  if (options.projectId !== undefined)
    data.append("projectId", String(options.projectId));
  if (options.canvasId !== undefined) data.append("canvasId", options.canvasId);
  return http.post<never, ManagedMedia>("/api/media-resources/upload", data, {
    timeout: 0,
    signal: options.signal,
  });
}

export function getMediaResource(reference: string): Promise<ManagedMedia> {
  const id = mediaId(reference);
  if (!id) throw new Error("不是已登记的媒体引用");
  return http.get(`/api/media-resources/${id}`);
}

export function bindShotVideo(shotId: number, version: number, videoId: string, posterId: string | null, requestId: string) {
  return http.post<never, { result: StoryboardItem }>("/api/media-bindings", {
    resourceType: "shot",
    resourceId: shotId,
    expectedVersion: version,
    requestId,
    bindings: { generatedVideoUrl: videoId, "customData.videoPosterUrl": posterId },
  });
}

export function listManagedMedia(
  projectId: number | null,
  page = 1,
  signal?: AbortSignal,
): Promise<{
  items: ManagedMedia[];
  page: number;
  pageSize: number;
  hasMore: boolean;
}> {
  return http.get("/api/media-resources", {
    params: { projectId: projectId ?? undefined, page, pageSize: 40 },
    signal,
  });
}
