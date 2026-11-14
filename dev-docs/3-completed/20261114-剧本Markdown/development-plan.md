# 剧本 Markdown 支持

整部剧本和分集原文支持粘贴纯文本或 Markdown，也可从 UTF-8 的 `.md`、`.markdown` 文件导入。源码可编辑、可预览，保存时保留格式。分集详情和「AI 解析该集」展示已保存原文，解析使用已确认的分集版本；整部剧本解析同时识别 Markdown 中的分集与场次。

网页、数据库和内部解析工具沿用 `rawContent` 保存、读取原文。对外 MCP 的 `rawContent` 与 `originalText` 同义，`synopsis` 只表示剧情概述。两个原文字段同传时必须完全相同；`includeRawContent: false` 同时省略两个字段。
