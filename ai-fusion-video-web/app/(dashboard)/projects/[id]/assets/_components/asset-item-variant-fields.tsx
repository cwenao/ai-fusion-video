"use client";

import { useId } from "react";
import { Input } from "@/components/ui/input";
import { MAX_ASSET_VARIANT_LABEL_LENGTH, type AssetItemVariant } from "@/lib/asset-item-variant";

const FIELDS = [
  { key: "timeline", label: "时间线", example: "例如：十年前、主线第 8 集" },
  { key: "appearanceState", label: "表现状态", example: "例如：受伤、换装、入夜" },
] as const;

export function AssetItemVariantFields({
  value,
  onChange,
  disabled,
}: {
  value: AssetItemVariant;
  onChange: (value: AssetItemVariant) => void;
  disabled: boolean;
}) {
  const id = useId();
  return (
    <div className="grid min-w-0 gap-3 sm:grid-cols-2">
      {FIELDS.map(({ key, label, example }) => (
        <div key={key} className="flex min-w-0 flex-col gap-2">
          <label htmlFor={`${id}-${key}`} className="text-xs font-medium text-muted-foreground">
            {label}
          </label>
          <Input
            id={`${id}-${key}`}
            value={value[key]}
            disabled={disabled}
            maxLength={MAX_ASSET_VARIANT_LABEL_LENGTH}
            placeholder={example}
            onChange={(event) => onChange({ ...value, [key]: event.target.value })}
          />
        </div>
      ))}
    </div>
  );
}
