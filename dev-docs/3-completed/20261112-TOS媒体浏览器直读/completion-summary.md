# TOS 媒体直读完成记录

TOS 托管的图片、视频、音频和对应缩略图启用浏览器直读。融光在读取请求时重新校验现有短时播放凭据，签发 TOS HTTPS GET 链接。浏览器按 Range 获取密文，使用单对象派生密钥认证解密并交给原生媒体元素。历史加密对象无需重写或迁移。

Java 密文兼容与鉴权定向测试、TypeScript 检查、前端定向 ESLint、分段读取测试和 Chromium 媒体播放测试均通过。测试中 PNG、MP4、WAV 解码成功，视频拖动通过。真实 TOS 迁移样本的跨域签名 Range GET 返回 206，页面源可读取 Content-Range。

线上 TOS 桶新增仅针对融光 HTTPS 源的 GET Range 跨域规则。页面播放需使用支持 Service Worker 的浏览器。 [TOS 跨域规则](https://docs.volcengine.com/docs/torch-object-storage/cor-srules?lang=zh)
