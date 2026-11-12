const SEGMENT_SIZE = 1024 * 1024;
const HEADER_SIZE = 24;
const TAG_SIZE = 16;
const PLAINTEXT_SEGMENT = SEGMENT_SIZE - TAG_SIZE;
const FIRST_SEGMENT = PLAINTEXT_SEGMENT - HEADER_SIZE;
const MAX_SEGMENTS = 2 ** 32;

const privateResponse = {
  "Cache-Control": "private, no-store",
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff",
  "Content-Security-Policy": "sandbox",
};

function parseRange(value, size) {
  if (value === null) {
    return { start: 0, end: size - 1, partial: false };
  }
  const match = /^bytes=(\d*)-(\d*)$/.exec(value);
  if (!match || (!match[1] && !match[2])) {
    return null;
  }
  const [, from, to] = match;
  const start = from ? Number(from) : Math.max(0, size - Number(to));
  const end = from && to ? Math.min(Number(to), size - 1) : size - 1;
  if ((from && !Number.isSafeInteger(Number(from))) || (to && !Number.isSafeInteger(Number(to)))
    || start > end || start >= size) {
    return null;
  }
  return { start, end, partial: true };
}

function segmentAt(plaintextOffset) {
  return Math.floor((plaintextOffset + HEADER_SIZE) / PLAINTEXT_SEGMENT);
}

function decodeBase64Url(value) {
  const data = atob(value.replaceAll("-", "+").replaceAll("_", "/"));
  return Uint8Array.from(data, (char) => char.charCodeAt(0));
}

async function loadGrant(request, origin) {
  const url = new URL(request.url);
  const grant = new URL(url.searchParams.get("grant"));
  if ((grant.origin !== origin && grant.protocol !== "https:") || grant.username || grant.password
    || !/^\/api\/media-content\/[0-9a-f-]{36}\/playback$/.test(grant.pathname)
    || !/^[A-Za-z0-9_-]{43}$/.test(grant.searchParams.get("ticket") ?? "")) {
    throw new Error("媒体授权链接无效");
  }
  return fetch(grant, {
    cache: "no-store",
    credentials: "omit",
    redirect: "error",
    referrerPolicy: "no-referrer",
    signal: request.signal,
  });
}

function validateGrant(grant) {
  const { size, ciphertextSize, mimeType } = grant;
  const segments = Math.ceil((size + HEADER_SIZE) / PLAINTEXT_SEGMENT);
  const source = new URL(grant.url);
  if (!Number.isSafeInteger(size) || size <= 0 || segments > MAX_SEGMENTS
    || !Number.isSafeInteger(ciphertextSize) || ciphertextSize !== size + HEADER_SIZE + segments * TAG_SIZE
    || !/^(image|video|audio)\/[a-z0-9.+-]+$/i.test(mimeType) || mimeType === "image/svg+xml"
    || source.protocol !== "https:" || source.username || source.password
    || !(Date.parse(grant.expiresAt) > Date.now())) {
    throw new Error("TOS 媒体授权无效");
  }
  return segments;
}

function blockReader(reader) {
  let pending = new Uint8Array();
  return async (length) => {
    const bytes = new Uint8Array(length);
    let offset = 0;
    while (offset < length) {
      if (pending.length === 0) {
        const { value, done } = await reader.read();
        if (done) {
          throw new Error("TOS 媒体分段不完整");
        }
        pending = value;
      }
      const count = Math.min(length - offset, pending.length);
      bytes.set(pending.subarray(0, count), offset);
      pending = pending.subarray(count);
      offset += count;
    }
    return bytes;
  };
}

async function openTosStream(request, grant, segments, range) {
  const { ciphertextSize, url } = grant;
  const first = segmentAt(range.start);
  const last = segmentAt(range.end);
  const start = first === 0 ? HEADER_SIZE : first * SEGMENT_SIZE;
  const end = Math.min((last + 1) * SEGMENT_SIZE, ciphertextSize) - 1;
  const nonce = decodeBase64Url(grant.noncePrefix);
  const keyBytes = decodeBase64Url(grant.key);
  if (nonce.length !== 7 || keyBytes.length !== 16) {
    throw new Error("TOS 媒体解密参数无效");
  }
  let key;
  try {
    key = await crypto.subtle.importKey("raw", keyBytes, "AES-GCM", false, ["decrypt"]);
  } finally {
    keyBytes.fill(0);
  }
  const upstream = await fetch(url, {
    headers: { Range: `bytes=${start}-${end}` },
    cache: "no-store",
    credentials: "omit",
    redirect: "error",
    referrerPolicy: "no-referrer",
    signal: request.signal,
  });
  if (upstream.status !== 206 || !upstream.body
    || upstream.headers.get("Content-Range") !== `bytes ${start}-${end}/${ciphertextSize}`) {
    await upstream.body?.cancel();
    throw new Error("TOS 没有返回所请求的媒体分段");
  }
  const reader = upstream.body.getReader();
  const read = blockReader(reader);
  let segment = first;
  const next = async () => {
    const index = segment++;
    const encryptedStart = index === 0 ? HEADER_SIZE : index * SEGMENT_SIZE;
    const encryptedEnd = Math.min((index + 1) * SEGMENT_SIZE, ciphertextSize);
    const iv = new Uint8Array(12);
    iv.set(nonce);
    new DataView(iv.buffer).setUint32(7, index);
    iv[11] = index === segments - 1 ? 1 : 0;
    const encrypted = await read(encryptedEnd - encryptedStart);
    const plaintext = new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv }, key, encrypted));
    const offset = index === 0 ? 0 : FIRST_SEGMENT + (index - 1) * PLAINTEXT_SEGMENT;
    return plaintext.subarray(Math.max(0, range.start - offset), Math.min(plaintext.length, range.end - offset + 1));
  };

  let initial;
  try {
    initial = await next();
  } catch (error) {
    await reader.cancel();
    throw error;
  }
  return new ReadableStream({
    async pull(controller) {
      try {
        if (initial) {
          controller.enqueue(initial);
          initial = undefined;
        } else {
          controller.enqueue(await next());
        }
        if (segment > last) {
          controller.close();
          await reader.cancel();
        }
      } catch (error) {
        controller.error(error);
        await reader.cancel();
      }
    },
    cancel(reason) {
      return reader.cancel(reason);
    },
  });
}

export async function streamTosMedia(request, origin) {
  if (request.mode === "navigate" || !["GET", "HEAD"].includes(request.method)) {
    return new Response(null, { status: 404, headers: privateResponse });
  }
  try {
    const authorized = await loadGrant(request, origin);
    if (!authorized.ok) {
      await authorized.body?.cancel();
      return new Response(null, { status: authorized.status, headers: privateResponse });
    }
    const grant = await authorized.json();
    const segments = validateGrant(grant);
    const range = parseRange(request.headers.get("Range"), grant.size);
    if (!range) {
      return new Response(null, {
        status: 416,
        headers: { ...privateResponse, "Content-Range": `bytes */${grant.size}` },
      });
    }
    const headers = {
      ...privateResponse,
      "Content-Type": grant.mimeType,
      "Content-Length": String(range.end - range.start + 1),
      "Accept-Ranges": "bytes",
    };
    if (range.partial) {
      headers["Content-Range"] = `bytes ${range.start}-${range.end}/${grant.size}`;
    }
    const body = request.method === "HEAD" ? null : await openTosStream(request, grant, segments, range);
    return new Response(body, { status: range.partial ? 206 : 200, headers });
  } catch {
    return new Response(null, { status: 502, headers: privateResponse });
  }
}
