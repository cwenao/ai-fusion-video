package com.stonewu.fusion.service.ai.tool.storyboard;

import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GetStoryboardItemToolExecutor implements ToolExecutor {
    private final StoryboardToolQueries tools;

    @Override
    public String getToolName() {
        return "get_storyboard_item";
    }

    @Override
    public String getDisplayName() {
        return "查询分镜镜头";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getToolDescription() {
        return """
                按镜头 ID 查询单个分镜镜头。直接返回该镜头的 storyboardItemId、version、画面、对白、
                已保存的视频提示词、首尾帧和角色、道具、场景参考图，以及所属场次的基本信息。
                """;
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "storyboardItemId": { "type": "integer", "description": "目标分镜镜头 ID" }
                    },
                    "required": ["storyboardItemId"],
                    "additionalProperties": false
                }
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        return tools.execute(getToolName(), toolInput, context);
    }
}
