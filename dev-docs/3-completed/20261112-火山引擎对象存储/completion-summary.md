# 火山引擎 TOS 接入完成记录

存储设置可新增「对象存储 → 火山引擎 TOS」。填写桶地域后自动生成 TOS 访问端点，支持手动指定端点、对象路径前缀及公开域名。托管媒体和工作空间可以选择同一存储配置。

后端在 Java 21 中使用火山引擎官方 `ve-tos-java-sdk 2.9.19`。接入范围包括字节、本地文件及远程文件上传、公开访问检查、托管媒体分段读取与清理、智能体工作空间正文读写。配置沿用现有对象存储结构。 [火山引擎官方 Java SDK](https://github.com/volcengine/ve-tos-java-sdk)

26 项后端定向测试通过，含本地 HTTP 服务上的真实 SDK 请求和已有 S3 行为回归。7 项设置表单脚本测试、TypeScript 类型检查及变更文件 ESLint 通过。浏览器使用模拟 API 验证了厂商选择、地域校验、端点切换、保存和测试连接请求，以及桌面深色与窄屏浅色下的表单滚动和焦点空间。

真实 TOS Bucket 的权限和公开访问仍需现场验收。对默认域名有在线预览限制的桶可填写公开域名或 CDN。 [火山引擎浏览器预览说明](https://docs.volcengine.com/docs/TorchObjectStorage/Howtopreviewobjectsinabrowser?lang=en)
