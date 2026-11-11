import assert from "node:assert/strict";
import test from "node:test";
import type { ComfyUiInputBinding, ComfyUiInputBindings } from "./api/comfyui-workflow";
import {
  getComfyUiTestFields,
  getComfyUiTestInputs,
  validateComfyUiTestUpload,
} from "./comfyui-test-media";
import {
  flattenInputBindings,
  getComfyUiBusinessFields,
  groupInputBindings,
} from "../app/(dashboard)/settings/ai-models/_components/comfyui-workflow-support";

function mediaBindings(type: ComfyUiInputBinding["valueType"], count: number, required: number) {
  return Array.from({ length: count }, (_, index) => ({
    nodeId: "82",
    inputName: `media_${index}`,
    valueType: type,
    index,
    ...(index >= required ? { optional: true } : {}),
  }));
}

const minimaxBindings: ComfyUiInputBindings = {
  referenceImages: mediaBindings("uploaded_image", 6, 1),
  referenceVideos: mediaBindings("uploaded_video", 2, 0),
  referenceAudios: mediaBindings("uploaded_audio", 2, 0),
};
const fields = getComfyUiTestFields(minimaxBindings);

// 防止新增 selector 业务字段从配置器丢失，或试运行把数值/布尔变为字符串。
test("exposes image and video selector fields and preserves typed trial parameters", () => {
  for (const modelType of [2, 3] as const) {
    const names = getComfyUiBusinessFields(modelType);
    for (const name of ["aspectRatio", "megapixels", "resolutionMultiple"]) {
      assert.ok(names.includes(name), `${modelType} missing ${name}`);
    }
  }
  assert.ok(getComfyUiBusinessFields(3).includes("upscaleMegapixels"));
  assert.ok(!getComfyUiBusinessFields(2).includes("upscaleMegapixels"));
  const selector = getComfyUiTestFields({
    aspectRatio: [{ nodeId: "681", inputName: "aspect_ratio", valueType: "string" }],
    megapixels: [{ nodeId: "681", inputName: "megapixels", valueType: "number" }],
    resolutionMultiple: [{ nodeId: "681", inputName: "multiple", valueType: "integer" }],
    upscaleMegapixels: [{ nodeId: "62", inputName: "value", valueType: "number" }],
    generateAudio: [{ nodeId: "82", inputName: "generate_audio", valueType: "boolean" }],
  });
  assert.deepEqual(getComfyUiTestInputs(selector, {
    aspectRatio: "16:9", megapixels: "1.5", resolutionMultiple: "32",
    upscaleMegapixels: "4.0", generateAudio: "false",
  }), {
    aspectRatio: "16:9", megapixels: 1.5, resolutionMultiple: 32,
    upscaleMegapixels: 4, generateAudio: false,
  });
  assert.deepEqual(getComfyUiTestInputs(selector, {}), {});
  assert.throws(() => getComfyUiTestInputs(selector, { resolutionMultiple: "32.5" }), /整数/);
  assert.throws(() => getComfyUiTestInputs(selector, { megapixels: "NaN" }), /数值/);
});

// 保存/再次编辑不得把 optional 元数据丢掉，否则 5 张可选图与视频/音频会变为必填。
test("preserves optional media flags through binding editing", () => {
  assert.deepEqual(groupInputBindings(flattenInputBindings(minimaxBindings)), minimaxBindings);
  assert.deepEqual(fields.map((field) => [field.name, field.requiredCount, field.maximumCount]), [
    ["referenceImages", 1, 6], ["referenceVideos", 0, 2], ["referenceAudios", 0, 2],
  ]);
});

// 首张 indexed 图不能复用工作流占位图；其他可选素材留空时不提交该业务字段。
test("requires the first image but permits absent optional media", () => {
  assert.throws(() => getComfyUiTestInputs(fields, {}), /参考图片.*第 1 项必填/);
  assert.throws(() => getComfyUiTestInputs(fields, { referenceImages: " \n " }), /必填/);
  assert.deepEqual(getComfyUiTestInputs(fields, { referenceImages: "media://image-1" }), {
    referenceImages: ["media://image-1"],
  });
  const required = getComfyUiTestFields({
    referenceImages: mediaBindings("uploaded_image", 3, 3),
  });
  assert.throws(() => getComfyUiTestInputs(required, { referenceImages: "media://image-1" }), /前 3 项/);
});

// 媒体按行切分，不能按逗号切断 Data URI/签名 URL；视频音频必须可提交。
test("submits all supported media kinds without corrupting references", () => {
  const image = "data:image/png;base64,YQ==";
  const video = "https://example.com/video.mp4?ids=1,2";
  const audio = "data:audio/wav;base64,Yg==";
  assert.deepEqual(getComfyUiTestInputs(fields, {
    referenceImages: image, referenceVideos: video + "\r\nmedia://video-2",
    referenceAudios: audio,
  }), {
    referenceImages: [image], referenceVideos: [video, "media://video-2"], referenceAudios: [audio],
  });
  assert.throws(() => getComfyUiTestInputs(fields, {
    referenceImages: image, referenceVideos: "one\ntwo\nthree",
  }), /参考视频最多支持 2 项/);
  const sparse = getComfyUiTestFields({
    referenceImages: [minimaxBindings.referenceImages[0], minimaxBindings.referenceImages[2]],
  });
  assert.throws(() => getComfyUiTestInputs(sparse, { referenceImages: "one\ntwo" }), /第 2 项未绑定/);
});

// 上传前必须校验对应 MIME，并严格限制图片20MB、视频200MB、音频50MB。
test("accepts exact upload limits and rejects wrong media kinds or oversized files", () => {
  const cases = [
    { name: "referenceImages", mime: "image/png", limit: 20 },
    { name: "referenceVideos", mime: "video/mp4", limit: 200 },
    { name: "referenceAudios", mime: "audio/wav", limit: 50 },
  ];
  for (const item of cases) {
    const field = fields.find((candidate) => candidate.name === item.name)!;
    const maxSize = item.limit * 1024 * 1024;
    assert.doesNotThrow(() => validateComfyUiTestUpload(field, "", [
      { type: item.mime, size: maxSize },
    ]));
    assert.throws(() => validateComfyUiTestUpload(field, "", [
      { type: item.mime, size: maxSize + 1 },
    ]), /请上传不超过/);
    assert.throws(() => validateComfyUiTestUpload(field, "", [
      { type: item.mime.startsWith("video/") ? "audio/wav" : "video/mp4", size: 1 },
    ]), /文件/);
  }
});

// 多次上传必须合计已填数量，而不是每次允许再上传完整上限；混合批次提前拒绝。
test("enforces cumulative upload capacity before making storage requests", () => {
  const image = fields[0];
  const files = Array.from({ length: 6 }, () => ({ type: "image/png", size: 1 }));
  assert.doesNotThrow(() => validateComfyUiTestUpload(image, "", files));
  assert.throws(() => validateComfyUiTestUpload(image, "media://existing", files), /最多支持 6 项/);
  assert.throws(() => validateComfyUiTestUpload(image, "", [
    files[0], { type: "audio/wav", size: 1 },
  ]), /图片文件/);
  const scalar = getComfyUiTestFields({
    firstFrame: [{ nodeId: "1", inputName: "image", valueType: "uploaded_image" }],
  })[0];
  assert.equal(scalar.maximumCount, 1);
  assert.deepEqual(getComfyUiTestInputs([scalar], {}), {});
  assert.throws(() => validateComfyUiTestUpload(scalar, "media://existing", [files[0]]), /最多支持 1 项/);
});

test("rejects inconsistent binding types rather than hiding a multimodal field", () => {
  assert.throws(() => getComfyUiTestFields({
    referenceVideos: [
      { nodeId: "1", inputName: "video", valueType: "uploaded_video" },
      { nodeId: "2", inputName: "image", valueType: "uploaded_image" },
    ],
  }), /绑定类型不一致/);
});
