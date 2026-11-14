# 交付记录

「AI 配置」提供 MiniMax 国内和国际官方线路。两条线路均可快速添加 MiniMax M3、M3.1 Flash，H3 Image、H3 Image Highspeed，以及 H3、H3 Highspeed、H3 Lite、H3 Max。模型预设保存后可复用，已有连接可以追加模型。

对话按官网配置 100 万 token 上下文和最大输出 token 上限，M3.1 Flash 提供五档思考深度。图片支持 1K、2K、4K，自定义宽高，单次 API 请求最多 10 张结果，编辑可传 1–16 张参考图。视频支持独立尾帧、首尾帧和多模态参考，各型号使用官网规定的分辨率、整数秒数及比例条件。系统预填 5 秒、768P 和文生视频 16:9，便于提交官网要求的必填参数。

本地验证包括临时 MySQL 入库、HTTP 请求及响应、前端类型检查和桌面及窄屏浏览器流程。正式环境部署与真实 MiniMax 密钥验收待执行。

[对话补全](https://platform.minimax.cn/docs/api-reference/text-chat-openai) · [图片生成与编辑](https://platform.minimax.cn/docs/guides/image-generation) · [视频 V2](https://platform.minimax.cn/docs/api-reference/video-generation-v2-create)
