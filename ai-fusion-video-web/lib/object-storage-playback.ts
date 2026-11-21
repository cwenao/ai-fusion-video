const WORKER_PATH = "/media-playback-worker.js";
const PLAYBACK_PATH = "/_afv/media-playback";
let registration: Promise<void> | undefined;

async function registerPlaybackWorker() {
  if (!window.isSecureContext || !("serviceWorker" in navigator)) {
    throw new Error("请通过 HTTPS 打开融光以播放对象存储媒体");
  }
  const workers = navigator.serviceWorker;
  const scriptUrl = new URL(WORKER_PATH, window.location.origin).href;
  await workers.register(scriptUrl, { scope: "/", type: "module", updateViaCache: "none" });
  await new Promise<void>((resolve, reject) => {
    const finish = () => {
      if (workers.controller?.scriptURL === scriptUrl) {
        window.clearTimeout(timeout);
        workers.removeEventListener("controllerchange", finish);
        resolve();
      }
    };
    const timeout = window.setTimeout(() => {
      workers.removeEventListener("controllerchange", finish);
      reject(new Error("对象存储媒体播放器启动超时"));
    }, 10000);
    workers.addEventListener("controllerchange", finish);
    finish();
  });
}

export async function objectStoragePlaybackUrl(grantUrl: string) {
  registration ??= registerPlaybackWorker().catch((error: unknown) => {
    registration = undefined;
    throw error;
  });
  await registration;
  const grant = new URL(grantUrl, window.location.origin);
  const playback = new URL(PLAYBACK_PATH, window.location.origin);
  playback.searchParams.set("grant", grant.href);
  return playback.href;
}
