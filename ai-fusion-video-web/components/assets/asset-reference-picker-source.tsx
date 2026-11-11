"use client";

import { useId } from "react";
import { Lock } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import type { Project } from "@/lib/api/project";

interface AssetReferencePickerSourceProps {
  projectId: number | null;
  locked: boolean;
  projects: Project[];
  loading: boolean;
  error: string | null;
  disabled: boolean;
  selectionCount: number;
  onChange: (projectId: number | null) => void;
  onRetry: () => void;
}

export function AssetReferencePickerSource({
  projectId,
  locked,
  projects,
  loading,
  error,
  disabled,
  selectionCount,
  onChange,
  onRetry,
}: AssetReferencePickerSourceProps) {
  const id = useId();
  const items = [
    { value: "standalone", label: "非项目资产" },
    ...projects.map((project) => ({ value: String(project.id), label: project.name })),
  ];

  return (
    <div className="flex min-w-0 shrink-0 flex-col gap-2 p-1">
      <label id={id + "-label"} htmlFor={id} className="text-xs font-medium text-muted-foreground">资产来源</label>
      {locked ? (
        <div className="flex min-h-9 items-center gap-2 text-sm" aria-labelledby={id + "-label"}>
          <Lock className="size-4 text-muted-foreground" aria-hidden="true" />
          {projectId === null ? "非项目资产" : "当前项目"}
        </div>
      ) : (
        <Select
          items={items}
          value={projectId === null ? "standalone" : String(projectId)}
          disabled={disabled || loading}
          onValueChange={(value) => {
            if (value !== null) {
              onChange(value === "standalone" ? null : Number(value));
            }
          }}
        >
          <SelectTrigger
            id={id}
            aria-labelledby={id + "-label"}
            aria-describedby={id + "-help"}
            className="w-full text-sm sm:w-80"
          >
            <SelectValue placeholder={loading ? "加载项目…" : "选择资产来源"} />
          </SelectTrigger>
          <SelectContent className="text-sm">
            <SelectGroup>
              {items.map((item) => (
                <SelectItem key={item.value} value={item.value} className="text-sm">{item.label}</SelectItem>
              ))}
            </SelectGroup>
          </SelectContent>
        </Select>
      )}
      <p id={id + "-help"} className="text-xs text-muted-foreground">
        {locked ? "仅显示当前来源的素材。" : selectionCount > 0 ? "已选素材属于同一来源，清空已选后可切换。" : "单次只能选择同一项目或非项目资产的素材。"}
      </p>
      {!locked && error !== null && (
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <p role="alert" className="break-words text-sm text-destructive">项目加载失败：{error}</p>
          <Button type="button" variant="outline" disabled={disabled || loading} onClick={onRetry}>重试项目</Button>
        </div>
      )}
    </div>
  );
}
