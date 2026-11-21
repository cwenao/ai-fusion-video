import assert from "node:assert/strict";
import { createCipheriv, randomBytes } from "node:crypto";
import test from "node:test";
import { streamObjectMedia } from "../public/media-playback-stream.mjs";

const origin = "https://fusion.example";
const playback = new URL("/_afv/media-playback", origin);
const grantUrl = new URL("/api/media-content/12345678-1234-1234-1234-123456789012/playback", origin);
grantUrl.searchParams.set("ticket", "a".repeat(43));
playback.searchParams.set("grant", grantUrl.href);
const storageUrl = "https://objects.example.test/media-bucket/cipher?X-Amz-Signature=test";
const size = 1024 * 1024 + 181;
const firstPayload = 1024 * 1024 - 40;
const plain = randomBytes(size);
const key = randomBytes(16);
const nonce = randomBytes(7);
const ciphertext = [Buffer.alloc(24)];
for (const [index, payload] of [plain.subarray(0, firstPayload), plain.subarray(firstPayload)].entries()) {
  const iv = Buffer.alloc(12);
  nonce.copy(iv);
  iv.writeUInt32BE(index, 7);
  iv[11] = index;
  const cipher = createCipheriv("aes-128-gcm", key, iv);
  ciphertext.push(cipher.update(payload), cipher.final(), cipher.getAuthTag());
}
const encrypted = Buffer.concat(ciphertext);
const grant = {
  url: storageUrl,
  key: key.toString("base64url"),
  noncePrefix: nonce.toString("base64url"),
  size,
  ciphertextSize: encrypted.length,
  mimeType: "video/mp4",
  expiresAt: new Date(Date.now() + 60_000).toISOString(),
};

function mockFetch(t, { denied, incorrectRange, corrupt } = {}) {
  const reads = [];
  t.mock.method(globalThis, "fetch", async (input, options) => {
    if (input.toString() === grantUrl.href) {
      return denied ? new Response(null, { status: 403 }) : Response.json(grant);
    }
    assert.equal(input, storageUrl);
    assert.equal(options.credentials, "omit");
    const [, from, to] = /^bytes=(\d+)-(\d+)$/.exec(options.headers.Range);
    const start = Number(from);
    const end = Number(to);
    reads.push([start, end]);
    const body = Buffer.from(encrypted.subarray(start, end + 1));
    if (corrupt) {
      body[0] ^= 1;
    }
    return new Response(body, {
      status: 206,
      headers: {
        "Content-Range": `bytes ${incorrectRange ? start + 1 : start}-${end}/${encrypted.length}`,
      },
    });
  });
  return reads;
}

test("streaming range across the first segment returns authenticated plaintext in browser offsets", async (t) => {
  const reads = mockFetch(t);
  const start = firstPayload - 7;
  const end = firstPayload + 17;
  const response = await streamObjectMedia(new Request(playback, {
    headers: { Range: `bytes=${start}-${end}` },
  }), origin);
  assert.equal(response.status, 206);
  assert.equal(response.headers.get("Content-Range"), `bytes ${start}-${end}/${size}`);
  assert.deepEqual(Buffer.from(await response.arrayBuffer()), plain.subarray(start, end + 1));
  assert.deepEqual(reads, [[24, encrypted.length - 1]]);
});

test("a suffix seek reads only the final ciphertext segment", async (t) => {
  const reads = mockFetch(t);
  const response = await streamObjectMedia(new Request(playback, {
    headers: { Range: "bytes=-18" },
  }), origin);
  assert.deepEqual(Buffer.from(await response.arrayBuffer()), plain.subarray(-18));
  assert.deepEqual(reads, [[1024 * 1024, encrypted.length - 1]]);
});

test("expired authorization never requests the storage object", async (t) => {
  const reads = mockFetch(t, { denied: true });
  assert.equal((await streamObjectMedia(new Request(playback), origin)).status, 403);
  assert.deepEqual(reads, []);
});

test("mismatched Range response is rejected before any plaintext is returned", async (t) => {
  mockFetch(t, { incorrectRange: true });
  const response = await streamObjectMedia(new Request(playback), origin);
  assert.equal(response.status, 502);
  assert.equal(await response.text(), "");
});

test("a damaged GCM segment is rejected before any plaintext is returned", async (t) => {
  mockFetch(t, { corrupt: true });
  const response = await streamObjectMedia(new Request(playback), origin);
  assert.equal(response.status, 502);
  assert.equal(await response.text(), "");
});
