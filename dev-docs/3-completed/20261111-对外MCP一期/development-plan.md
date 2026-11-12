# 融光对外 MCP 一期实施方案

方案以实施前的 `3f9cc57` 为基线。实际交付与验收见[完成记录](completion-summary.md)。

## 一期目标

外部 Agent 在自己所在的客户端起草剧本、分镜和提示词，随后调用融光工具保存到一个已有项目。融光负责结构、关系、权限和版本校验，提交后沿用既有资源变更流更新网页和画布。项目成员在网页上修改后，Agent 通过相同资源 ID 读取新版本。

一期按受控团队接入设计。客户端须支持 HTTPS、Streamable HTTP、自定义 `Authorization: Bearer` 和配套的 HTTP 文件上传下载。创作数据覆盖剧本、分镜、对象上的提示词、项目资产与资产项。独立提示词库、融光代写和外部系统持续复制属于后续独立需求。

## 服务和项目鉴权

- Java 后端新增 `/api/mcp`，使用 MCP Java SDK `0.17.2` 的无状态 Servlet Streamable HTTP 传输。该传输在实际握手中协商为 `2025-06-18`，通过 `initialize`、`notifications/initialized`、`tools/list`、`tools/call`。现有 AgentScope 客户端通过针对性兼容测试。服务默认关闭，启用后按白名单注册下文的 19 个工具。部署时通过 HTTPS 入口接入，校验主机名和请求携带的 `Origin`。按凭证限流，触发限制时返回 `429` 和 `Retry-After`。 [MCP 传输](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports) · [MCP 生命周期](https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle)
- 使用独立的项目凭证。凭证固定 `userId`、`teamId`（个人范围可为空）、`projectId`、`scope`、有效期和撤销状态。现有网页登录身份负责签发自己的凭证。拥有读取权限可签发 `read`，拥有编辑权限可签发 `read_write`。`scope` 缺省为 `read`，有效期缺省 24 小时，最长 7 天。项目凭证用于 MCP 和本方案的媒体端点，管理端点继续使用网页登录凭证。
- `POST /api/project/{projectId}/mcp-credentials` 接受 `label`（1–80 字符）、`scope?`、`expiresInSeconds?`（1–604800），返回 `credentialId`、`projectId`、`scope`、`expiresAt` 和仅签发时展示的 `token`。`GET` 同路径列出调用者签发的凭证元数据，项目管理者可列出项目凭证。`DELETE /api/project/{projectId}/mcp-credentials/{credentialId}` 由签发人或项目管理者撤销。凭证用高熵随机密钥，数据库保存摘要和元数据，撤销持久化。以现有 `requireProjectManager` 判定管理权限。 [项目权限策略](../../../ai-fusion-video/src/main/java/com/stonewu/fusion/service/canvas/CanvasAccessPolicy.java)
- 每次调用从验证通过的凭证构造 `CanvasActor`，固定签发时的团队范围。校验账号启用状态、实时成员权限、工具读写范围及 `projectId`。查询、写入和文件绑定时继续验证实际资源、父对象和引用对象属于凭证项目。版本校验和幂等回执读取也在授权之后。撤销提交后发起的调用、重试和下载请求均失败。记录 `credentialId`、用户、项目、工具、`requestId`、`commandId` 和结果码，日志省略凭证、正文和文件内容。

团队试点采用手工配置项目凭证。需要通用客户端自动登录时，以 MCP HTTP OAuth 授权规范作为接入契约。 [MCP HTTP 授权](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)

## 通用工具契约

以下表格省略共同必填参数 `projectId`。每个写工具另需业务 `requestId`，长度为 1–128，字符范围为 `[A-Za-z0-9._:-]`，独立于 JSON-RPC `id`。`?` 表示可选，`*` 表示该分支创建必填。业务 ID 使用十进制字符串，格式为 `[1-9][0-9]{0,18}`，服务端同时校验 Java `long` 范围。媒体 ID 使用小写 UUID。版本为非负整数，`0` 有效。

- 单对象读取返回 `id`、`kind`、`projectId`、`version`、`fields`，有父对象时返回 `parentId` 和 `parentVersion`。历史分镜若缺少场景归属，可通过分镜主档列出并按 ID 读取。列表返回 `items`、`page`、`pageSize`、`hasMore` 及可用的父对象版本，默认 `page=1`、`pageSize=20`，最大 `50`。列表返回摘要，详情返回正文和业务字段。以现有排序及 ID 作同序项的排序依据，在查询层分页。
- 创建子对象时，`expectedParentVersion` 取自读取的父对象。更新时，`expectedVersion` 取自目标对象。更新资产项的元数据、提示词或媒体时，还需 `expectedParentVersion`，因为这些操作可能联动主资产版本和封面。其他对象更新仅接受目标版本。
- `fields` 和 `patch` 按 `kind` 注册 JSON Schema 白名单。`patch` 至少包含一个字段，省略字段保留原值，显式 `null` 仅用于可清空字段。数组按字段整体提交。服务端将十进制 ID 字符串转换为现有领域命令使用的 `long`。身份、归属、版本、统计字段和运行状态由服务端维护。
- 成功时返回 `structuredContent` 和同内容的文本块。结构为 `ok: true` 与 `data`。业务写回执再返回原始 `requestId`、`commandId` 和 `versions`，其中 `versions` 是提交回执中的 `{kind,id,version}` 列表。同一操作重试使用原回执生成结果。业务失败返回 `isError: true` 和包含 `ok: false`、`error.code`、`error.message`、`error.details` 的结果。JSON-RPC 协议错误由 SDK 处理。 [MCP Tools 规范](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)

### 项目工作区

| 工具 | 参数 | 结果与行为 |
|---|---|---|
| `fusion_workspace_get` | 共同参数 | 返回 `project: {id,name,version}`、`script: {id,version}` 和 `storyboard: {id,version}`。容器缺失时对应值为 `null`。可写凭证还会收到 `mediaUpload`，含上传路径、`maxFileBytes` 和支持的媒体类型。 |
| `fusion_workspace_initialize` | `expectedVersion`（项目版本） | 用 `InitializeProjectWorkspaceCommand` 补齐历史项目缺失的剧本或分镜主档，保留已有容器和内容。返回工作区及提交后的版本。 |

### 剧本

| 工具 | 参数 | 结果与行为 |
|---|---|---|
| `fusion_script_list` | `kind: episode / scene`，`parentId`，`page?`，`pageSize?` | 列出剧本下的分集或分集下的场次。 |
| `fusion_script_get` | `kind: script / episode / scene`，`id`，`includeRawContent?` | 读取剧本主档、分集或场次。`includeRawContent` 仅适用于主档和分集，默认 `false`。场次详情返回对白。 |
| `fusion_script_create` | `kind: episode / scene`，`parentId`，`expectedParentVersion`，`fields` | 在对应剧本或分集下追加子对象，返回新对象和更新后的版本。剧本主档由工作区初始化创建。 |
| `fusion_script_update` | `kind: script / episode / scene`，`id`，`expectedVersion`，`patch` | 更新指定字段并返回提交回执。普通编辑使用 `ResourcePatchCommand`。源稿重导入由现有专用流程处理。 |

| 类型 | 一期可写字段 |
|---|---|
| `script` | `content`、`storySynopsis`、`summary`、`genre`、`targetAudience`、`durationEstimate`、`charactersJson`。`content` 用于外部 Agent 保存格式化正文。 |
| `episode` | `episodeNumber*`（正整数）、`title`、`synopsis`、`rawContent`、`durationEstimate`。 |
| `scene` | `sceneHeading*`、`sceneNumber`、`location`、`timeOfDay`、`intExt`、`sceneDescription`、`characters: string[]`、`dialogues`、`characterAssetIds`、`sceneAssetId`、`propAssetIds`。未传 `sceneNumber` 时沿用系统编号。 |

`durationEstimate` 沿用现有分钟单位，为 0–1000000 的整数。`charactersJson` 为人物对象数组，每项必填 `name`，可传 `description`、`importance`、`assetId`。`dialogues` 为对白对象数组，每项必填 `type`（整数 1–5）、`content`（文本），可传 `character_name`、`parenthetical`、`character_asset_id`、`sortOrder`。`characterAssetIds`、`propAssetIds` 为 ID 数组，`sceneAssetId` 为单个 ID。上述引用使用同项目且类型匹配的**资产主档** ID。其余文本字段使用字符串，长度沿用领域校验。 [剧本字段校验](../../../ai-fusion-video/src/main/java/com/stonewu/fusion/service/resource/ScriptFields.java) · [剧本新增命令](../../../ai-fusion-video/src/main/java/com/stonewu/fusion/service/resource/ScriptResourceCreateHandler.java)

### 分镜

| 工具 | 参数 | 结果与行为 |
|---|---|---|
| `fusion_storyboard_list` | `kind: episode / scene / shot`，`parentKind: storyboard / episode / scene`，`parentId`，`page?`，`pageSize?` | 按现有查询关系分页。分集的父级为分镜主档，场景可按分集或分镜主档读取，镜头可按场景、分集或分镜主档读取。 |
| `fusion_storyboard_get` | `kind: storyboard / episode / scene / shot`，`id` | 读取对象详情。镜头详情返回提示词字段及可读取的媒体绑定。 |
| `fusion_storyboard_create` | `kind: episode / scene / shot`，`parentId`，`expectedParentVersion`，`fields` | 分集、场景、镜头分别在分镜主档、分集、场景下追加。主档由工作区初始化创建。 |
| `fusion_storyboard_update` | `kind: storyboard / episode / scene / shot`，`id`，`expectedVersion`，`patch` | 按对象版本更新内容字段。镜头提示词和媒体分别由对应工具更新。 |
| `fusion_storyboard_append_shots` | `sceneId`，`expectedParentVersion`（场景版本），`shots`（1–20 个 `shot` 的 `fields` 对象） | 复用 `StoryboardChildrenCommand(APPEND)`，同一场景内原子追加，按提交顺序返回本次新增的镜头和版本。任一镜头校验失败，整批回滚。 |

| 类型 | 一期可写字段 |
|---|---|
| `storyboard` | `description`。 |
| `episode` | `episodeNumber*`（正整数）、`scriptEpisodeId`、`title`、`synopsis`。传 `scriptEpisodeId` 时校验它属于本分镜关联的剧本，沿用集号和绑定唯一性规则。 |
| `scene` | `sceneHeading*`、`sceneNumber`、`location`、`timeOfDay`、`intExt`。未传 `sceneNumber` 时沿用系统编号。 |
| `shot` | `content*`、`duration`、`shotType`、`shotNumber`、`sceneExpectation`、`dialogue`、`sound`、`soundEffect`、`music`、`cameraMovement`、`cameraAngle`、`cameraEquipment`、`focalLength`、`transition`、`remark`、`characterIds`、`sceneAssetItemId`、`propIds`。 |

镜头 `duration` 单位为秒，范围为 0–86400，最多两位小数。`characterIds`、`propIds` 为 ID 数组，`sceneAssetItemId` 为单个 ID，三者使用同项目且类型匹配的**资产项**。分镜分集的 `scriptEpisodeId` 使用**剧本分集** ID。除已标注类型的字段外，其余为字符串，长度沿用领域校验。 [分镜字段校验](../../../ai-fusion-video/src/main/java/com/stonewu/fusion/service/resource/StoryboardFields.java) · [分镜关联校验](../../../ai-fusion-video/src/main/java/com/stonewu/fusion/service/resource/StoryboardRelations.java) · [分镜批量命令](../../../ai-fusion-video/src/main/java/com/stonewu/fusion/service/resource/StoryboardChildrenHandler.java)

`fusion_storyboard_append_shots` 的调用参数示例

```json
{
  "projectId": "42",
  "requestId": "episode-1-scene-1-shots-01",
  "sceneId": "311",
  "expectedParentVersion": 6,
  "shots": [
    {"content": "雨夜，店门外的路灯亮起。", "shotType": "全景", "duration": 4.5},
    {"content": "店主把钥匙放到柜台上。", "shotType": "近景", "duration": 3}
  ]
}
```

### 提示词

提示词随其所属对象管理。`targetKind: shot` 支持 `firstFramePrompt`、`lastFramePrompt`、`videoPrompt`。`targetKind: asset / assetItem` 支持 `aiPrompt`。

| 工具 | 参数 | 结果与行为 |
|---|---|---|
| `fusion_prompt_get` | `targetKind`，`targetId`，`slot` | 返回槽位 `text` 和目标对象版本。未填写时 `text=null`。资产项附主资产版本。 |
| `fusion_prompt_set` | `targetKind`，`targetId`，`slot`，`text: string / null`，`expectedVersion`，`expectedParentVersion?` | 首次赋值和后续修改均走单字段补丁。显式 `null` 清空槽位。目标为 `assetItem` 时，`expectedParentVersion` 必填。返回持久化文本和新版本。 |

文本长度和字符边界沿用现有字段校验。工具通过目标对象版本防止覆盖网页刚保存的提示词。 [镜头字段](../../../ai-fusion-video/src/main/java/com/stonewu/fusion/service/resource/StoryboardFields.java) · [资产字段](../../../ai-fusion-video/src/main/java/com/stonewu/fusion/service/resource/AssetFields.java)

### 资产和资产项

| 工具 | 参数 | 结果与行为 |
|---|---|---|
| `fusion_asset_list` | `kind: asset / assetItem`，`parentId?`，`assetType?`，`keyword?`，`page?`，`pageSize?` | `asset` 列出项目资产，可按类型和关键词筛选，`keyword` 最长 200 字符。`assetItem` 必填资产 `parentId`，列出该资产的变体。类型和关键词筛选仅适用于 `asset`。 |
| `fusion_asset_get` | `kind: asset / assetItem`，`id` | 返回业务元数据、`aiPrompt`、版本和可读取的媒体绑定。资产项返回所属资产 ID 和版本。 |
| `fusion_asset_create` | `kind: asset / assetItem`，`fields`，`parentId?`，`expectedParentVersion?` | `asset` 创建主档，返回主档和系统同时创建的**初始资产项**，可直接绑定原件。`assetItem` 为已有资产新增变体，必填 `parentId` 和 `expectedParentVersion`。 |
| `fusion_asset_update` | `kind: asset / assetItem`，`id`，`expectedVersion`，`patch`，`expectedParentVersion?` | 更新名称、描述、标签或变体元数据。`assetItem` 必填 `expectedParentVersion`。原件替换走 `fusion_media_bind`。 |

资产主档创建字段为 `type*`、`name*`、`description?`、`tags?: string[]`。主档可更新 `name`、`description`、`tags`。`type` 使用现有枚举 `character`、`scene`、`prop`、`vehicle`、`building`、`costume`、`effect`、`material`、`image`。资产项创建或更新使用 `name`、`itemType`，新增变体缺省 `itemType=variant`。`itemType` 为匹配 `[a-z][a-z0-9_]{0,31}` 的字符串。 [资产创建与初始资产项](../../../ai-fusion-video/src/main/java/com/stonewu/fusion/repository/resource/AssetMutationRepository.java) · [资产更新](../../../ai-fusion-video/src/main/java/com/stonewu/fusion/service/resource/AssetResourcePatchHandler.java)

## 媒体工具及文件传输

媒体原件和业务资产分别使用自己的 ID。先把原件上传为 `READY` 的项目媒体，再绑定到现有资产、资产项或镜头。资产和镜头详情中的 `mediaBindings` 返回 `{field,slot?,state,mediaResourceId?}`。已有登记的生成结果等字段可通过 `field` 读取，有可写槽位时返回 `slot`。项目内可访问的托管原件返回其 ID 和 `ready` 或 `pending` 状态，历史外链标为 `unmanaged`，超出凭证范围或已删除的引用标为 `unavailable`，只返回状态及字段。历史外链通过既有媒体入库流程获得托管 ID 后即可传输原件。

| 工具 | 参数 | 结果与行为 |
|---|---|---|
| `fusion_media_get` | `mediaResourceId` | 读取同项目媒体的 `mediaResourceId`、`name`、`kind`、`mimeType`、`byteSize`、`sha256`、`state`、`version`。状态为 `READY` 时返回同域 `downloadUrl`。 |
| `fusion_media_bind` | `targetKind: asset / assetItem / shot`，`targetId`，`expectedVersion`，`expectedParentVersion?`，`bindings: {slot,mediaResourceId}[]` | 创建或更新目标的原件引用。至少提交一个绑定，槽位须属于同一目标且互不重复，统一校验后原子保存。目标为 `assetItem` 时父级版本必填。 |

| 目标 | 可绑定槽位及媒体类型 |
|---|---|
| `asset` | `cover` 为图片，映射 `coverUrl`。 |
| `assetItem` | `image` 和 `thumbnail` 为图片，映射 `imageUrl` 和 `thumbnailUrl`。`video` 和 `audio` 分别映射 `properties.videoUrl` 和 `properties.audioUrl`。 |
| `shot` | `image`、`referenceImage`、`firstFrame`、`lastFrame` 为图片，`video` 为视频，分别映射现有镜头媒体字段。音频同步到项目资产项。 |

绑定入参以白名单槽位和媒体 ID 表达。服务端调用 `ResourceBindMediaCommand`，校验媒体状态、项目归属及类型，由现有观察器维护引用关系。替换原件先上传新文件，再针对目标槽位提交新 ID。引用同一旧原件的其他对象继续使用旧 ID。 [绑定命令](../../../ai-fusion-video/src/main/java/com/stonewu/fusion/service/resource/model/ResourceBindMediaCommand.java) · [媒体字段定义](../../../ai-fusion-video/src/main/java/com/stonewu/fusion/service/media/MediaSourceFields.java)

### 上传

`POST /api/mcp/projects/{projectId}/media` 使用同一项目的 `read_write` 凭证和 `multipart/form-data`。必填 `file`（一个文件）和 `requestId`（上传操作自己的幂等键）。首期单文件上限为 95 MiB，给当前 100 MiB 的 multipart 请求上限预留封装空间。使用 `MediaIntakeService.uploadChecked` 接收图片、视频和音频，沿用文件探测、存储与 SHA-256 登记。只有媒体状态为 `READY` 时返回 HTTP `200` 和上述媒体元数据。 [上传服务](../../../ai-fusion-video/src/main/java/com/stonewu/fusion/service/media/MediaIntakeService.java) · [现有上传配置](../../../ai-fusion-video/src/main/resources/application.yaml)

同键、同文件名、同申报类型及同内容重试返回原 `mediaResourceId`。同键异内容返回 `409 MEDIA_REQUEST_CONFLICT`。接收仍在进行时返回 `409 MEDIA_INGEST_IN_PROGRESS`，调用方稍后用原键重试。超过限制返回 `413`，内容格式或媒体类型校验失败返回业务错误。上传成功后独立执行绑定，绑定冲突时可保留已上传 ID，重读目标版本后重新提交绑定。文件字节传输在业务数据库事务之外执行。 [媒体接收幂等](../../../ai-fusion-video/src/main/java/com/stonewu/fusion/service/media/MediaRegistryTransactions.java)

### 下载

`GET /api/mcp/projects/{projectId}/media/{mediaResourceId}/content` 使用同项目的 `read` 或 `read_write` 凭证，支持 `HEAD`、`Range`、`ETag` 和原件下载。`fusion_media_get` 返回相对路径，客户端拼接连接融光所用的同域地址。下载控制器复用现有原件输出逻辑，流开始和现有分段校验点使用项目凭证重新鉴权。完整内容返回 `200`，有效范围返回 `206`，越界返回 `416`。业务正文仅保存稳定媒体 ID。 [原件流式输出](../../../ai-fusion-video/src/main/java/com/stonewu/fusion/controller/media/MediaContentController.java)

## 并发、幂等与错误

普通修改统一进入 `ResourceCommandService`。创建子对象比对父版本，字段更新比对目标版本，资产项写操作同时比对资产版本。沿用资源命令的事务、缓存失效、版本记录和资源变更事件。新增镜头后，Web 可通过原有资源事件读取新数据。 [资源命令事务](../../../ai-fusion-video/src/main/java/com/stonewu/fusion/service/resource/ResourceCommandService.java) · [网页资源事件](../../../ai-fusion-video-web/components/dashboard/resource-sync-bridge.tsx)

MCP 内部幂等键为 `mcp:` 加上对 `[projectId, toolName, requestId]` 的规范 JSON 序列计算的 SHA-256 十六进制值。现有命令服务再按实际调用者隔离键。文件上传的 `toolName` 固定为 `media_upload`。客户端丢失响应时应原样重试。同键异参由业务命令摘要校验拒绝。变更意图或重新合并后提交新版本时，使用新的 `requestId`。批量追加结果从原始回执的有序子列表末尾投影出本批新增镜头。

| 情况 | 对外契约 |
|---|---|
| 凭证缺失、无效、过期或已撤销 | HTTP `401`。 |
| 凭证有效但项目、角色或读写范围不允许 | HTTP 边界返回 `403`，工具执行中返回 `isError` 和 `ACCESS_DENIED`。通过其他项目对象 ID 试读时，统一返回 `RESOURCE_NOT_FOUND`。 |
| 输入、引用类型或父子关系错误 | `INVALID_ARGUMENT`，详情包含可定位的字段。 |
| 目标版本或父版本已变化 | `VERSION_CONFLICT`，详情返回 `resourceKind`、`resourceId`、`expectedVersion` 和 `currentVersion`。Agent 读取发生冲突的对象，合并差异，再用新请求提交。 |
| 相同写入键用于不同命令 | `REQUEST_CONFLICT`，保留第一次提交的内容及回执。 |
| 绑定或下载的媒体尚未就绪 | `MEDIA_NOT_READY`，详情含媒体状态，待 `READY` 后执行绑定或下载。 |
| 达到入口限流 | HTTP `429`。客户端等待 `Retry-After` 指定时间，重试同一操作时沿用原业务键。 |

## 开发顺序

1. **协议和凭证。** 验证 SDK 依赖与实际客户端握手，接入默认关闭的入口及精确的路径鉴权。用 MyBatis-Plus 增加项目凭证登记、签发、查询与撤销。分配 Flyway 版本前核对远端正式 Release/Tag、迁移目录和目标测试库历史，按仓库规则确定产品版本与迁移号。通过 `FlywayMigrationNamingTests` 和目标测试库的 Flyway validate。
2. **剧本、分镜和提示词。** 建立固定 DTO、输入白名单和回执投影。复用 `ResourceQueryService`、`StoryboardResourceQueryService` 和现有命令。给子列表增加查询层分页，按读取、单对象写、批量镜头写的顺序联调。验证原页面能收到同一资源事件。
3. **资产和媒体。** 复用 `AssetResourceQueryService`、现有资产命令和媒体接收。实现项目凭证保护的传输端点，提取已有下载代码共用。接通初始资产项、变体、媒体 ID 和绑定回执。
4. **验收与接入文档。** 用真实服务、实际 MCP 客户端和浏览器完成下表。记录版本、接入方式、凭证申请和文件调用示例。代码通过、真实客户端联调和人工确认各自记录结果。

## 端到端验收标准

使用隔离测试环境和两个测试项目。首个项目准备编辑者和查看者。第二个项目使用同一编辑者具备访问权的资源，以验证凭证确实只允许其签发项目。协议回归用独立进程连接真实服务，工作流由一个支持 Bearer 和文件 HTTP 传输的外部 Agent 执行，网页使用同一测试项目。保持页面前台打开且无未保存编辑，资源事件应在写入成功后 10 秒内反映在剧本、分镜和资产页面。

| 场景 | 通过标准 |
|---|---|
| 连接与发现 | 目标客户端成功协商协议，发现恰好 19 个工具。每个工具的参数通过实际 `tools/call` 验证。停用入口、凭证过期及撤销后的调用按约定失败。 |
| 剧本创建、读取、更新 | 读取或初始化工作区，创建一集和一个场次，写入场景和对白。MCP 和网页返回相同 ID 与正文。网页修改对白后，MCP 读到新文本和新版本。历史项目补齐容器后保留已有内容。 |
| 分镜创建、读取、更新 | 新建关联该剧本分集的分镜集和场景，单条创建一个镜头，批量追加两个镜头，随后更新镜头内容或时长。网页看到三个镜头、正确顺序和分集关联。提交含一个无效镜头的批次后，集合无新增。 |
| 提示词创建、读取、更新 | 对镜头写入首帧、尾帧、视频提示词，并更新其中一个。给资产项写入 `aiPrompt` 并更新。逐个读回精确文本、目标 ID 及版本，在网页核对落到对应字段。 |
| 素材创建、读取、更新 | 创建项目资产，获得初始资产项，再创建变体。分别上传真实 PNG、MP4、WAV，绑定图像、视频和音频槽位。项目素材页能预览三种原件，MCP 下载文件的 SHA-256 与上传前一致。更新资产名称和标签，替换其中一个槽位后，目标读到新 ID，其他引用仍能读取旧 ID。 |
| 版本冲突及丢包重试 | 网页和 Agent 以同一版本写同一镜头，只允许一次成功。父集合变化后用旧版本新增子对象，或主资产变化后用旧父版本更新资产项，均返回冲突。模拟写入已成功但客户端未收回执，原样重试返回相同 ID 和 `commandId`。同键改参数返回冲突且对象数量保持。 |
| 项目隔离和撤销 | 查看者凭证可读。编辑者的 `read` 凭证也无法写入或上传。更换 `projectId`、父 ID、镜头 ID 或媒体 ID 也无法跨项目读、写、绑定或下载。成员被降权后写入失败，凭证撤销后新请求和旧请求重试均失败。项目凭证访问普通业务 REST 路径被拒绝。 |
| 文件边界 | 实测 `HEAD`、单范围 `206` 和非法范围 `416`。同一上传键重试返回同一个媒体 ID，变更文件返回冲突。超限上传和错误类型绑定被拒绝，已绑定媒体保持原值。 |

验收以真实服务回执、浏览器对同一 ID 的读回和文件哈希为准。实际结果见[完成记录](completion-summary.md)。
