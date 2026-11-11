"use client";

import { useEffect, useState } from "react";
import { assetApi } from "@/lib/api/asset";
import { getApiErrorMessage } from "@/lib/api/api-error";
import { projectApi, type Project } from "@/lib/api/project";
import {
  assetReferences,
  type AssetReference,
  type AssetReferenceMediaType,
} from "@/lib/asset-references";

export const ASSET_REFERENCE_MEDIA_LABELS: Record<AssetReferenceMediaType, string> = {
  image: "图片",
  video: "视频",
  audio: "音频",
};

export function pickerSelectionIssue(
  selection: AssetReference[],
  projectId: number | null,
  mediaTypes: AssetReferenceMediaType[],
  selectionIssue: (selection: AssetReference[]) => string | null,
): string | null {
  if (selection.length === 0) {
    return "请至少选择一个素材";
  }
  if (selection.some((reference) => reference.projectId !== projectId)) {
    return "单次只能选择同一项目的素材，请重新选择";
  }
  if (selection.some((reference) => !mediaTypes.includes(reference.mediaType))) {
    return "已选素材包含当前不支持的类型，请移除后继续";
  }
  return selectionIssue(selection);
}

export function movePickerReference(
  selection: AssetReference[],
  key: string,
  direction: -1 | 1,
): AssetReference[] {
  const index = selection.findIndex((reference) => reference.key === key);
  const destination = index + direction;
  if (index < 0 || destination < 0 || destination >= selection.length) {
    return selection;
  }
  const reordered = [...selection];
  [reordered[index], reordered[destination]] = [reordered[destination], reordered[index]];
  return reordered;
}

interface AssetResult {
  projectId: number | null;
  attempt: number;
  references: AssetReference[];
  error: string | null;
}

interface ProjectResult {
  attempt: number;
  projects: Project[];
  error: string | null;
}

export function useAssetReferencePickerData(projectId: number | null, projectLocked: boolean) {
  const [assetAttempt, setAssetAttempt] = useState(0);
  const [projectAttempt, setProjectAttempt] = useState(0);
  const [assetResult, setAssetResult] = useState<AssetResult | null>(null);
  const [projectResult, setProjectResult] = useState<ProjectResult | null>(null);

  useEffect(() => {
    let active = true;
    assetApi.listWithItems(projectId)
      .then((assets) => {
        if (active) {
          // The null API query spans authorized projects; null is not a personal-only query.
          const references = assets
            .filter((asset) => asset.projectId === projectId)
            .flatMap(assetReferences);
          setAssetResult({ projectId, attempt: assetAttempt, references, error: null });
        }
      })
      .catch((error: unknown) => {
        if (active) {
          setAssetResult({
            projectId,
            attempt: assetAttempt,
            references: [],
            error: getApiErrorMessage(error),
          });
        }
      });
    return () => {
      active = false;
    };
  }, [projectId, assetAttempt]);

  useEffect(() => {
    if (projectLocked) {
      return;
    }
    let active = true;
    projectApi.list()
      .then((projects) => {
        if (active) {
          setProjectResult({ attempt: projectAttempt, projects, error: null });
        }
      })
      .catch((error: unknown) => {
        if (active) {
          setProjectResult({ attempt: projectAttempt, projects: [], error: getApiErrorMessage(error) });
        }
      });
    return () => {
      active = false;
    };
  }, [projectLocked, projectAttempt]);

  const currentAssets = assetResult?.projectId === projectId && assetResult.attempt === assetAttempt
    ? assetResult
    : null;
  const currentProjects = projectResult?.attempt === projectAttempt ? projectResult : null;

  return {
    references: currentAssets === null ? [] : currentAssets.references,
    loadingAssets: currentAssets === null,
    assetError: currentAssets === null ? null : currentAssets.error,
    retryAssets: () => setAssetAttempt((attempt) => attempt + 1),
    projects: currentProjects === null ? [] : currentProjects.projects,
    loadingProjects: !projectLocked && currentProjects === null,
    projectError: currentProjects === null ? null : currentProjects.error,
    retryProjects: () => setProjectAttempt((attempt) => attempt + 1),
  };
}
