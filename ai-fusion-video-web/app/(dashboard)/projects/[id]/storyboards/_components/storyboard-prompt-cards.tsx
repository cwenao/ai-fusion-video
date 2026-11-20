"use client";

import { useId, useState } from "react";
import { ChevronRight } from "lucide-react";
import type { Project } from "@/lib/api/project";
import type { StoryboardPromptField, StoryboardScene } from "@/lib/api/storyboard";
import { StoryboardPromptEditor } from "./prompt-editor/storyboard-prompt-editor";
import { storyboardPromptFields, storyboardPromptLabels, type StoryboardPromptEditorData } from "./prompt-editor/storyboard-prompt-fields";

export function StoryboardPromptCards(props: StoryboardPromptEditorData & {
  projectId: number;
  project?: Project | null;
  scene?: StoryboardScene;
}) {
  const [editing, setEditing] = useState<StoryboardPromptField | null>(null);
  const heading = useId();
  return (
    <section aria-labelledby={heading} className="flex min-w-0 flex-col gap-2 p-4">
      <h3 id={heading} className="text-sm font-semibold">提示词</h3>
      <ul className="grid gap-2">
        {storyboardPromptFields.map((field) => (
          <li key={field}>
            <button type="button" aria-haspopup="dialog" onClick={() => setEditing(field)}
              className="flex w-full min-w-0 flex-col gap-1 rounded-xl border border-border/30 bg-card p-3 text-left transition-colors hover:border-border/50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring">
              <span className="flex w-full items-center justify-between gap-2 text-sm font-medium">
                {storyboardPromptLabels[field]}
                <ChevronRight aria-hidden className="size-4 shrink-0 text-muted-foreground" />
              </span>
              <span className="line-clamp-2 text-xs leading-relaxed wrap-anywhere text-muted-foreground">
                {props.item[field]?.trim() || "点击编写"}
              </span>
            </button>
          </li>
        ))}
      </ul>
      {editing && <StoryboardPromptEditor {...props} initialField={editing} onClose={() => setEditing(null)} />}
    </section>
  );
}
