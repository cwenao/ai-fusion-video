package com.stonewu.fusion.service.mcp;

import java.util.Locale;

enum McpProjectTool {
    WORKSPACE_GET(false, "读取项目、剧本和分镜主档 ID 与版本"),
    WORKSPACE_INITIALIZE(true, "补齐项目缺失的剧本或分镜主档"),
    SCRIPT_LIST(false, "分页读取分集或场次。分集剧情概述为 synopsis，剧本原文为 rawContent，可用 includeRawContent=false 省略原文"),
    SCRIPT_GET(false, "读取剧本、分集或场次详情。分集默认返回剧情概述 synopsis 和剧本原文 rawContent；主档可用 includeRawContent=true 读取原文"),
    SCRIPT_BATCH_GET(false, "按 ID 批量读取同类剧本对象的完整详情"),
    SCRIPT_CREATE(true, "在剧本或分集中追加分集或场次"),
    SCRIPT_BATCH_CREATE(true, "在同一剧本或分集中批量创建分集或场次，整批提交"),
    SCRIPT_UPDATE(true, "按版本更新剧本、分集或场次"),
    SCRIPT_BATCH_UPDATE(true, "按目标版本批量更新同类剧本对象，整批提交"),
    SCRIPT_DELETE(true, "按版本删除剧本场次"),
    SCRIPT_BATCH_DELETE(true, "在同一项目中按版本批量删除剧本场次，整批提交"),
    SCRIPT_REORDER_SCENES(true, "按剧本分集版本调整直属场次顺序，须传完整 ID 列表"),
    STORYBOARD_LIST(false, "分页读取分镜分集、场景或镜头"),
    STORYBOARD_GET(false, "读取分镜主档、分集、场景或镜头详情，包括提示词、自定义内容和媒体绑定"),
    STORYBOARD_BATCH_GET(false, "按 ID 批量读取同类分镜对象的完整详情"),
    STORYBOARD_CREATE(true, "在分镜父对象下追加分集、场景或镜头"),
    STORYBOARD_BATCH_CREATE(true, "在同一父对象下批量创建分镜分集、场景或镜头，整批提交"),
    STORYBOARD_UPDATE(true, "按版本更新分镜内容"),
    STORYBOARD_BATCH_UPDATE(true, "按目标版本批量更新同类分镜对象，整批提交"),
    STORYBOARD_REORDER(true, "按父对象版本调整直属分集、场次或镜头的顺序，须传完整 ID 列表"),
    STORYBOARD_DELETE(true, "按版本删除镜头或删除场次及场次内镜头"),
    STORYBOARD_BATCH_DELETE(true, "按版本批量删除镜头或分镜场景，删除场景会同时删除其镜头"),
    STORYBOARD_APPEND_SHOTS(true, "在同一场景内原子追加一批镜头"),
    PROMPT_GET(false, "读取指定镜头或资产的提示词槽位和版本"),
    PROMPT_BATCH_GET(false, "批量读取同类镜头或资产的指定提示词槽位和版本"),
    PROMPT_SET(true, "按版本首次填写、修改或清空指定提示词槽位"),
    PROMPT_BATCH_SET(true, "按版本批量填写、修改或清空同类对象的指定提示词槽位，整批提交"),
    ASSET_LIST(false, "分页读取项目资产，或按父资产读取子资产及其时间线和表现状态"),
    ASSET_GET(false, "读取主资产设定，或子资产的时间线、表现状态、外观描述和生图提示词"),
    ASSET_BATCH_GET(false, "按 ID 批量读取主资产或子资产的完整详情"),
    ASSET_CREATE(true, "一次创建主资产及其多个子资产，或向已有主资产追加一个子资产"),
    ASSET_BATCH_CREATE(true, "批量创建主资产，或在同一主资产下批量追加子资产，整批提交"),
    ASSET_UPDATE(true, "按版本更新资产设定，或更新子资产的时间线、表现状态、外观和生图提示词"),
    ASSET_BATCH_UPDATE(true, "按版本批量更新主资产或子资产，整批提交"),
    ASSET_BATCH_DELETE(true, "按版本批量删除主资产及其子资产，或仅删除同一主资产下指定的子资产"),
    MEDIA_GET(false, "读取项目媒体原件元数据和下载路径"),
    MEDIA_BATCH_GET(false, "按媒体 ID 批量读取项目原件的元数据和下载路径"),
    MEDIA_BIND(true, "按版本绑定已上传的原件、替换或清空指定槽位"),
    MEDIA_BATCH_BIND(true, "按版本批量绑定、替换或清空同类对象的媒体槽位，整批提交");

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
            case SCRIPT_UPDATE, SCRIPT_BATCH_UPDATE, SCRIPT_DELETE, SCRIPT_BATCH_DELETE, SCRIPT_REORDER_SCENES,
                 STORYBOARD_UPDATE, STORYBOARD_BATCH_UPDATE, STORYBOARD_DELETE, STORYBOARD_BATCH_DELETE, STORYBOARD_REORDER,
                 PROMPT_SET, PROMPT_BATCH_SET, ASSET_UPDATE, ASSET_BATCH_UPDATE, ASSET_BATCH_DELETE,
                 MEDIA_BIND, MEDIA_BATCH_BIND -> true;
            default -> false;
        };
    }
}
