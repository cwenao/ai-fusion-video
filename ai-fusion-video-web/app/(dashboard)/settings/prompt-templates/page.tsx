"use client";

import { useState } from "react";
import { Plus } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { usePromptTemplates } from "@/hooks/use-prompt-templates";
import { promptTemplateApi, type PromptTemplate, type PromptTemplateInput } from "@/lib/api/prompt-template";
import { toastApiError } from "@/lib/api/toast-api-error";
import { useAuthStore } from "@/lib/store/auth-store";
import { settingsTypography } from "../_shared";
import { PromptTemplateEditor } from "./_components/prompt-template-editor";
import { PromptTemplateList } from "./_components/prompt-template-list";

function PromptTemplateManagement() {
  const { catalog, reload } = usePromptTemplates();
  const { confirm } = useConfirm();
  const [editor, setEditor] = useState<{ template: PromptTemplate | null } | null>(null);
  const [removing, setRemoving] = useState(false);
  const busy = catalog.status === "loading" || removing || !!editor;

  async function save(input: PromptTemplateInput) {
    if (editor?.template) {
      await promptTemplateApi.update(editor.template.id, input);
    } else {
      await promptTemplateApi.create(input);
    }
    setEditor(null);
    reload();
    toast.success("模板已保存");
  }

  async function remove(template: PromptTemplate) {
    if (!await confirm({
      title: "删除提示词模板",
      description: `删除「${template.name}」？已生成的资产提示词会保留。`,
      variant: "destructive",
      confirmText: "删除",
    })) {
      return;
    }
    setRemoving(true);
    try {
      await promptTemplateApi.delete(template.id);
      reload();
      toast.success("模板已删除");
    } catch (error) {
      toastApiError(error, "删除模板失败");
    } finally {
      setRemoving(false);
    }
  }

  return (
    <section className="flex flex-col gap-3 rounded-xl border border-border/30 bg-card/50 p-4 backdrop-blur-sm">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className={settingsTypography.sectionTitle}>自定义模板</h2>
        <Button disabled={busy} onClick={() => setEditor({ template: null })}><Plus />新建模板</Button>
      </div>
      {catalog.status === "loading" && <p role="status" className="text-sm text-muted-foreground">正在加载模板…</p>}
      {catalog.status === "error" && (
        <div className="flex flex-wrap items-center gap-2">
          <p role="alert" className="text-sm text-destructive">加载模板失败。{catalog.error}</p>
          <Button variant="outline" onClick={reload}>重试</Button>
        </div>
      )}
      {catalog.status === "ready" && (
        <PromptTemplateList templates={catalog.templates} disabled={busy}
          onEdit={(template) => setEditor({ template })} onDelete={(template) => void remove(template)} />
      )}
      {editor && <PromptTemplateEditor template={editor.template} onClose={() => setEditor(null)} onSave={save} />}
    </section>
  );
}

export default function PromptTemplateSettingsPage() {
  const user = useAuthStore((state) => state.user);
  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className={settingsTypography.pageTitle}>提示词模板</h1>
        <p className={settingsTypography.pageDescription}>在资产管理中选用这些模板，生成角色、场景和道具的生图提示词。</p>
      </header>
      {user && (user.roles?.includes("admin")
        ? <PromptTemplateManagement />
        : <p role="status" className="text-sm text-muted-foreground">仅管理员可管理模板。</p>)}
    </div>
  );
}
