"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import {
  Check,
  Loader2,
  Plus,
  Sparkles,
  Trash2,
  X,
} from "lucide-react";
import type { AssetItem, AssetWithItems } from "@/lib/api/asset";
import { assetImagePromptHref, getAssetImagePrompt } from "@/lib/asset-image-prompt-source";
import { assetReferences } from "@/lib/asset-references";
import { AssetImageGenerationDialog } from "@/components/assets/asset-image-generation-dialog";
import type { ImageModelOptions } from "@/components/dashboard/generation/image-model-fields";
import { AssetGenerationActions } from "@/components/assets/asset-generation-actions";
import { uploadFile } from "@/lib/api/storage";
import { getApiErrorMessage } from "@/lib/api/api-error";
import { toastApiError } from "@/lib/api/toast-api-error";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { AssetAppearanceDialog } from "./asset-appearance-dialog";
import { AssetImagePromptField } from "./asset-image-prompt-field";
import { MAX_ASSET_IMAGE_PROMPT_LENGTH } from "./asset-image-prompt-context";
import { AssetItemMedia } from "./asset-item-media";
import { getAppearance, getAppearanceHints, type AssetItemDraft } from "./asset-types";
import { AssetItemVariantFields } from "./asset-item-variant-fields";
import { getAssetItemVariant } from "@/lib/asset-item-variant";

interface AssetItemWorkspaceProps {
  projectId: number;
  mode: "create" | "edit";
  item: AssetItem | null;
  asset: AssetWithItems;
  assetType: string;
  navigationBlockedReason?: string | null;
  parentVersion: number;
  saving: boolean;
  onCreate: (
    parentVersion: number,
    draft: AssetItemDraft,
    image?: ImageModelOptions,
  ) => Promise<void>;
  onSave: (
    item: AssetItem,
    draft: AssetItemDraft,
  ) => Promise<AssetItem>;
  onDelete: (item: AssetItem) => void;
  onCancelCreate: () => void;
  onGenerate: (image: ImageModelOptions) => void;
}

export function AssetItemWorkspace({
  projectId,
  mode,
  item,
  asset,
  assetType,
  navigationBlockedReason,
  parentVersion,
  saving,
  onCreate,
  onSave,
  onDelete,
  onCancelCreate,
  onGenerate,
}: AssetItemWorkspaceProps) {
  const router = useRouter();
  const [base, setBase] = useState(item);
  const [creationVersion] = useState(parentVersion);
  const initialName = base?.name || "";
  const initialAppearance = getAppearance(base);
  const initialImageUrl = base?.imageUrl || "";
  const initialImagePrompt = getAssetImagePrompt(base);
  const initialVariant = getAssetItemVariant(base?.properties ?? null);
  const [name, setName] = useState(initialName);
  const [appearance, setAppearance] = useState(initialAppearance);
  const [imageUrl, setImageUrl] = useState(initialImageUrl);
  const [imagePrompt, setImagePrompt] = useState(initialImagePrompt);
  const [variant, setVariant] = useState(initialVariant);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState("");
  const appearanceField = useId();
  const nameField = useId();
  const [appearanceOpen, setAppearanceOpen] = useState(false);
  const [generationOpen, setGenerationOpen] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const appearanceHints = useMemo(
    () => getAppearanceHints(assetType),
    [assetType],
  );
  const dirty =
    name !== initialName ||
    appearance !== initialAppearance ||
    imageUrl !== initialImageUrl ||
    imagePrompt !== initialImagePrompt ||
    variant.timeline !== initialVariant.timeline ||
    variant.appearanceState !== initialVariant.appearanceState;
  const draft: AssetItemDraft = { name, appearance, imageUrl, imagePrompt, variant };
  const disabled = saving || uploading || !name.trim() || imagePrompt.length > MAX_ASSET_IMAGE_PROMPT_LENGTH;

  useEffect(() => {
    if (!item || !base || dirty || saving || uploading ||
      (item.version <= base.version && item.assetVersion <= base.assetVersion)) {
      return;
    }
    setBase(item);
    setName(item.name || "");
    setAppearance(getAppearance(item));
    setImageUrl(item.imageUrl || "");
    setImagePrompt(getAssetImagePrompt(item));
    setVariant(getAssetItemVariant(item.properties));
  }, [item, base, dirty, saving, uploading]);

  const upload = async (file: File) => {
    if (saving || uploading) {
      return;
    }
    if (!file.type.startsWith("image/")) {
      setError("请选择图片文件");
      return;
    }

    setUploading(true);
    setError("");
    try {
      setImageUrl(await uploadFile(file, "assets"));
    } catch (cause) {
      setError(getApiErrorMessage(cause));
    } finally {
      setUploading(false);
    }
  };

  const appendHint = (hint: string) => {
    const token = `${hint}：`;
    setAppearance((current) => {
      if (current.includes(token)) {
        return current;
      }
      return current.trim() ? `${current.trim()}\n${token}` : token;
    });
    requestAnimationFrame(() => textareaRef.current?.focus());
  };

  const save = async () => {
    if (!base || disabled) {
      return;
    }
    setError("");
    const updated = await onSave(base, draft);
    setBase(updated);
    setName(updated.name || "");
    setAppearance(getAppearance(updated));
    setImageUrl(updated.imageUrl || "");
    setImagePrompt(getAssetImagePrompt(updated));
    setVariant(getAssetItemVariant(updated.properties));
    return updated;
  };

  const create = async (image?: ImageModelOptions) => {
    if (disabled) {
      return;
    }
    await onCreate(creationVersion, draft, image);
  };

  const generationBlockedReason = imagePrompt.trim() ? undefined : "请先生成或填写生图提示词。";
  const openImageTool = async () => {
    if (!base || disabled || generationBlockedReason || navigationBlockedReason) {
      return;
    }
    try {
      const source = dirty ? await save() : base;
      if (source) {
        router.push(assetImagePromptHref(source));
      }
    } catch (cause) {
      toastApiError(cause, "无法带入生图工具");
    }
  };
  const generationLabel = mode === "create" ? "创建并生图" : dirty ? "保存并生图" : "生成图片";

  const generate = async (image: ImageModelOptions) => {
    if (mode === "create") {
      await create(image);
      return;
    }
    if (base && dirty) {
      await save();
    }
    onGenerate(image);
  };

  return (
    <section className="grid min-h-[360px] min-w-0 bg-[radial-gradient(circle_at_18%_20%,color-mix(in_oklab,var(--primary)_5%,transparent),transparent_32%)] xl:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)]">
      <AssetItemMedia
        item={item}
        references={item ? assetReferences(asset).filter((reference) => reference.itemId === item.id) : []}
        assetType={assetType}
        name={name}
        imageUrl={imageUrl}
        saving={saving}
        uploading={uploading}
        generationDisabled={disabled || !!generationBlockedReason}
        generationBlockedReason={generationBlockedReason}
        generationLabel={generationLabel}
        error={error}
        onImageChange={setImageUrl}
        onUpload={upload}
        onGenerate={() => setGenerationOpen(true)}
      />

      <div className="flex min-w-0 flex-col border-t border-border/40 bg-background/35 p-5 xl:border-l xl:border-t-0">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <p className="text-[11px] font-semibold uppercase tracking-[0.15em] text-primary">
                {mode === "create" ? "新增" : "子资产"}
              </p>
              <span className="rounded-md bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">
                {mode === "create" ? "草稿" : imageUrl ? "有图片" : "待补图"}
              </span>
            </div>
            <h2 className="mt-1 truncate text-xl font-semibold tracking-tight">
              {mode === "create" ? "新建子资产" : name || "未命名"}
            </h2>
          </div>
          <Button
            variant={mode === "create" ? "ghost" : "destructive-ghost"}
            size="icon-sm"
            disabled={saving || uploading}
            onClick={mode === "create" ? onCancelCreate : () => {
              if (base) {
                onDelete(base);
              }
            }}
            title={mode === "create" ? "取消新建" : "删除子资产"}
            aria-label={mode === "create" ? "取消新建" : "删除子资产"}
          >
            {mode === "create" ? (
              <X className="h-4 w-4" />
            ) : (
              <Trash2 className="h-4 w-4" />
            )}
          </Button>
        </div>

        {mode === "edit" && item && (
          <div className="mt-4">
            <AssetGenerationActions
              asset={asset}
              itemId={item.id}
              blockedReason={navigationBlockedReason || (saving ? "正在保存，请稍候" : uploading
                ? "图片上传中，请稍候" : dirty ? "请先保存修改，再用于生成" : null)}
            />
          </div>
        )}

        <div className="mt-4 flex min-w-0 flex-col gap-4">
          <div className="flex min-w-0 flex-col gap-2">
            <label htmlFor={nameField} className="text-xs font-medium text-muted-foreground">名称</label>
            <Input disabled={saving || uploading}
              id={nameField}
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="子资产名称"
              className="h-9 rounded-lg bg-background/85"
              autoFocus={mode === "create"}
            />
          </div>

          <AssetItemVariantFields value={variant} onChange={setVariant} disabled={saving || uploading} />

          <div className="flex min-w-0 flex-col gap-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <label htmlFor={appearanceField} className="text-xs font-medium text-muted-foreground">
                外观描写
              </label>
              <div className="flex items-center gap-2">
                <span className="text-xs tabular-nums text-muted-foreground">{appearance.length} 字</span>
                <Button type="button" variant="ai" disabled={disabled} onClick={() => setAppearanceOpen(true)}>
                  <Sparkles />
                  AI 写外观
                </Button>
              </div>
            </div>
            <Textarea id={appearanceField} disabled={saving || uploading}
              ref={textareaRef}
              value={appearance}
              onChange={(event) => setAppearance(event.target.value)}
              rows={6}
              placeholder="描述材质、颜色、形态和细节"
              className="min-h-32 rounded-lg bg-background/85 text-sm leading-5"
            />
          </div>

          <div>
            <p className="mb-2 text-[10px] font-medium text-muted-foreground">
              描写要点
            </p>
            <div className="flex flex-wrap gap-1.5">
              {appearanceHints.map((hint) => (
                <button
                  key={hint}
                  type="button"
                  disabled={saving || uploading}
                  onClick={() => appendHint(hint)}
                  className="h-6 rounded-md border border-border/50 bg-background/65 px-2 text-[10px] text-muted-foreground transition-colors hover:border-primary/30 hover:bg-primary/5 hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
                >
                  + {hint}
                </button>
              ))}
            </div>
          </div>
        </div>

        <div className="py-4">
          <AssetImagePromptField
            projectId={projectId}
            asset={asset}
            itemName={name.trim()}
            itemType={base?.itemType ?? "variant"}
            appearance={appearance}
            variant={variant}
            value={imagePrompt}
            disabled={saving || uploading}
            onChange={setImagePrompt}
            imageTool={base ? {
              needsSave: dirty,
              blockedReason: navigationBlockedReason || (!name.trim() ? "请先填写子资产名称" : undefined),
              onOpen: openImageTool,
            } : undefined}
          />
        </div>

        <div className="mt-auto flex items-center justify-between gap-3 border-t border-border/40 pt-4">
          <span className="text-[10px] text-muted-foreground">
            {mode === "create" ? "尚未创建" : dirty ? "有未保存修改" : "已保存"}
          </span>
          <div className="flex items-center gap-2">
            {mode === "create" && (
              <Button variant="outline" size="sm" onClick={onCancelCreate}>
                取消
              </Button>
            )}
            <Button
              size="sm"
              disabled={disabled || (mode === "edit" && !dirty)}
              onClick={() => void (mode === "create" ? create() : save()).catch(failure => setError(getApiErrorMessage(failure)))}
            >
              {saving ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
              ) : mode === "create" ? (
                <Plus className="h-3.5 w-3.5" />
              ) : (
                <Check className="h-3.5 w-3.5" />
              )}
              {mode === "create" ? "创建" : "保存"}
            </Button>
          </div>
        </div>
      </div>
      {generationOpen && (
        <AssetImageGenerationDialog
          title={generationLabel}
          description={`为“${name.trim()}”生成一张图片。`}
          onClose={() => setGenerationOpen(false)}
          onConfirm={generate}
        />
      )}
      {appearanceOpen && (
        <AssetAppearanceDialog
          projectId={projectId}
          asset={asset}
          name={name.trim()}
          appearance={appearance}
          variant={variant}
          onClose={() => setAppearanceOpen(false)}
          onApply={(draft) => {
            setAppearance(draft);
            setAppearanceOpen(false);
          }}
        />
      )}
    </section>
  );
}
