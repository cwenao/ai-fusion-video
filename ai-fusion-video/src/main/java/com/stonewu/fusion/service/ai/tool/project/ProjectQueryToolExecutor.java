package com.stonewu.fusion.service.ai.tool.project;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.project.Project;
import com.stonewu.fusion.mapper.project.ProjectMapper;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.canvas.CanvasAccessPolicy;
import com.stonewu.fusion.service.project.ProjectArtStyleInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 查询项目详情工具（get_project）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectQueryToolExecutor implements ToolExecutor {

    private final ProjectMapper projectMapper;
    private final CanvasAccessPolicy access;
    private final ProjectArtStyleInfo artStyles;

    @Override
    public String getToolName() {
        return "get_project";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getDisplayName() {
        return "查询项目详情";
    }

    @Override
    public String getToolDescription() {
        return """
                查询指定项目的详细信息，包括项目名称、描述、封面、创建时间，
                以及项目画风配置（artStyleInfo：画风描述、英文提示词、参考图URL）。
                仅能查询当前用户有权限访问的项目。
                """;
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "projectId": {
                            "type": "integer",
                            "description": "项目ID（必填）"
                        }
                    },
                    "required": ["projectId"]
                }
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            Long projectId = params.getLong("projectId");
            if (projectId == null) {
                return JSONUtil.createObj().set("status", "error").set("message", "缺少 projectId").toString();
            }

            access.requireProject(projectId, context.requireMutationContext().actor());
            Project project = projectMapper.selectById(projectId);
            if (project == null) {
                return JSONUtil.createObj().set("status", "error").set("message", "项目已删除").toString();
            }

            JSONObject result = JSONUtil.createObj()
                    .set("projectId", project.getId())
                    .set("version", project.getVersion())
                    .set("name", project.getName())
                    .set("description", project.getDescription())
                    .set("coverUrl", project.getCoverUrl())
                    .set("properties", project.getProperties())
                    .set("ownerType", project.getOwnerType())
                    .set("ownerId", project.getOwnerId())
                    .set("createTime", project.getCreateTime())
                    .set("updateTime", project.getUpdateTime());

            // 附加画风信息
            result.set("artStyleInfo", artStyles.describe(project));

            return result.toString();
        } catch (Exception e) {
            log.error("查询项目失败", e);
            return JSONUtil.createObj().set("status", "error").set("message", "查询失败: " + e.getMessage()).toString();
        }
    }
}
