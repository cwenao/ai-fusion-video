# 分镜、资产 MCP 与视频工具

已有 `/api/mcp` 连接地址和项目凭证继续使用。工具名和参数由 `tools/list` 发现。所有业务 ID 使用十进制字符串；所有写工具需要 `projectId`、可写凭证和 `requestId`。根据详情给出的版本提交，网络重试保留原参数与请求 ID。

| 用途 | 工具及参数 |
| --- | --- |
| 剧本场次 | `fusion_script_list/get/create/update/delete` 传 `kind: "scene"`；支持场次标题、时空、人物、场景描写、对白、主资产引用、状态。用 `fusion_script_reorder_scenes` 传分集 `parentId`、`expectedParentVersion`、完整 `childIds` 排序。 |
| 分镜分集、场次和镜头 | `fusion_storyboard_list/get/create/update` 通过 `kind` 选资源。镜头可写画面、台词、声音、运镜、时长、子资产引用、各提示词、状态和 AI 标记；创建时写 `fields.customData`，更新时写 `patch.customDataChanges`。传 `null` 删除单个自定义键。 |
| 分镜集合 | `fusion_storyboard_reorder` 传 `parentKind`、`parentId`、`expectedParentVersion`、完整 `childIds`；分镜主档排序分集、分集排序场次、场次排序镜头。`fusion_storyboard_delete` 传 `kind: "shot" / "scene"`、`id` 和 `expectedVersion`。删场次会同时删除其所属镜头。 |
| 资产主档与资产项 | `fusion_asset_get` 返回 `fields.description`（主档设定）与 `fields.properties`（扩展设定）。主档、资产项可用 `fusion_asset_create` 的 `fields.properties` 新建属性。更新使用 `fusion_asset_update` 的 `patch.propertyChanges`，省略项保留，显式 `null` 删除单个键。 |
| 生图提示词、外观 | 资产项写 `patch.propertyChanges.appearanceDescription` 和 `patch.propertyChanges.imagePrompt`，并传资产项 `expectedVersion`、主资产 `expectedParentVersion`。也可用 `fusion_prompt_get/set` 的 `targetKind: "assetItem"`、`slot: "imagePrompt"` 读写单槽；`text: null` 清空。 |
| 绑定原件 | `fusion_media_bind` 传 `bindings: [{slot, mediaResourceId}]`，`mediaResourceId: null` 清空槽位。镜头新增 `generatedImage`、`generatedVideo`、`videoPoster`，原有首帧、尾帧、参考图等槽位继续使用。详情从 `mediaBindings` 读取状态与托管 ID，再用 `fusion_media_get` 获取下载路径。 |

镜头关系使用资产项 ID；剧本场次关系使用资产主档 ID。分镜的自动镜号、祖先版本、合成任务和配乐状态可读，由现有业务流程维护。历史外链沿用媒体状态契约，以 `unmanaged` 标记。MCP 设定更新不接收媒体地址，也不会覆盖媒体绑定。

分镜表格、卡片和详情提供「打开视频工具」。目标页按项目与镜头版本加载已保存的提示词、首尾帧、参考图和关联资产原件。视频海报不充当图片或视频原件。工具优先选择能容纳素材的模型；没有兼容模型时仍保留素材并提示调整，用户确认后使用生成工具提交。
