import type { PromptTemplate, PromptTemplateCategory } from "@/lib/api/prompt-template";

type AssetImagePromptTemplateChoice = {
  value: string;
  label: string;
  category: PromptTemplateCategory;
};

type BuiltinTemplate = AssetImagePromptTemplateChoice & { sections: string[]; guidance: string };
type CustomTemplate = AssetImagePromptTemplateChoice & { markdown: string };

export type AssetImagePromptTemplate = BuiltinTemplate | CustomTemplate;

const builtinTemplates: BuiltinTemplate[] = [
  {
    value: "character",
    category: "character",
    label: "角色设定图",
    sections: ["主体与识别特征", "体态与服饰", "视角与构图", "画风与背景"],
    guidance: "默认采用中性的正面全身设定图和纯白背景，描述五官、体态、发型服饰及辨识特征。",
  },
  {
    value: "characterThreeView",
    category: "character",
    label: "角色三视图",
    sections: ["角色识别特征", "正侧背视图", "版面与留白", "画风与背景"],
    guidance: "在同一画面并列面部特写与正、侧、背三张全身图。保持身份、服装、比例和细节一致。",
  },
  {
    value: "scene",
    category: "scene",
    label: "场景概念图",
    sections: ["场景与空间布局", "环境与陈设", "时间与光线", "视角与画风"],
    guidance: "描述前景、中景、背景及空间关系。明确时间、可见陈设、光线方向和观看视角。",
  },
  {
    value: "prop",
    category: "prop",
    label: "道具设定图",
    sections: ["主体与结构", "材质与色彩", "纹理与细节", "视角与背景"],
    guidance: "突出形制、比例、材质和识别细节。在无特殊要求时采用清晰视角、纯白背景。",
  },
  {
    value: "general",
    category: "general",
    label: "通用资产图",
    sections: ["主体设定", "视觉细节", "构图与光线", "画风与背景"],
    guidance: "根据资产用途描述主体、可见细节与构图，保留资产定义中的关键特征。",
  },
];

export function defaultAssetImagePromptTemplate(assetType: string, itemType: string) {
  if (assetType === "character" && itemType === "three_view") {
    return "characterThreeView";
  }
  return builtinTemplates.some(({ value }) => value === assetType) ? assetType : "general";
}

export function assetImagePromptTemplates(
  assetType: string,
  custom: PromptTemplate[],
  allBuiltin = false,
): AssetImagePromptTemplate[] {
  const applies = ({ category }: { category: PromptTemplateCategory }) => category === assetType || category === "general";
  return [
    ...builtinTemplates.filter((template) => allBuiltin || applies(template)),
    ...custom.filter(applies).map(({ id, name, category, markdown }) => ({
      value: `custom:${id}`, label: name, category, markdown,
    })),
  ];
}
