"use client";

import { useId } from "react";
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { ASSET_IMAGE_PROMPT_TEMPLATES } from "./asset-image-prompt-templates";

export function AssetImagePromptTemplateSelect({ label, value, disabled, onChange }: {
  label: string;
  value: string;
  disabled?: boolean;
  onChange: (value: string) => void;
}) {
  const field = useId();
  return (
    <div className="flex min-w-0 flex-col gap-2">
      <label htmlFor={field} className="text-sm font-medium">{label}</label>
      <Select items={ASSET_IMAGE_PROMPT_TEMPLATES} value={value} disabled={disabled}
        onValueChange={(next) => {
          if (next !== null) {
            onChange(next);
          }
        }}>
        <SelectTrigger id={field} className="w-full">
          <SelectValue placeholder="选择提示词模板" />
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            {ASSET_IMAGE_PROMPT_TEMPLATES.map(({ value: id, label: name }) => (
              <SelectItem key={id} value={id}>{name}</SelectItem>
            ))}
          </SelectGroup>
        </SelectContent>
      </Select>
    </div>
  );
}
