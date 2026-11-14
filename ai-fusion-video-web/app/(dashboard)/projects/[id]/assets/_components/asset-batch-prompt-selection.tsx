"use client";

import { useState } from "react";
import { Check } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { getAssetImagePrompt } from "@/lib/asset-image-generation";
import type { AssetWithItems } from "@/lib/api/asset";
import { ASSET_TYPES } from "./asset-types";
import { ASSET_IMAGE_PROMPT_TEMPLATES } from "./asset-image-prompt-templates";
import type { BatchPromptTarget } from "./use-asset-batch-prompts";

export function AssetBatchPromptSelection({ assets, disabled, onGenerate }: {
  assets: AssetWithItems[];
  disabled: boolean;
  onGenerate: (targets: BatchPromptTarget[]) => void;
}) {
  const [checked, setChecked] = useState<Set<number>>(() => new Set());
  const [templates, setTemplates] = useState<Record<string, string>>({
    character: "character", scene: "scene", prop: "prop", other: "general",
  });
  const groups = [...ASSET_TYPES, { value: "other", label: "其他资产" }].map(({ value, label }) => ({
    value,
    label,
    assets: assets.filter((asset) => asset.items.length > 0 && (
      value === "other" ? !ASSET_TYPES.some((type) => type.value === asset.type) : asset.type === value
    )),
  })).filter((group) => group.assets.length > 0);
  const items = assets.flatMap((asset) => asset.items);
  const missing = items.filter((item) => !getAssetImagePrompt(item).trim());
  const selected = items.filter((item) => checked.has(item.id));
  const existing = selected.filter((item) => getAssetImagePrompt(item).trim()).length;

  function generate() {
    const targets = groups.flatMap((group) => {
      const template = ASSET_IMAGE_PROMPT_TEMPLATES.find(({ value }) => value === templates[group.value]);
      return template ? group.assets.flatMap((asset) => asset.items
        .filter((item) => checked.has(item.id)).map((item) => ({ asset, item, template }))) : [];
    });
    onGenerate(targets);
  }

  return (
    <div className="flex min-w-0 flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p role="status" className="text-sm text-muted-foreground">
          已选 {selected.length} 项{existing > 0 ? `，含 ${existing} 项已有提示词` : ""}
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="outline" disabled={disabled || missing.length === 0}
            onClick={() => setChecked(new Set(missing.map(({ id }) => id)))}>全选缺少提示词</Button>
          <Button variant="ghost" disabled={disabled || selected.length === 0}
            onClick={() => setChecked(new Set())}>清空选择</Button>
        </div>
      </div>
      {groups.map((group) => (
        <section key={group.value} className="flex min-w-0 flex-col gap-2 border-t border-border/20 pt-3">
          <h3 className="text-sm font-medium">{group.label}</h3>
          <div role="group" aria-label={`${group.label}模板`} className="flex flex-wrap gap-2">
            {ASSET_IMAGE_PROMPT_TEMPLATES.filter((template) => template.value === group.value
              || template.value === "general" || (group.value === "character" && template.value === "characterThreeView"))
              .map(({ value, label }) => (
                <Button key={value} variant="outline" aria-pressed={templates[group.value] === value} disabled={disabled}
                  onClick={() => setTemplates((current) => ({ ...current, [group.value]: value }))}>
                  {templates[group.value] === value && <Check />}
                  {label}
                </Button>
              ))}
          </div>
          <div className="flex min-w-0 flex-col gap-1">
            {group.assets.flatMap((asset) => asset.items.map((item) => {
              const inputId = `batch-prompt-item-${item.id}`;
              return (
                <div key={item.id} className="flex min-w-0 items-center gap-3 rounded-lg px-2 py-2">
                  <Checkbox id={inputId} checked={checked.has(item.id)} disabled={disabled}
                    onCheckedChange={(value) => setChecked((current) => {
                      const next = new Set(current);
                      if (value) {
                        next.add(item.id);
                      } else {
                        next.delete(item.id);
                      }
                      return next;
                    })} />
                  <label htmlFor={inputId} className="min-w-0 flex-1 cursor-pointer break-words text-sm">
                    {asset.name} / {item.name || "未命名子资产"}
                  </label>
                  {getAssetImagePrompt(item).trim() && <span className="shrink-0 text-xs text-muted-foreground">已有提示词</span>}
                </div>
              );
            }))}
          </div>
        </section>
      ))}
      <div className="flex justify-end">
        <Button variant="ai" disabled={disabled || selected.length === 0} onClick={generate}>一键生成（{selected.length}）</Button>
      </div>
    </div>
  );
}
