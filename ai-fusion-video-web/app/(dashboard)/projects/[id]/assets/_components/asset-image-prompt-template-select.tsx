"use client";

import { useId } from "react";
import { Select, SelectContent, SelectGroup, SelectItem, SelectLabel, SelectTrigger, SelectValue } from "@/components/ui/select";
import type { AssetImagePromptTemplate } from "./asset-image-prompt-templates";

export function AssetImagePromptTemplateSelect({ label, value, templates, disabled, onChange }: {
  label: string;
  value: string | null;
  templates: AssetImagePromptTemplate[];
  disabled?: boolean;
  onChange: (value: string) => void;
}) {
  const field = useId();
  const groups = [
    { label: "内置模板", items: templates.filter((template) => "sections" in template) },
    { label: "自定义模板", items: templates.filter((template) => "markdown" in template) },
  ];
  return (
    <div className="flex min-w-0 flex-col gap-2">
      <label htmlFor={field} className="text-sm font-medium">{label}</label>
      <Select items={templates} value={value} disabled={disabled}
        onValueChange={(next) => {
          if (next !== null) {
            onChange(next);
          }
        }}>
        <SelectTrigger id={field} className="w-full">
          <SelectValue placeholder="选择提示词模板" />
        </SelectTrigger>
        <SelectContent>
          {groups.filter(({ items }) => items.length > 0).map((group) => (
            <SelectGroup key={group.label}>
              <SelectLabel>{group.label}</SelectLabel>
              {group.items.map(({ value: id, label: name }) => (
                <SelectItem key={id} value={id}>{name}</SelectItem>
              ))}
            </SelectGroup>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
