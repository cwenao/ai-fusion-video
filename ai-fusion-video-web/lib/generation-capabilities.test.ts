import assert from "node:assert/strict";
import test from "node:test";
import { resolveGenerationCapabilities } from "./generation-capabilities";
import { generationSubmitRequest } from "../components/dashboard/generation/generation-submit-request";
import { EMPTY_GENERATION_FORM } from "../components/dashboard/generation/generation-types";
import { getComfyUiBusinessFields } from "../app/(dashboard)/settings/ai-models/_components/comfyui-workflow-support";

type Model = Parameters<typeof resolveGenerationCapabilities>[0];
const model = (generationConfig: Record<string, unknown>) => ({ modelType: 2, generationConfig }) as Model;

test("ComfyUI does not advertise ratios absent from its published capability", () => {
  const capabilities = resolveGenerationCapabilities(model({ comfyuiWorkflowVersionId: 7 }), []);
  assert.deepEqual(capabilities.supportedAspectRatios, []);
  assert.deepEqual(capabilities.supportedResolutions, []);
});

test("selector ratio and MP tier survive ordinary image submission without pixel defaults", () => {
  const capabilities = resolveGenerationCapabilities(model({
    comfyuiWorkflowVersionId: 8,
    comfyuiResolutionSelector: { nodeId: "681", multiple: 32 },
    supportedAspectRatios: ["1:1", "16:9", "9:16"],
    supportedResolutions: ["1MP", "2MP"],
    defaultAspectRatio: "16:9",
    defaultResolution: "2MP",
  }), []);
  assert.deepEqual(capabilities.supportedAspectRatios, ["1:1", "16:9", "9:16"]);
  assert.deepEqual(capabilities.supportedResolutions, ["1MP", "2MP"]);
  for (const ratio of capabilities.supportedAspectRatios) {
    for (const resolution of capabilities.supportedResolutions) {
      const request = generationSubmitRequest("image", {
        ...EMPTY_GENERATION_FORM, prompt: "selector test", ratio, resolution,
      }, 6, capabilities);
      assert.equal(request.ratio, ratio);
      assert.equal(request.resolution, resolution);
      assert.equal("aspectRatio" in request && request.aspectRatio, ratio);
      assert.equal("width" in request, false);
      assert.equal("height" in request, false);
    }
  }
});

test("non-ComfyUI defaults and pixel-table requests remain unchanged", () => {
  assert.deepEqual(resolveGenerationCapabilities(model({}), []).supportedAspectRatios,
    ["1:1", "4:3", "3:4", "16:9", "9:16"]);
  const capabilities = resolveGenerationCapabilities(model({
    supportedResolutions: ["1536x864"],
    supportedSizes: { "1k": { "16:9": "1536x864" } },
  }), []);
  const request = generationSubmitRequest("image", {
    ...EMPTY_GENERATION_FORM, prompt: "pixel test", ratio: "16:9", resolution: "1k",
  }, 1, capabilities);
  assert.equal(request.resolution, "1536x864");
});

test("image and video workflow bindings expose declared selector parameters", () => {
  assert.equal(getComfyUiBusinessFields(2).includes("aspectRatio"), true);
  assert.equal(getComfyUiBusinessFields(2).includes("megapixels"), true);
  assert.equal(getComfyUiBusinessFields(3).includes("aspectRatio"), true);
  assert.equal(getComfyUiBusinessFields(3).includes("megapixels"), true);
  assert.equal(getComfyUiBusinessFields(3).includes("resolutionMultiple"), true);
  assert.equal(getComfyUiBusinessFields(3).includes("upscaleMegapixels"), true);
  assert.equal(getComfyUiBusinessFields(2).includes("upscaleMegapixels"), false);
});
