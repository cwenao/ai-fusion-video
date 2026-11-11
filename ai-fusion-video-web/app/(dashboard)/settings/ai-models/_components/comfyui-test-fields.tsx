"use client";

import { useId, useRef, useState } from "react";
import { Upload } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import { getApiErrorMessage } from "@/lib/api/api-error";
import { uploadMedia } from "@/lib/api/media";
import {
  getComfyUiTestFieldLabel,
  getComfyUiTestMediaPolicy,
  validateComfyUiTestUpload,
  type ComfyUiTestField,
} from "@/lib/comfyui-test-media";

export function ComfyUiTestFields({
  fields,
  values,
  disabled,
  onChange,
  onAppend,
  onUploading,
}: {
  fields: ComfyUiTestField[];
  values: Record<string, string>;
  disabled: boolean;
  onChange: (field: string, value: string) => void;
  onAppend: (field: string, references: string[]) => void;
  onUploading: (field: string, uploading: boolean) => void;
}) {
  return (
    <div className="grid min-w-0 gap-4 p-1 md:grid-cols-2">
      {fields.map((field) => (
        <TestField
          key={field.name}
          field={field}
          value={values[field.name] ?? ""}
          disabled={disabled}
          onChange={(value) => onChange(field.name, value)}
          onAppend={(refs) => onAppend(field.name, refs)}
          onUploading={(busy) => onUploading(field.name, busy)}
        />
      ))}
    </div>
  );
}

function TestField({
  field,
  value,
  disabled,
  onChange,
  onAppend,
  onUploading,
}: {
  field: ComfyUiTestField;
  value: string;
  disabled: boolean;
  onChange: (value: string) => void;
  onAppend: (refs: string[]) => void;
  onUploading: (uploading: boolean) => void;
}) {
  const id = useId();
  const input = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const media = getComfyUiTestMediaPolicy(field.type);
  const list = field.type === "string_list" || !!media;
  const label = getComfyUiTestFieldLabel(field.name);
  const requirement = field.requiredCount === 1
    ? "（第 1 项必填）"
    : field.requiredCount > 1 ? `（前 ${field.requiredCount} 项必填）` : "（可选）";
  const describedBy = [media && `${id}-help`, error && `${id}-error`]
    .filter(Boolean).join(" ");
  const upload = async (files: File[]) => {
    if (files.length === 0) {
      return;
    }
    setUploading(true);
    onUploading(true);
    setError(null);
    try {
      validateComfyUiTestUpload(field, value, files);
      for (const file of files) {
        const resource = await uploadMedia(file, { requestId: crypto.randomUUID() });
        onAppend([`media://${resource.id}`]);
      }
    } catch (failure) {
      setError(getApiErrorMessage(failure));
    } finally {
      setUploading(false);
      onUploading(false);
    }
  };
  return (
    <div className="flex min-w-0 flex-col gap-2">
      <Label htmlFor={id}>{label}{media && requirement}</Label>
      {list ? (
        <Textarea
          id={id}
          value={value}
          disabled={disabled || uploading}
          aria-required={field.requiredCount > 0}
          aria-invalid={!!error}
          aria-describedby={describedBy || undefined}
          onChange={(event) => {
            setError(null);
            onChange(event.target.value);
          }}
          placeholder={media ? "每行一项，可填写媒体引用、URL 或 Data URI" : "每行一项"}
        />
      ) : (
        <Input
          id={id}
          type={field.type === "integer" || field.type === "number" ? "number" : "text"}
          step={field.type === "integer" ? "1" : "any"}
          value={value}
          disabled={disabled}
          placeholder={field.type === "boolean"
            ? "true 或 false"
            : field.name === "aspectRatio"
              ? "例如 16:9，留空使用已保存值"
              : field.name === "resolutionMultiple"
                ? "例如 32，留空使用已保存值"
                : "留空使用工作流中保存的值"}
          onChange={(event) => onChange(event.target.value)}
        />
      )}
      {media && (
        <>
          <p id={`${id}-help`} className="text-xs text-muted-foreground">
            最多 {field.maximumCount} 项，每项不超过 {media.maxMegabytes}MB。
            {field.requiredCount > 0 && field.requiredCount < field.maximumCount && "其余可选。"}
          </p>
          <input
            ref={input}
            type="file"
            multiple={field.maximumCount !== 1}
            accept={`${media.mimePrefix}*`}
            className="hidden"
            aria-label={`上传${label}`}
            disabled={disabled || uploading}
            onChange={(event) => {
              const files = Array.from(event.target.files ?? []);
              event.target.value = "";
              void upload(files);
            }}
          />
          <Button
            variant="outline"
            size="sm"
            className="self-start"
            disabled={disabled || uploading}
            onClick={() => input.current?.click()}
          >
            <Upload />
            {uploading ? "上传中…" : `上传${media.label}`}
          </Button>
        </>
      )}
      {error && (
        <p id={`${id}-error`} role="alert" className="text-xs text-destructive">
          {error}
        </p>
      )}
    </div>
  );
}
