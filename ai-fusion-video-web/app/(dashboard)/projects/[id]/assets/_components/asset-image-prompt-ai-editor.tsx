"use client";

import { ProjectTextAiEditor } from "@/components/project-text/project-text-ai-editor";
import { usePromptTemplates } from "@/hooks/use-prompt-templates";
import type { Asset } from "@/lib/api/asset";
import { useProject } from "../../project-context";
import { assetImagePromptContext, ASSET_IMAGE_PROMPT_INSTRUCTION } from "./asset-image-prompt-context";
import { AssetImagePromptTemplateSelect } from "./asset-image-prompt-template-select";
import { assetImagePromptTemplates } from "./asset-image-prompt-templates";
import { AssetPromptTemplateCatalogStatus } from "./asset-prompt-template-catalog-status";

export function AssetImagePromptAiEditor({ projectId, asset, item, draft, templateId, onSelect, onApply }: {
  projectId: number;
  asset: Asset;
  item: { name: string; type: string; appearance: string };
  draft: string;
  templateId: string;
  onSelect: (id: string) => void;
  onApply: (draft: string) => void;
}) {
  const { project } = useProject();
  const status = usePromptTemplates();
  const templates = assetImagePromptTemplates(asset.type, status.catalog.status === "ready" ? status.catalog.templates : [], true);
  const template = templates.find(({ value }) => value === templateId);

  return (
    <ProjectTextAiEditor
      projectId={projectId}
      kind="assetImagePrompt"
      title={`AI 写生图提示词 · ${item.name}`}
      context={assetImagePromptContext(project, asset, item, template)}
      draft={draft}
      disabled={!template}
      quickLabel="一键生成"
      quickInstruction={ASSET_IMAGE_PROMPT_INSTRUCTION}
      renderSettings={(busy) => (
        <div className="flex min-w-0 flex-col gap-2">
          <AssetImagePromptTemplateSelect label="提示词模板" value={template?.value ?? null}
            templates={templates} disabled={busy && !!template} onChange={onSelect} />
          <AssetPromptTemplateCatalogStatus {...status} />
          {template && "sections" in template && (
            <p className="text-xs text-muted-foreground">{template.sections.join(" · ")}</p>
          )}
        </div>
      )}
      placeholder="例如：保留主体设计，改为低机位视角"
      onApply={onApply}
    />
  );
}
