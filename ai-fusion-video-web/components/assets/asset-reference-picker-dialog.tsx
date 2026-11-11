"use client";

import { useEffect, useId, useRef, useState } from "react";
import { Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { getApiErrorMessage } from "@/lib/api/api-error";
import type { AssetReference, AssetReferenceMediaType } from "@/lib/asset-references";
import { cn } from "@/lib/utils";
import { AssetReferencePickerLibrary } from "./asset-reference-picker-library";
import { AssetReferencePickerPreview } from "./asset-reference-picker-preview";
import { AssetReferencePickerSelection } from "./asset-reference-picker-selection";
import { AssetReferencePickerSource } from "./asset-reference-picker-source";
import {
  movePickerReference,
  pickerSelectionIssue,
  useAssetReferencePickerData,
} from "./asset-reference-picker-support";

export interface AssetReferencePickerDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  projectId: number | null;
  projectLocked: boolean;
  mediaTypes: AssetReferenceMediaType[];
  targetLabel?: string;
  selectionIssue: (selection: AssetReference[]) => string | null;
  onConfirm: (selection: AssetReference[]) => Promise<void>;
}

export function AssetReferencePickerDialog(props: AssetReferencePickerDialogProps) {
  if (!props.open) {
    return null;
  }
  return (
    <AssetReferencePickerSession
      key={String(props.projectId) + ":" + String(props.projectLocked)}
      {...props}
    />
  );
}

function AssetReferencePickerSession({
  onOpenChange,
  projectId,
  projectLocked,
  mediaTypes,
  targetLabel,
  selectionIssue,
  onConfirm,
}: AssetReferencePickerDialogProps) {
  const [sourceProjectId, setSourceProjectId] = useState(projectId);
  const [selection, setSelection] = useState<AssetReference[]>([]);
  const [preview, setPreview] = useState<AssetReference | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [confirmError, setConfirmError] = useState<string | null>(null);
  const [mobilePanel, setMobilePanel] = useState<"library" | "selection">("library");
  const active = useRef(false);
  const submitting = useRef(false);
  const id = useId();
  const data = useAssetReferencePickerData(sourceProjectId, projectLocked);
  const issue = pickerSelectionIssue(selection, sourceProjectId, mediaTypes, selectionIssue);

  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);

  const changeSource = (nextProjectId: number | null) => {
    if (submitting.current || selection.length > 0 || nextProjectId === sourceProjectId) {
      return;
    }
    setSourceProjectId(nextProjectId);
    data.retryAssets();
    setPreview(null);
    setConfirmError(null);
    setMobilePanel("library");
  };

  const editSelection = (update: (current: AssetReference[]) => AssetReference[]) => {
    if (submitting.current) {
      return;
    }
    setConfirmError(null);
    setSelection(update);
  };

  const toggleReference = (reference: AssetReference) => {
    editSelection((current) => current.some((item) => item.key === reference.key)
      ? current.filter((item) => item.key !== reference.key)
      : [...current, reference]);
  };

  const confirm = async () => {
    if (submitting.current || issue !== null) {
      return;
    }
    submitting.current = true;
    setConfirming(true);
    setConfirmError(null);
    setPreview(null);
    try {
      await onConfirm([...selection]);
      if (active.current) {
        onOpenChange(false);
      }
    } catch (error) {
      if (active.current) {
        setConfirmError(getApiErrorMessage(error));
      }
    } finally {
      submitting.current = false;
      if (active.current) {
        setConfirming(false);
      }
    }
  };

  return (
    <Dialog
      open
      onOpenChange={(nextOpen) => {
        if (!submitting.current) {
          onOpenChange(nextOpen);
        }
      }}
    >
      <DialogContent
        showCloseButton={!confirming}
        className="flex h-[min(52rem,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col gap-4 overflow-hidden rounded-2xl sm:max-w-6xl"
        aria-busy={confirming}
      >
        <DialogHeader className="shrink-0 pr-8">
          <DialogTitle>{targetLabel ? `选择${targetLabel}` : "选择资产素材"}</DialogTitle>
          <DialogDescription>选择素材并调整顺序，按此顺序带入生成。</DialogDescription>
        </DialogHeader>
        <AssetReferencePickerSource
          projectId={sourceProjectId}
          locked={projectLocked}
          projects={data.projects}
          loading={data.loadingProjects}
          error={data.projectError}
          disabled={confirming || selection.length > 0}
          selectionCount={selection.length}
          onChange={changeSource}
          onRetry={data.retryProjects}
        />
        <div className="flex shrink-0 items-center gap-2 p-1 lg:hidden" aria-label="显示区域">
          <Button
            type="button"
            variant={mobilePanel === "library" ? "secondary" : "ghost"}
            aria-pressed={mobilePanel === "library"}
            aria-controls={id + "-library"}
            disabled={confirming}
            onClick={() => setMobilePanel("library")}
          >
            素材库
          </Button>
          <Button
            type="button"
            variant={mobilePanel === "selection" ? "secondary" : "ghost"}
            aria-pressed={mobilePanel === "selection"}
            aria-controls={id + "-selection"}
            disabled={confirming}
            onClick={() => setMobilePanel("selection")}
          >
            已选 {selection.length} 项
          </Button>
        </div>
        <div className="grid min-h-0 flex-1 grid-cols-1 gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
          <div id={id + "-library"} className={cn("min-h-0 min-w-0", mobilePanel !== "library" && "hidden lg:block")}>
            <AssetReferencePickerLibrary
              key={sourceProjectId === null ? "standalone" : String(sourceProjectId)}
              references={data.references}
              selection={selection}
              mediaTypes={mediaTypes}
              loading={data.loadingAssets}
              error={data.assetError}
              disabled={confirming}
              onRetry={data.retryAssets}
              onToggle={toggleReference}
              onPreview={setPreview}
            />
          </div>
          <div id={id + "-selection"} className={cn("min-h-0 min-w-0", mobilePanel !== "selection" && "hidden lg:block")}>
            <AssetReferencePickerSelection
              selection={selection}
              disabled={confirming}
              onMove={(key, direction) => editSelection((current) => movePickerReference(current, key, direction))}
              onRemove={(key) => editSelection((current) => current.filter((reference) => reference.key !== key))}
              onClear={() => editSelection(() => [])}
              onPreview={setPreview}
            />
          </div>
        </div>
        <div className="flex shrink-0 flex-col gap-3 p-1">
          {confirmError !== null && (
            <p role="alert" className="break-words text-sm text-destructive">{confirmError}</p>
          )}
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <p
              role="status"
              aria-live="polite"
              className={cn("break-words text-sm", selection.length > 0 && issue !== null ? "text-destructive" : "text-muted-foreground")}
            >
              {confirming ? "正在确认素材…" : issue !== null ? issue : "已选 " + selection.length + " 项，按编号顺序使用"}
            </p>
            <DialogFooter className="shrink-0">
              <Button type="button" variant="outline" disabled={confirming} onClick={() => onOpenChange(false)}>取消</Button>
              <Button type="button" disabled={confirming || issue !== null} onClick={confirm}>
                {confirming && <Loader2 className="animate-spin motion-reduce:animate-none" aria-hidden="true" />}
                {confirming ? "确认中…" : "使用所选"}
              </Button>
            </DialogFooter>
          </div>
        </div>
      </DialogContent>
      <AssetReferencePickerPreview reference={preview} onClose={() => setPreview(null)} />
    </Dialog>
  );
}
