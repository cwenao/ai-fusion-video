import { usePipelineStore } from "@/lib/store/pipeline-store";
import type { PipelineTask } from "@/lib/store/pipeline-types";

interface ProjectTextRequest {
  projectId: number;
  kind: "storyboardPrompt" | "storyboardPromptChat" | "assetAppearance" | "assetImagePrompt";
  title: string;
  context: Record<string, unknown>;
  draft: string;
  instruction: string;
  conversationId: string;
}

export function enqueueProjectText({ projectId, kind, title, context, draft, instruction, conversationId }: ProjectTextRequest) {
  return usePipelineStore.getState().addPipeline({
    label: title,
    projectId,
    request: {
      agentType: "project_text_editor",
      category: "pipeline",
      conversationId,
      projectId,
      title,
      toolExecutionMode: "DEFAULT",
      message: JSON.stringify({ kind, context, draft, instruction }),
    },
  });
}

export function projectTextOutput(task: PipelineTask | undefined): string {
  return task?.status === "done"
    ? task.state.timeline.flatMap((entry) => entry.type === "content" ? [entry.text] : []).join("").trim()
    : "";
}
