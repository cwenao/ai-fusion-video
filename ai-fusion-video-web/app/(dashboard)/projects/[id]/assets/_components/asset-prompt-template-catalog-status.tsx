import { Button } from "@/components/ui/button";
import type { usePromptTemplates } from "@/hooks/use-prompt-templates";

export function AssetPromptTemplateCatalogStatus({ catalog, reload }: ReturnType<typeof usePromptTemplates>) {
  if (catalog.status === "loading") {
    return <p role="status" className="text-xs text-muted-foreground">正在加载自定义模板…</p>;
  }
  if (catalog.status === "error") {
    return (
      <div className="flex flex-wrap items-center gap-2">
        <p role="alert" className="text-sm text-destructive">加载自定义模板失败。{catalog.error}</p>
        <Button type="button" variant="outline" onClick={reload}>重试</Button>
      </div>
    );
  }
  return null;
}
