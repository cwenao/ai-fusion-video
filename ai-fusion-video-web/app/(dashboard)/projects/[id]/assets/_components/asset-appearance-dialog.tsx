"use client";

import { ProjectTextAiEditor } from "@/components/project-text/project-text-ai-editor";
import { DialogScrollBody } from "@/components/shared/dialog-scroll-body";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import type { Asset } from "@/lib/api/asset";
import { getAssetType } from "./asset-types";
import type { AssetItemVariant } from "@/lib/asset-item-variant";

export function AssetAppearanceDialog({ projectId, asset, name, appearance, variant, onClose, onApply }: {
  projectId: number;
  asset: Asset;
  name: string;
  appearance: string;
  variant: AssetItemVariant;
  onClose: () => void;
  onApply: (value: string) => void;
}) {
  return (
    <Dialog open onOpenChange={(open) => {
      if (!open) {
        onClose();
      }
    }}>
      <DialogContent className="flex max-h-[calc(100vh-2rem)] flex-col gap-0 overflow-hidden p-0 sm:max-w-lg">
        <DialogHeader className="shrink-0 border-b border-border/20 px-5 py-4 pr-14">
          <DialogTitle>AI 外观描写</DialogTitle>
          <DialogDescription>{name} · {getAssetType(asset.type).label}</DialogDescription>
        </DialogHeader>
        <DialogScrollBody className="p-5">
          <ProjectTextAiEditor
            projectId={projectId}
            kind="assetAppearance"
            title={`AI 外观描写 · ${name}`}
            context={{
              类型: asset.type, 主资产: asset.name, 主资产描述: asset.description, 子资产名称: name,
              时间线: variant.timeline.trim(), 表现状态: variant.appearanceState.trim(),
            }}
            draft={appearance}
            quickInstruction="依据资产设定生成一段具体、可直接用于生图的外观描写，保留已给出的外观细节。"
            placeholder="例如：保留灰蓝色外套，补充面料和磨损细节"
            onApply={onApply}
          />
        </DialogScrollBody>
      </DialogContent>
    </Dialog>
  );
}
