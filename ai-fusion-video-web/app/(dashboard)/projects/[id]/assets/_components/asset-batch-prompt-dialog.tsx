"use client";

import { useState } from "react";
import { Images } from "lucide-react";
import { AssetImageGenerationDialog } from "@/components/assets/asset-image-generation-dialog";
import { DialogScrollBody } from "@/components/shared/dialog-scroll-body";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import type { AssetWithItems } from "@/lib/api/asset";
import { enqueueAssetImageGeneration } from "@/lib/asset-image-generation";
import { useProject } from "../../project-context";
import { AssetBatchPromptResults } from "./asset-batch-prompt-results";
import { AssetBatchPromptSelection } from "./asset-batch-prompt-selection";
import { useAssetBatchPrompts, type BatchPromptTarget } from "./use-asset-batch-prompts";

export function AssetBatchPromptDialog({ open, assets, onClose, onSaved }: {
  open: boolean;
  assets: AssetWithItems[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const { project } = useProject();
  const { confirm } = useConfirm();
  const [selecting, setSelecting] = useState(true);
  const [imageOpen, setImageOpen] = useState(false);
  const batch = useAssetBatchPrompts(project, onSaved);
  const busy = batch.running || batch.savingId !== null;
  const savedCount = batch.saved.length;
  const unsaved = batch.drafts.some((row) => row.status === "ready" && (!row.savedInBatch || row.text !== row.savedText));

  async function generate(targets: BatchPromptTarget[]) {
    if (!project || busy || targets.length === 0) {
      return;
    }
    if (unsaved && !await confirm({
      title: "开始新一批提示词",
      description: "当前批次还有未保存的草稿。开始后将清除这些草稿。",
      variant: "warning",
      confirmText: "开始新一批",
    })) {
      return;
    }
    batch.start(targets);
    setSelecting(false);
  }

  return (
    <>
      <Dialog open={open && !imageOpen} onOpenChange={(next) => {
        if (!next) {
          onClose();
        }
      }}>
        <DialogContent className="flex max-h-[calc(100vh-2rem)] flex-col gap-0 overflow-hidden p-0 sm:max-w-2xl">
          <DialogHeader className="shrink-0 border-b border-border/20 px-5 py-4 pr-14">
            <DialogTitle>批量生成提示词</DialogTitle>
            <DialogDescription>
              {selecting ? "选择子资产和各类型模板。已有提示词的条目需手动勾选。"
                : "逐项修改并保存 Markdown，再为本批已保存的子资产生图。"}
            </DialogDescription>
          </DialogHeader>
          <DialogScrollBody className="flex flex-col gap-4 p-5">
            {selecting ? (
              <AssetBatchPromptSelection assets={assets} disabled={!project || busy} onGenerate={(targets) => void generate(targets)} />
            ) : (
              <AssetBatchPromptResults drafts={batch.drafts} savingId={batch.savingId}
                onEdit={batch.edit} onRetry={batch.retry} onSave={(row) => void batch.save(row)} />
            )}
          </DialogScrollBody>
          <div className="flex shrink-0 flex-wrap items-center justify-end gap-2 border-t border-border/20 px-5 py-3">
            {batch.drafts.length > 0 && (
              <Button variant="outline" disabled={!selecting && busy} onClick={() => setSelecting(!selecting)}>
                {selecting ? "查看本批草稿" : "重新选择"}
              </Button>
            )}
            <Button variant="ghost" onClick={onClose}>关闭</Button>
            {!selecting && (
              <Button variant="image" disabled={!project || busy || savedCount === 0}
                onClick={() => setImageOpen(true)}>
                <Images />
                批量生图（{savedCount}）
              </Button>
            )}
          </div>
        </DialogContent>
      </Dialog>
      {imageOpen && project && (
        <AssetImageGenerationDialog title="批量生图" description={`为本批 ${savedCount} 个已保存的子资产各生成一张图片。`}
          onClose={() => setImageOpen(false)}
          onConfirm={(image) => {
            enqueueAssetImageGeneration({
              projectId: project.id,
              assetIds: Array.from(new Set(batch.saved.map(({ asset }) => asset.id))),
              itemIds: batch.saved.map(({ item }) => item.id),
              image,
              label: "批量生成资产图",
            });
            onClose();
          }} />
      )}
    </>
  );
}
