# Project Progress

## Current Release

- Version: `0.0.103-geek-chat-shared-worker`
- Status: published for production acceptance on BOSS
- Public install: https://aijob.zxpnb.top/ai-job-hunting.user.js

## Root Cause

- The current BOSS page uses `GeekChatCore 2.0.3` with a `SharedWorker` transport.
- The previous release only treated legacy `window.ChatWebsocket.client.isConnected()` as the ready signal.
- As a result, the script reported `当前页面消息通道未就绪` even while the real BOSS channel was connected.

## Changes

- Detect `GeekChatCore.socketConnect` and require `socketStatus=CONNECTED` with a ready SharedWorker.
- Send text through the current official `GeekChatCore.sendTextMessage()` path.
- Keep the legacy sender disabled in official-only delivery to prevent duplicate messages.
- Preserve the existing greeting generation, screening, and user-facing log behavior.

## Verification

- Adapter regression tests: 13 passed.
- `vue-tsc --noEmit`: passed.
- Vite production build: passed.
- Production acceptance still requires one controlled single-job delivery in an authenticated BOSS browser session.

## Next Action

Install or update the script, disable older duplicate copies, refresh BOSS, set delivery count to `1`, and inspect the resulting greeting and send log.
