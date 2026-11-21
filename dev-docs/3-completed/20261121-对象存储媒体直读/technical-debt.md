# 对象存储接入条件

预览需要 HTTPS 页面和 Service Worker。存储桶跨域规则需允许融光页面来源的 GET、允许 `Range` 并暴露 `Content-Range`。TOS 预览签发同地域公网端点。S3 兼容存储默认使用其服务端点，服务端点仅供内网使用时，通过存储配置更新接口设置 `options.browserEndpoint` 为浏览器可达的 HTTPS S3 服务端点。浏览器端点沿用同一桶、签名地域和路径样式。 [Amazon S3 跨域配置](https://docs.aws.amazon.com/AmazonS3/latest/userguide/ManageCorsUsing.html)

对象存储原件下载在点击时生成私有交付副本。通常使用原件的存储配置，也可设置 `APP_MEDIA_DELIVERY_STORAGE_CONFIG_ID` 指向符合私有交付要求的对象存储。签名有效期为 5 分钟。副本沿用已有过期清理机制。交付副本准备仍需服务端执行一次存储读取和写入。
