# 剧本 Markdown 支持

整部剧本和分集原文支持粘贴纯文本或 Markdown，也可从 UTF-8 的 `.md`、`.markdown` 文件导入。源码可编辑、可预览，保存时保留格式。分集详情和「AI 解析该集」展示已保存原文，解析使用已确认的分集版本；整部剧本解析同时识别 Markdown 中的分集与场次。

网页、内部解析工具与对外 MCP 统一使用 `rawContent` 表示原文，`synopsis` 表示分集剧情概述。MCP 分集列表和详情可传 `includeRawContent: false` 省略原文。
