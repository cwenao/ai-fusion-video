import { http } from "./client";

export const PROMPT_TEMPLATE_CATEGORIES = [
  { value: "general", label: "通用" },
  { value: "character", label: "角色" },
  { value: "scene", label: "场景" },
  { value: "prop", label: "道具" },
] as const;

export type PromptTemplateCategory = typeof PROMPT_TEMPLATE_CATEGORIES[number]["value"];

export interface PromptTemplateInput {
  name: string;
  category: PromptTemplateCategory;
  markdown: string;
}

export interface PromptTemplate extends PromptTemplateInput {
  id: string;
}

const url = "/api/system/prompt-templates";

export const promptTemplateApi = {
  list(signal: AbortSignal): Promise<PromptTemplate[]> {
    return http.get(url, { signal });
  },
  create(input: PromptTemplateInput): Promise<PromptTemplate> {
    return http.post(url, input);
  },
  update(id: string, input: PromptTemplateInput): Promise<PromptTemplate> {
    return http.put(`${url}/${id}`, input);
  },
  delete(id: string): Promise<boolean> {
    return http.delete(`${url}/${id}`);
  },
};
