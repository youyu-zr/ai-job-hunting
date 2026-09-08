# Project Progress

## Current Release

- Version: `0.0.104-chat-session-send`
- Status: deployed for controlled production acceptance on BOSS
- Public install: https://aijob.zxpnb.top/ai-job-hunting.user.js

## Root Cause

- BOSS新版职位页使用 `GeekChatCore 2.0.3 + SharedWorker`。
- 职位页可能显示 `DISCONNECTED`，但 SharedWorker 已就绪；旧逻辑把页面状态误当成唯一发送条件。
- `socketConnect.sendTextMessage()` 内部会异步发送，但包装函数固定返回 `null`，不能作为发送回执。
- 旧版固定招呼语之所以偶尔成功，是 BOSS 原生建联先创建了会话，随后旧 protobuf 通道才有机会发送第二条消息。

## Changes

- 投递后主动初始化 BOSS SharedWorker 会话通道，不再依赖用户先打开聊天页。
- 识别“Worker 已就绪但页面状态滞后”的合法状态。
- 文本消息优先调用底层 `SharedWorkerClient.sendTextMessage()`，等待真实 Promise 结果。
- 保留目标岗位、公司、简历和求职偏好生成岗位化招呼语的原流程。
- 不恢复固定“你好”或 BOSS 默认招呼语，避免污染和重复发送。
- 仍以目标会话中的己方消息回执作为最终确认条件。

## Verification

- Adapter regression tests: 16 passed.
- `vue-tsc --noEmit`: passed.
- Vite production build: passed.
- 服务器 `ai-job.service` active，9100 和 6768 正常监听。
- 剩余验收：更新脚本后，在已登录 BOSS 环境单职位投递一次，确认真实消息回执。

## Next Action

安装或更新脚本到 `0.0.104-chat-session-send`，禁用旧版本，刷新 BOSS，将单次投递数量设为 `1`，再观察岗位化招呼语和发送回执。
