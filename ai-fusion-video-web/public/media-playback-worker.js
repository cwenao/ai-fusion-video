import { streamObjectMedia } from "./media-playback-stream.mjs";

self.addEventListener("install", (event) => {
  event.waitUntil(self.skipWaiting());
});

self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin || url.pathname !== "/_afv/media-playback") {
    return;
  }
  event.respondWith(streamObjectMedia(event.request, self.location.origin));
});
