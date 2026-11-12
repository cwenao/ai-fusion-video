# 火山引擎对象存储接入

在存储设置的「对象存储」中加入火山引擎 TOS。沿用配置的增改、测试、启用和默认存储入口，并让普通媒体、托管媒体和智能体工作空间使用同一套对象存储选择逻辑。

使用火山引擎官方 Java SDK 对指定桶执行上传、读取和删除。托管媒体继续通过 ETag 和响应范围校验后分段读取。公开访问使用桶的公网地址或已配置的公开域名。 [火山引擎对象存储 SDK 文档](https://docs.volcengine.com/docs/torch-object-storage/sdk-overview?lang=zh)
