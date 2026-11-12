# 兼容范围

本地存储、其他对象存储厂商、托管文档、下载和服务端处理继续使用既有媒体接口。TOS 播放失败由页面的媒体加载状态反馈。

浏览器需要安全上下文与 Service Worker。TOS 桶须允许融光页面的 HTTPS 源进行 GET Range 请求，并暴露 Content-Range。上传与服务端读取使用已配置端点，浏览器签名使用同地域 HTTPS 公网端点。

每个媒体请求重新校验融光访问凭据。TOS 签名 URL 最长有效到原浏览器凭据过期。已开始的 TOS 请求可持续到完成或取消。浏览器获得的派生密钥只可解密对应对象，长期密钥仍留在服务端。 [TOS 跨域规则](https://docs.volcengine.com/docs/torch-object-storage/cor-srules?lang=zh)
