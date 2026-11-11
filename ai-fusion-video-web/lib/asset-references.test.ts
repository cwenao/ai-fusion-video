import assert from "node:assert/strict";
import test from "node:test";
import type { AssetItem, AssetWithItems } from "./api/asset";
import type { AiModel } from "./api/ai-model";
import { assetReferences, assetReferenceHref, parseAssetReferenceIdentity, resolveAssetReferenceIdentity, type AssetReference } from "./asset-references";
import { resolveGenerationCapabilities } from "./generation-capabilities";
import { readPendingGeneration } from "./generation-submission";
import { appendAssetReferences } from "../components/dashboard/generation/generation-asset-inputs";
import { generationSubmitRequest } from "../components/dashboard/generation/generation-submit-request";
import { EMPTY_GENERATION_FORM } from "../components/dashboard/generation/generation-types";

const image = "media://11111111-1111-4111-8111-111111111111";
const video = "media://22222222-2222-4222-8222-222222222222";
const audio = "media://33333333-3333-4333-8333-333333333333";
const poster = "media://44444444-4444-4444-8444-444444444444";
const item = (patch: Partial<AssetItem>): AssetItem => ({ id: 20, version: 4, assetId: 10, assetVersion: 5, name: "动作素材", imageUrl: image, thumbnailUrl: poster, properties: null, itemType: null, sortOrder: 0, sourceType: 2, aiPrompt: null, createTime: "", updateTime: "", ...patch });
const asset = (items: AssetItem[], patch: Partial<AssetWithItems> = {}): AssetWithItems => ({ id: 10, version: 5, userId: 1, projectId: 7, type: "scene", name: "场景", description: null, coverUrl: null, properties: null, tags: null, sourceType: 2, aiPrompt: null, ownerType: 1, ownerId: 1, status: 1, createTime: "", updateTime: "", items, ...patch });
const capability = (modelType: number, patch: Record<string, unknown> = {}) => {
  const model: AiModel = {
    id: 7,
    name: "Asset reference test",
    code: "asset-reference-test",
    modelProtocol: null,
    capabilityPresetCode: null,
    modelType,
    icon: null,
    description: null,
    sort: 0,
    status: 1,
    config: null,
    generationConfig: {
      supportReferenceImages: true,
      maxReferenceImages: 6,
      maxImageInputs: 6,
      supportReferenceVideos: true,
      maxReferenceVideos: 2,
      supportReferenceAudios: true,
      maxReferenceAudios: 2,
      referenceImageInputFormats: ["data_uri"],
      ...patch,
    },
    maxConcurrency: 1,
    defaultModel: false,
    supportVision: false,
    multimodalInputTypes: [],
    multimodalInputTransports: {},
    supportReasoning: false,
    reasoningEffortLevels: [],
    contextWindow: null,
    apiConfigId: null,
    comfyuiWorkflowId: null,
    createTime: "",
    updateTime: "",
  };
  return resolveGenerationCapabilities(model, []);
};
const refs = () => assetReferences(asset([item({}), item({ id: 21, itemType: "generated_video", imageUrl: poster, properties: { sourceGenerationMode: "video", sourceMediaUrl: video } }), item({ id: 22, imageUrl: null, properties: { audioUrl: audio } })]));

// Protect source-field selection: generated videos must never become posters, and edited images use their saved imageUrl.
test("asset references retain original media and explicitly typed video/audio fields", () => {
  const actual = refs();
  assert.deepEqual(actual.map((entry) => [entry.mediaType, entry.reference]), [["image", image], ["video", video], ["audio", audio]]);
  assert.equal(actual[1].previewUrl, poster);
  assert.equal(actual[1].sourceField, "properties.sourceMediaUrl");
  assert.equal(actual.some((entry) => entry.reference === poster), false);
  assert.deepEqual(assetReferences(asset([item({ imageUrl: null, thumbnailUrl: poster })])), []);
});

test("a video without original or explicit mode is unavailable rather than substituted with its poster", () => {
  assert.deepEqual(assetReferences(asset([item({ itemType: "generated_video", imageUrl: poster, properties: {} })])), []);
  assert.deepEqual(assetReferences(asset([item({ itemType: "generated_video", imageUrl: poster, properties: { sourceMediaUrl: video } })])), []);
});

test("parent and child media sources remain individually addressable without signed URLs", () => {
  const parent = asset([], { properties: { videoUrl: video, audioUrl: audio } });
  const sources = assetReferences(parent);
  assert.deepEqual(sources.map((source) => source.sourceField), ["properties.videoUrl", "properties.audioUrl"]);
  const href = assetReferenceHref("video", sources[1]);
  const identity = parseAssetReferenceIdentity(new URL(href, "https://example.test").searchParams)!;
  assert.equal(resolveAssetReferenceIdentity(parent, identity).reference, audio);
  assert.equal(href.includes("media%3A"), false);
  assert.equal(href.includes("url="), false);
});

// URL is a command identity, not trusted media/project/asset content.
test("handoff rejects malformed, foreign-parent, wrong-project and stale source identities", () => {
  const current = asset([item({})]);
  const source = assetReferences(current)[0];
  const identity = parseAssetReferenceIdentity(new URL(assetReferenceHref("image", source), "https://example.test").searchParams)!;
  assert.equal(resolveAssetReferenceIdentity(current, identity).reference, image);
  assert.throws(() => resolveAssetReferenceIdentity(current, { ...identity, projectId: 8 }), /项目/);
  assert.throws(() => resolveAssetReferenceIdentity(current, { ...identity, assetVersion: 4 }), /版本/);
  assert.throws(() => resolveAssetReferenceIdentity(current, { ...identity, itemVersion: 3 }), /版本/);
  assert.throws(() => resolveAssetReferenceIdentity(asset([item({ assetId: 999 })]), identity), /不属于/);
  assert.throws(() => parseAssetReferenceIdentity(new URLSearchParams("assetId=-1&assetVersion=1&sourceField=imageUrl")), /参数/);
  assert.throws(() => parseAssetReferenceIdentity(new URLSearchParams("assetId=10&assetVersion=5&itemId=20&sourceField=imageUrl")), /参数/);
  assert.throws(() => parseAssetReferenceIdentity(new URLSearchParams("assetId=10&assetVersion=5&sourceField=thumbnailUrl")), /字段/);
  assert.equal(parseAssetReferenceIdentity(new URLSearchParams()), null);
  const initial = asset([item({ version: 0 })], { version: 0 });
  const zero = parseAssetReferenceIdentity(new URL(assetReferenceHref("image", assetReferences(initial)[0]), "https://example.test").searchParams)!;
  assert.equal(resolveAssetReferenceIdentity(initial, zero).itemVersion, 0);
});

test("selection preserves existing inputs and ordered originals through actual request and pending retry", () => {
  const sources = refs();
  const secondImage = { ...sources[0], key: "second", reference: "media://55555555-5555-4555-8555-555555555555" };
  const ordered = [secondImage, sources[1], sources[0], sources[2]];
  const caps = capability(3);
  const form = appendAssetReferences({ ...EMPTY_GENERATION_FORM, prompt: "test", ratio: "16:9", resolution: "720p" }, caps, "video", ordered);
  assert.deepEqual(form.referenceImages, [secondImage.reference, image]);
  assert.deepEqual(form.referenceVideos, [video]);
  assert.deepEqual(form.referenceAudios, [audio]);
  const input = generationSubmitRequest("video", form, 7, caps, 7);
  assert.equal(input.projectId, 7);
  assert.equal("referenceVideoUrls" in input && input.referenceVideoUrls, JSON.stringify([video]));
  assert.equal("referenceImageUrls" in input && input.referenceImageUrls, JSON.stringify([secondImage.reference, image]));
  const scope = { userId: 1, teamId: null };
  const record = { schemaVersion: 1, mode: "video", modelName: "H3", createdAt: new Date().toISOString(), request: { ...input, scope, requestId: "asset-reference-test" } };
  assert.equal(readPendingGeneration(JSON.stringify(record), "video", scope)?.request.projectId, 7);
  const imageInput = generationSubmitRequest("image", { ...form, referenceImages: [image] }, 6, capability(2), 7);
  assert.equal(imageInput.projectId, 7);
  assert.equal("refImageUrls" in imageInput && imageInput.refImageUrls, JSON.stringify([image]));
});

// Limits must reject the entire attempted selection, not slice away sources or overwrite an existing frame.
test("selection rejects overflow, duplicate references, unsupported kinds and mixed projects atomically", () => {
  const sources = refs();
  const original = { ...EMPTY_GENERATION_FORM, referenceImages: [image] };
  const another = { ...sources[0], reference: poster };
  assert.throws(() => appendAssetReferences(original, capability(2, { maxReferenceImages: 1 }), "image", [another]), /上限/);
  assert.deepEqual(original.referenceImages, [image]);
  assert.throws(() => appendAssetReferences(original, capability(2), "image", [sources[0]]), /重复/);
  assert.throws(() => appendAssetReferences(EMPTY_GENERATION_FORM, capability(2), "image", [sources[1]]), /视频/);
  assert.throws(() => appendAssetReferences(EMPTY_GENERATION_FORM, capability(3), "video", [sources[0], { ...sources[1], projectId: 9 }]), /同一项目/);
  assert.throws(() => appendAssetReferences(EMPTY_GENERATION_FORM, capability(3), "video", [sources[1], { ...sources[1], reference: image }, { ...sources[1], reference: poster }]), /视频数量/);
  assert.throws(() => appendAssetReferences(EMPTY_GENERATION_FORM, capability(3), "video", [sources[0]], { supported: false, reason: "未配置公网访问" }), /公网访问/);
});

test("frame-only models allocate ordered images without replacing frames and enforce shared count", () => {
  const first = refs()[0];
  const second: AssetReference = { ...first, key: "second", reference: poster };
  const caps = capability(3, { supportReferenceImages: false, supportFirstFrame: true, supportLastFrame: true, maxImageInputs: 2 });
  const form = appendAssetReferences(EMPTY_GENERATION_FORM, caps, "video", [second, first]);
  assert.deepEqual(form.firstFrame, [poster]);
  assert.deepEqual(form.lastFrame, [image]);
  assert.throws(() => appendAssetReferences(form, caps, "video", [{ ...first, reference: video }]), /输入位置/);
  assert.throws(() => appendAssetReferences({ ...EMPTY_GENERATION_FORM, firstFrame: [poster] }, capability(3, { maxImageInputs: 1 }), "video", [first]), /总数/);
});

// A card routes to its own field even when the model has multiple image inputs.
test("card selections append to the chosen reference or frame field", () => {
  const [picture, clip, sound] = refs();
  const secondPicture = { ...picture, key: "second", reference: poster };
  const caps = capability(3, { supportFirstFrame: true, supportLastFrame: true });
  const withLastFrame = appendAssetReferences(EMPTY_GENERATION_FORM, caps, "video", [secondPicture], undefined, "lastFrame");
  assert.deepEqual(withLastFrame.firstFrame, []);
  assert.deepEqual(withLastFrame.lastFrame, [poster]);
  assert.deepEqual(withLastFrame.referenceImages, []);

  const withPicture = appendAssetReferences(withLastFrame, caps, "video", [picture], undefined, "referenceImages");
  const withClip = appendAssetReferences(withPicture, caps, "video", [clip], undefined, "referenceVideos");
  const result = appendAssetReferences(withClip, caps, "video", [sound], undefined, "referenceAudios");
  assert.deepEqual(result.lastFrame, [poster]);
  assert.deepEqual(result.referenceImages, [image]);
  assert.deepEqual(result.referenceVideos, [video]);
  assert.deepEqual(result.referenceAudios, [audio]);
});

test("card selections reject wrong types, full fields and incompatible modes without rerouting", () => {
  const [picture, clip] = refs();
  const caps = capability(3, { supportFirstFrame: true, supportLastFrame: true, exclusiveInputModes: true });
  const form = appendAssetReferences(EMPTY_GENERATION_FORM, caps, "video", [{ ...picture, reference: poster }], undefined, "firstFrame");
  assert.throws(() => appendAssetReferences(form, caps, "video", [clip], undefined, "referenceImages"), /类型/);
  assert.throws(() => appendAssetReferences(form, caps, "video", [picture], undefined, "firstFrame"), /上限/);
  assert.throws(() => appendAssetReferences(form, caps, "video", [picture], undefined, "referenceImages"), /不能混用/);
  assert.deepEqual(form.firstFrame, [poster]);
  assert.deepEqual(form.lastFrame, []);
  assert.deepEqual(form.referenceImages, []);
});
