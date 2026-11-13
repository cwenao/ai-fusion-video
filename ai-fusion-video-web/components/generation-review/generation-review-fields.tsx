"use client";

import { useId } from "react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import type { GenerationReviewChoice } from "./use-generation-review-choice";

export function GenerationReviewFields({
  choice, disabled, missingPrompt,
}: {
  choice: GenerationReviewChoice;
  disabled?: boolean;
  missingPrompt?: boolean;
}) {
  const hint = useId();
  const field = useId();
  const items = choice.options.state === "ready"
    ? choice.options.items.map((item) => ({ value: String(item.id), label: item.name })) : [];
  return (
    <div className="flex min-w-0 flex-col gap-2 p-1">
      <label className="flex w-fit items-center gap-2 text-sm">
        <Checkbox
          checked={choice.checked}
          onCheckedChange={choice.setEnabled}
          disabled={disabled}
          aria-describedby={choice.checked ? hint : undefined}
        />
        生成后 AI 审查
      </label>
      {choice.checked && (
        <>
          <p id={hint} className="text-xs text-muted-foreground">
            每份结果会交给审查模型核对，生成完成后可直接使用素材。
          </p>
          {choice.options.state === "loading" && <p role="status" className="text-xs text-muted-foreground">正在加载审查模型…</p>}
          {choice.options.state === "error" && (
            <div className="flex flex-wrap items-center gap-2">
              <p role="alert" className="text-xs text-destructive">无法加载审查模型。</p>
              <Button type="button" variant="outline" size="sm" onClick={choice.retry} disabled={disabled}>重试</Button>
            </div>
          )}
          {choice.options.state === "ready" && items.length === 0 && (
            <p role="alert" className="text-xs text-destructive">
              请在模型配置中启用支持对应媒体输入的对话模型。
            </p>
          )}
          {items.length > 0 && (
            <div className="flex min-w-0 flex-col gap-2">
              <label htmlFor={field} className="text-xs font-medium">审查模型</label>
              <Select
                items={items}
                value={choice.modelId === undefined ? null : String(choice.modelId)}
                onValueChange={(value) => choice.select(value === null ? undefined : Number(value))}
                disabled={disabled}
              >
                <SelectTrigger id={field} className="w-full min-w-0">
                  <SelectValue placeholder="选择审查模型" />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {items.map((item) => <SelectItem key={item.value} value={item.value}>{item.label}</SelectItem>)}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </div>
          )}
          {missingPrompt && <p role="alert" className="text-xs text-destructive">请填写提示词后再提交审查。</p>}
        </>
      )}
    </div>
  );
}
