import type { Asset } from "@/lib/api/asset";
import type { Project } from "@/lib/api/project";
import type { AssetImagePromptTemplate } from "./asset-image-prompt-templates";

export const MAX_ASSET_IMAGE_PROMPT_LENGTH = 5_000;

export const ASSET_IMAGE_PROMPT_INSTRUCTION =
  "根据项目设置、资产定义和外观描写，按选中的模板生成完整的 Markdown 生图提示词。";

export function assetImagePromptContext(
  project: Project | null,
  asset: Asset,
  item: { name: string; type: string; appearance: string },
  template: AssetImagePromptTemplate | undefined,
) {
  return {
    项目: {
      类型: project?.properties?.type,
      画风: project?.artStyle,
      画风描述: project?.artStyleDescription,
      画风提示词: project?.artStyleImagePrompt,
      画面比例: project?.properties?.aspectRatio,
    },
    资产定义: {
      类型: asset.type,
      名称: asset.name,
      描述: asset.description,
      属性: asset.properties,
    },
    子资产: { 名称: item.name, 类别: item.type, 外观描写: item.appearance },
    提示词模板: template,
  };
}
