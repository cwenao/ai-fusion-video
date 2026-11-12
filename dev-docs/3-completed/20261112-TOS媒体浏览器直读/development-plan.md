# TOS 媒体浏览器直读

TOS 托管的图片、视频和音频由浏览器直读。服务端校验媒体版本和权限，签发对应密文对象的短时 GET 地址与该对象的派生解密参数。浏览器用 Service Worker 读取并认证解密密文分段，向原生媒体元素提供明文响应，支持 Range 请求和拖动定位。

验收包括旧 Tink 密文互操作、篡改与越权拒绝、浏览器解码和拖动，以及现有桶的签名 Range 请求。 [Tink 流式 AEAD 格式](https://developers.google.com/tink/streaming-aead/aes_gcm_hkdf_streaming) · [火山引擎 Java SDK 预签名](https://docs.volcengine.com/docs/torch-object-storage/standard-presigned-signature-java-sdk?lang=zh)
