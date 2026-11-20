import type { StoryboardItem, StoryboardPromptField } from "@/lib/api/storyboard";

export const storyboardPromptLabels = {
  videoPrompt: "视频提示词",
  firstFramePrompt: "首帧提示词",
  lastFramePrompt: "尾帧提示词",
} satisfies Record<StoryboardPromptField, string>;

export const storyboardPromptFields = Object.keys(storyboardPromptLabels) as StoryboardPromptField[];

export interface StoryboardPromptEditorData {
  item: StoryboardItem;
  onSave: (item: StoryboardItem, field: StoryboardPromptField, value: string | null) => Promise<void>;
}
