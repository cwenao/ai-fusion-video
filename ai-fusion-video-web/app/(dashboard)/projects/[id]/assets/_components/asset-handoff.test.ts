import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import type { AssetItem, AssetWithItems } from "@/lib/api/asset";
import type { AssetItemDraft } from "./asset-types";

const require = createRequire(import.meta.url);
const ts: typeof import("typescript") = require("typescript");
const root = fileURLToPath(new URL("../../../../../../", import.meta.url));
const route = "app/(dashboard)/projects/[id]/assets/_components";
const actionsFile = "components/assets/asset-generation-actions.tsx";
type UiNode = { type: unknown; props: Record<string, unknown> };

// Render the business components with inert shared controls. Assertions protect
// selected source identity, availability, original playback, and pipeline calls.
function fixture() {
  const pushes: string[] = [];
  const state: unknown[] = [];
  const cache = new Map<string, { exports: Record<string, unknown> }>();
  let cursor = 0;
  const hooks = {
    useId: () => "handoff-issue",
    useRef: (value: unknown) => ({ current: value }),
    useMemo: (callback: () => unknown) => callback(),
    useEffect: () => {},
    useState: (initial: unknown) => {
      const index = cursor++;
      if (!(index in state)) {
        state[index] = initial;
      }
      return [state[index], (next: unknown) => {
        state[index] = typeof next === "function" ? next(state[index]) : next;
      }];
    },
  };
  const jsx = (type: unknown, props: Record<string, unknown>) => ({ type, props });
  const mocks: Record<string, unknown> = {
    react: hooks,
    "react/jsx-runtime": { jsx, jsxs: jsx, Fragment: "Fragment" },
    "next/navigation": { useRouter: () => ({ push: (href: string) => pushes.push(href) }) },
    "lucide-react": new Proxy({}, { get: (_, name) => String(name) }),
    "@/components/ui/button": { Button: "Button" },
    "@/components/ui/input": { Input: "Input" },
    "@/components/ui/textarea": { Textarea: "Textarea" },
    "./asset-appearance-dialog": { AssetAppearanceDialog: "AssetAppearanceDialog" },
    "./asset-image-prompt-field": { AssetImagePromptField: "AssetImagePromptField" },
    "@/components/assets/asset-image-generation-dialog": { AssetImageGenerationDialog: "AssetImageGenerationDialog" },
    "@/components/media/managed-image": { SafeImage: "SafeImage" },
    "@/components/media/managed-video": { ManagedVideo: "ManagedVideo" },
    "@/components/media/managed-audio": { ManagedAudio: "ManagedAudio" },
    "@/lib/api/storage": { uploadFile: async () => "media://uploaded" },
    "@/lib/api/api-error": { getApiErrorMessage: (error: Error) => error.message },
    "@/lib/store/pipeline-store": {},
    "@/lib/utils": { cn: (...values: unknown[]) => values.filter(Boolean).join(" ") },
  };
  function load(relative: string): Record<string, unknown> {
    const file = path.resolve(root, relative);
    const existing = cache.get(file);
    if (existing) {
      return existing.exports;
    }
    const loaded = { exports: {} };
    cache.set(file, loaded);
    const compiled = ts.transpileModule(fs.readFileSync(file, "utf8"), {
      compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
      fileName: file,
      reportDiagnostics: true,
    });
    assert.equal(compiled.diagnostics?.length, 0, "Syntax errors in " + relative);
    const localRequire = (name: string): unknown => {
      if (name in mocks) {
        return mocks[name];
      }
      const target = name.startsWith("@/") ? path.resolve(root, name.slice(2)) : path.resolve(path.dirname(file), name);
      const extension = fs.existsSync(target + ".tsx") ? ".tsx" : ".ts";
      return load(target + extension);
    };
    vm.runInThisContext("(function(require,module,exports){" + compiled.outputText + "\n})", { filename: file })(localRequire, loaded, loaded.exports);
    return loaded.exports;
  }
  return {
    pushes,
    render(file: string, name: string, props: object): UiNode {
      cursor = 0;
      const component = load(file)[name] as (props: object) => UiNode;
      return component(props);
    },
    references(asset: AssetWithItems) {
      const get = load("lib/asset-references.ts").assetReferences as (asset: AssetWithItems) => Array<{ itemId: number | null }>;
      return get(asset);
    },
  };
}

function nodes(value: unknown): UiNode[] {
  if (Array.isArray(value)) {
    return value.flatMap(nodes);
  }
  if (!value || typeof value !== "object" || !("props" in value)) {
    return [];
  }
  const node = value as UiNode;
  return [node, ...nodes(node.props.children)];
}
function text(value: unknown): string {
  if (Array.isArray(value)) {
    return value.map(text).join("");
  }
  if (value && typeof value === "object" && "props" in value) {
    return text((value as UiNode).props.children);
  }
  return typeof value === "string" ? value : "";
}
function element(tree: UiNode, type: string, label?: string): UiNode {
  const node = nodes(tree).find((candidate) => (candidate.type === type
    || (typeof candidate.type === "function" && candidate.type.name === type))
    && (!label || text(candidate.props.children) === label));
  assert.ok(node, "Missing " + type + " " + (label ?? ""));
  return node;
}
function click(node: UiNode) {
  assert.notEqual(node.props.disabled, true);
  (node.props.onClick as () => void)();
}
function item(patch: Partial<AssetItem> = {}): AssetItem {
  return {
    id: 2, version: 3, assetVersion: 7, assetId: 11, itemType: null, name: "当前素材",
    imageUrl: "media://current-image", thumbnailUrl: null, properties: null,
    sortOrder: 0, sourceType: 2, aiPrompt: null, createTime: "", updateTime: "", ...patch,
  };
}
function asset(items: AssetItem[]): AssetWithItems {
  return {
    id: 11, version: 7, userId: 1, projectId: 19, type: "scene", name: "场景资产", description: null,
    coverUrl: "media://master-cover", properties: null, tags: null, sourceType: 2, aiPrompt: null,
    ownerType: 1, ownerId: 1, status: 1, createTime: "", updateTime: "", items,
  };
}
function mediaProps(f: ReturnType<typeof fixture>, selected: AssetItem, parent = asset([selected])) {
  return {
    item: selected, references: f.references(parent).filter((reference) => reference.itemId === selected.id), assetType: parent.type, name: selected.name,
    imageUrl: selected.imageUrl, saving: false, uploading: false, generationDisabled: false,
    generationLabel: "生成图片", error: "", onImageChange: () => {}, onUpload: async () => {}, onGenerate: () => {},
  };
}

// Prevents master-cover/sibling substitution and leaking media URLs through navigation.
test("saved image handoff carries only the selected item identity and versions", () => {
  const f = fixture();
  const parent = asset([item({ id: 1, imageUrl: "https://signed.invalid/sibling?token=secret" }), item()]);
  const tree = f.render(actionsFile, "AssetGenerationActions", { asset: parent, itemId: 2 });
  click(element(tree, "Button", "用于生图"));
  click(element(tree, "Button", "用于生视频"));
  assert.equal(f.pushes.length, 2);
  for (const [index, href] of f.pushes.entries()) {
    const url = new URL(href, "https://app.invalid");
    assert.equal(url.pathname, index === 0 ? "/generate/image" : "/generate/video");
    assert.equal(url.searchParams.get("assetId"), "11");
    assert.equal(url.searchParams.get("assetVersion"), "7");
    assert.equal(url.searchParams.get("itemId"), "2");
    assert.equal(url.searchParams.get("itemVersion"), "3");
    assert.equal(url.searchParams.get("projectId"), "19");
    assert.equal(url.searchParams.get("sourceField"), "imageUrl");
    assert.ok(!href.includes("media://") && !href.includes("signed.invalid"));
  }
});

// A generated video's imageUrl is only its poster, never its original source.
test("generated video disables image handoff and plays its full original with controls", () => {
  const f = fixture();
  const selected = item({ itemType: "generated_video", imageUrl: "media://poster",
    properties: { sourceGenerationMode: "video", sourceMediaUrl: "media://original-video" } });
  const tree = f.render(actionsFile, "AssetGenerationActions", { asset: asset([selected]), itemId: selected.id });
  assert.equal(element(tree, "Button", "用于生图").props.disabled, true);
  assert.match(text(tree), /视频素材仅可用于生视频/);
  click(element(tree, "Button", "用于生视频"));
  assert.equal(new URL(f.pushes[0], "https://app.invalid").searchParams.get("sourceField"), "properties.sourceMediaUrl");
  const preview = f.render(route + "/asset-item-media.tsx", "AssetItemMedia", mediaProps(f, selected));
  const video = element(preview, "ManagedVideo");
  assert.equal(video.props.src, "media://original-video");
  assert.equal(video.props.poster, "media://poster");
  assert.equal(video.props.controls, true);
  assert.ok(!nodes(preview).some((node) => node.type === "SafeImage"));
});

test("a video with only a poster has no usable handoff or image preview", () => {
  const f = fixture();
  const selected = item({ itemType: "generated_video", imageUrl: "media://poster", properties: { sourceGenerationMode: "video" } });
  const tree = f.render(actionsFile, "AssetGenerationActions", { asset: asset([selected]), itemId: selected.id });
  assert.equal(element(tree, "Button", "用于生图").props.disabled, true);
  assert.equal(element(tree, "Button", "用于生视频").props.disabled, true);
  assert.match(text(tree), /缺少原始素材/);
  const preview = f.render(route + "/asset-item-media.tsx", "AssetItemMedia", mediaProps(f, selected));
  assert.match(text(preview), /视频原件不可用/);
  assert.ok(!nodes(preview).some((node) => node.type === "SafeImage" || node.type === "ManagedVideo"));
});

test("saved video source is preferred for video handoff while its independent image remains usable", () => {
  const f = fixture();
  const selected = item({ properties: { videoUrl: "media://asset-video" } });
  const tree = f.render(actionsFile, "AssetGenerationActions", { asset: asset([selected]), itemId: selected.id });
  click(element(tree, "Button", "用于生图"));
  click(element(tree, "Button", "用于生视频"));
  assert.equal(new URL(f.pushes[0], "https://app.invalid").searchParams.get("sourceField"), "imageUrl");
  assert.equal(new URL(f.pushes[1], "https://app.invalid").searchParams.get("sourceField"), "properties.videoUrl");
  const preview = f.render(route + "/asset-item-media.tsx", "AssetItemMedia", mediaProps(f, selected));
  assert.equal(element(preview, "ManagedVideo").props.src, "media://asset-video");
});

function workspaceProps(selected: AssetItem) {
  return {
    projectId: 19, mode: "edit", item: selected, asset: asset([selected]), assetType: "scene", parentVersion: 7,
    saving: false, onCreate: async () => {}, onSave: async () => selected,
    onDelete: () => {}, onCancelCreate: () => {}, onGenerate: () => {},
  };
}
const flush = () => new Promise<void>((resolve) => setImmediate(resolve));
const imageOptions = { modelId: 7 };

function confirmGeneration(f: ReturnType<typeof fixture>, tree: UiNode, props: object) {
  (element(tree, "AssetItemMedia").props.onGenerate as () => void)();
  const dialog = element(f.render(route + "/asset-item-workspace.tsx", "AssetItemWorkspace", props),
    "AssetImageGenerationDialog");
  return (dialog.props.onConfirm as (image: typeof imageOptions) => Promise<void>)(imageOptions);
}

test("dirty item handoff stays blocked and existing save-then-generate pipeline is preserved", async () => {
  const f = fixture();
  const calls: string[] = [];
  const selected = item({ properties: { imagePrompt: "白昼站台" } });
  const props = { ...workspaceProps(selected), onSave: async (_item: AssetItem, draft: AssetItemDraft) => {
    calls.push("save");
    return { ...selected, name: draft.name, version: 4 };
  }, onGenerate: () => calls.push("generate") };
  let tree = f.render(route + "/asset-item-workspace.tsx", "AssetItemWorkspace", props);
  (element(tree, "Input").props.onChange as (event: { target: { value: string } }) => void)({ target: { value: "已编辑名称" } });
  tree = f.render(route + "/asset-item-workspace.tsx", "AssetItemWorkspace", props);
  const actionProps = element(tree, "AssetGenerationActions").props;
  assert.match(String(actionProps.blockedReason), /先保存/);
  const actions = f.render(actionsFile, "AssetGenerationActions", actionProps);
  assert.equal(element(actions, "Button", "用于生图").props.disabled, true);
  assert.equal(element(actions, "Button", "用于生视频").props.disabled, true);
  await confirmGeneration(f, tree, props);
  assert.deepEqual(calls, ["save", "generate"]);
});

test("failed saving never launches asset pipeline and keeps the draft error visible", async () => {
  const f = fixture();
  const calls: string[] = [];
  const props = { ...workspaceProps(item({ properties: { imagePrompt: "写实站牌" } })), onSave: async () => {
    throw new Error("版本冲突");
  }, onGenerate: () => calls.push("generate") };
  let tree = f.render(route + "/asset-item-workspace.tsx", "AssetItemWorkspace", props);
  (element(tree, "Input").props.onChange as (event: { target: { value: string } }) => void)({ target: { value: "未保存名称" } });
  tree = f.render(route + "/asset-item-workspace.tsx", "AssetItemWorkspace", props);
  click(element(tree, "Button", "保存"));
  await flush();
  tree = f.render(route + "/asset-item-workspace.tsx", "AssetItemWorkspace", props);
  assert.deepEqual(calls, []);
  assert.equal(element(tree, "AssetItemMedia").props.error, "版本冲突");
});

test("master editing and saving block both navigation actions", () => {
  for (const patch of [{ navigationBlockedReason: "请先保存或取消主资产编辑，再用于生成" }, { saving: true }]) {
    const f = fixture();
    const tree = f.render(route + "/asset-item-workspace.tsx", "AssetItemWorkspace", { ...workspaceProps(item()), ...patch });
    const actions = f.render(actionsFile, "AssetGenerationActions", element(tree, "AssetGenerationActions").props);
    assert.equal(element(actions, "Button", "用于生图").props.disabled, true);
    assert.equal(element(actions, "Button", "用于生视频").props.disabled, true);
  }
});

test("an image without an original cannot use its master cover or another item", () => {
  const f = fixture();
  const selected = item({ imageUrl: null });
  const parent = asset([item({ id: 1 }), selected]);
  const tree = f.render(actionsFile, "AssetGenerationActions", { asset: parent, itemId: selected.id });
  assert.equal(element(tree, "Button", "用于生图").props.disabled, true);
  assert.equal(element(tree, "Button", "用于生视频").props.disabled, true);
  assert.match(text(tree), /缺少原始素材/);
});

test("uploading blocks handoff until the newly uploaded image is saved", async () => {
  const f = fixture();
  const props = workspaceProps(item());
  let tree = f.render(route + "/asset-item-workspace.tsx", "AssetItemWorkspace", props);
  const upload = element(tree, "AssetItemMedia").props.onUpload as (file: File) => Promise<void>;
  const pending = upload(new File(["image"], "reference.png", { type: "image/png" }));
  tree = f.render(route + "/asset-item-workspace.tsx", "AssetItemWorkspace", props);
  let actionProps = element(tree, "AssetGenerationActions").props;
  assert.match(String(actionProps.blockedReason), /上传中/);
  const actions = f.render(actionsFile, "AssetGenerationActions", actionProps);
  assert.equal(element(actions, "Button", "用于生图").props.disabled, true);
  assert.equal(element(actions, "Button", "用于生视频").props.disabled, true);
  await pending;
  tree = f.render(route + "/asset-item-workspace.tsx", "AssetItemWorkspace", props);
  actionProps = element(tree, "AssetGenerationActions").props;
  assert.match(String(actionProps.blockedReason), /先保存/);
});

test("新建子资产并生图时提交当前时间线和表现状态", async () => {
  const f = fixture();
  const calls: unknown[][] = [];
  const props = { ...workspaceProps(item()), mode: "create", item: null, asset: asset([]),
    onCreate: async (...args: unknown[]) => {
      calls.push(args);
    } };
  let tree = f.render(route + "/asset-item-workspace.tsx", "AssetItemWorkspace", props);
  assert.ok(!nodes(tree).some((node) => typeof node.type === "function" && node.type.name === "AssetGenerationActions"));
  (element(tree, "Input").props.onChange as (event: { target: { value: string } }) => void)({ target: { value: "新增素材" } });
  (element(tree, "AssetItemVariantFields").props.onChange as (variant: AssetItemDraft["variant"]) => void)({
    timeline: "十年前", appearanceState: "雨夜",
  });
  (element(tree, "AssetImagePromptField").props.onChange as (value: string) => void)("雨中站台");
  tree = f.render(route + "/asset-item-workspace.tsx", "AssetItemWorkspace", props);
  await confirmGeneration(f, tree, props);
  assert.deepEqual(calls, [[7, {
    name: "新增素材", appearance: "", imageUrl: "", imagePrompt: "雨中站台",
    variant: { timeline: "十年前", appearanceState: "雨夜" },
  }, imageOptions]]);
});


test("采用 AI 外观描写后由创建表单提交草稿", async () => {
  const f = fixture();
  const selected = item();
  const created: string[] = [];
  const props = { ...workspaceProps(selected), mode: "create", item: null,
    onCreate: async (_version: number, draft: AssetItemDraft) => {
      created.push(draft.name, draft.appearance);
    } };
  let tree = f.render(route + "/asset-item-workspace.tsx", "AssetItemWorkspace", props);
  assert.equal(element(tree, "Button", "AI 写外观").props.disabled, true);
  (element(tree, "Input").props.onChange as (event: { target: { value: string } }) => void)({ target: { value: "雨夜站台" } });
  tree = f.render(route + "/asset-item-workspace.tsx", "AssetItemWorkspace", props);
  click(element(tree, "Button", "AI 写外观"));
  tree = f.render(route + "/asset-item-workspace.tsx", "AssetItemWorkspace", props);
  const dialog = element(tree, "AssetAppearanceDialog");
  (dialog.props.onApply as (value: string) => void)("墨绿色铁制站牌，表面覆着雨珠。");
  assert.deepEqual(created, []);
  tree = f.render(route + "/asset-item-workspace.tsx", "AssetItemWorkspace", props);
  assert.equal(element(tree, "Textarea").props.value, "墨绿色铁制站牌，表面覆着雨珠。");
  click(element(tree, "Button", "创建"));
  await flush();
  assert.deepEqual(created, ["雨夜站台", "墨绿色铁制站牌，表面覆着雨珠。"]);
});
