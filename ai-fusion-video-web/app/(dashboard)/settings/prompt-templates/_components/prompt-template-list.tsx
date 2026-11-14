import { FileText, Pencil, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { PROMPT_TEMPLATE_CATEGORIES, type PromptTemplate } from "@/lib/api/prompt-template";

export function PromptTemplateList({ templates, disabled, onEdit, onDelete }: {
  templates: PromptTemplate[];
  disabled: boolean;
  onEdit: (template: PromptTemplate) => void;
  onDelete: (template: PromptTemplate) => void;
}) {
  if (templates.length === 0) {
    return (
      <div className="flex flex-col items-center gap-2 rounded-lg border border-border/20 bg-background/70 p-6 text-center">
        <FileText className="size-5 text-muted-foreground" aria-hidden />
        <p className="text-sm text-muted-foreground">暂无自定义模板</p>
      </div>
    );
  }

  return (
    <ul className="divide-y divide-border/20">
      {templates.map((template) => (
        <li key={template.id} className="flex flex-wrap items-center justify-between gap-3 px-1 py-3">
          <div className="min-w-0 flex-1">
            <h3 className="wrap-anywhere text-sm font-medium">{template.name}</h3>
            <p className="text-xs text-muted-foreground">
              {PROMPT_TEMPLATE_CATEGORIES.find(({ value }) => value === template.category)?.label}
            </p>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            <Button variant="outline" size="icon" aria-label={`编辑 ${template.name}`} title="编辑"
              disabled={disabled} onClick={() => onEdit(template)}><Pencil /></Button>
            <Button variant="destructive-ghost" size="icon" aria-label={`删除 ${template.name}`} title="删除"
              disabled={disabled} onClick={() => onDelete(template)}><Trash2 /></Button>
          </div>
        </li>
      ))}
    </ul>
  );
}
