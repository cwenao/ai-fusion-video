package com.stonewu.fusion.service.ai.tool.script;

import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GetScriptEpisodeSourceToolExecutor implements ToolExecutor {
    private final ScriptToolQueries queries;

    @Override
    public String getToolName() {
        return "get_script_episode_source";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getDisplayName() {
        return "读取分集原文";
    }

    @Override
    public String getToolDescription() {
        return "读取分集已保存的 rawContent 和 episode_version，不读取场次和对白。若原文为空或确认版本有变化则返回错误。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "scriptEpisodeId": { "type": "integer", "description": "剧本分集 ID" },
                        "expectedVersion": { "type": "integer", "description": "用户确认的分集版本，确认弹窗启动时必须传入" }
                    },
                    "required": ["scriptEpisodeId"],
                    "additionalProperties": false
                }
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        return queries.execute(getToolName(), toolInput, context);
    }
}
