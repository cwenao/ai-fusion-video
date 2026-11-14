package com.stonewu.fusion.service.mcp;

import java.util.Locale;

enum McpProjectTool {
    WORKSPACE_GET(false, "读取项目、剧本和分镜主档 ID 与版本"),
    WORKSPACE_INITIALIZE(true, "补齐项目缺失的剧本或分镜主档"),
    SCRIPT_LIST(false, "分页读取剧本分集或场次。分集默认返回原文，可用 includeRawContent=false 省略"),
    SCRIPT_GET(false, "读取剧本、分集或场次详情。分集默认返回原文，剧本主档可用 includeRawContent=true 读取原文"),
    SCRIPT_CREATE(true, "在剧本或分集中追加分集或场次"),
    SCRIPT_UPDATE(true, "按版本更新剧本、分集或场次"),
    STORYBOARD_LIST(false, "分页读取分镜分集、场景或镜头"),
    STORYBOARD_GET(false, "读取分镜主档、分集、场景或镜头详情"),
    STORYBOARD_CREATE(true, "在分镜父对象下追加分集、场景或镜头"),
    STORYBOARD_UPDATE(true, "按版本更新分镜内容"),
    STORYBOARD_APPEND_SHOTS(true, "在同一场景内原子追加一批镜头"),
    PROMPT_GET(false, "读取指定镜头或资产的提示词槽位和版本"),
    PROMPT_SET(true, "按版本首次填写、修改或清空指定提示词槽位"),
    ASSET_LIST(false, "分页读取项目资产或资产变体"),
    ASSET_GET(false, "读取项目资产或资产变体的详情"),
    ASSET_CREATE(true, "创建项目资产及初始资产项，或追加资产变体"),
    ASSET_UPDATE(true, "按版本更新资产或资产项元数据"),
    MEDIA_GET(false, "读取项目媒体原件元数据和下载路径"),
    MEDIA_BIND(true, "按版本绑定已上传的原件或替换指定槽位");

    final boolean write;
    final String description;

    McpProjectTool(boolean write, String description) {
        this.write = write;
        this.description = description;
    }

    String toolName() {
        return "fusion_" + name().toLowerCase(Locale.ROOT);
    }

    boolean canReplaceContent() {
        return switch (this) {
            case SCRIPT_UPDATE, STORYBOARD_UPDATE, PROMPT_SET, ASSET_UPDATE, MEDIA_BIND -> true;
            default -> false;
        };
    }
}
