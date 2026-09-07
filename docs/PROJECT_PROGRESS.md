# Project Progress

## Current Release

- Version: `0.0.102-official-greeting-send`
- Status: prepared for deployment and pending one-job production acceptance on BOSS
- Public install: https://aijob.zxpnb.top/ai-job-hunting.user.js

## Changes

- Job greetings now receive the complete current job context and candidate profile.
- Generic greetings are rejected before sending; generation is retried once, with no generic fallback.
- Successful delivery logs include company, job, salary, location, and the complete screening explanation when available.
- The bottom-right delivery panel and the full run log read from the same session records and preserve message order.
- Internal send-state markers are translated into concise Chinese user-facing records.
- Text greetings wait until the official BOSS ChatWebsocket reports a connected client.
- Automatic delivery uses the official `ChatWebsocket.sendText()` path and waits for its acknowledgement.
- Failed automatic sends do not fall back to compatibility channels, preventing duplicate or competing messages.
- The original BOSS `ChatWebsocket.init()` call is preserved so the page owns connection initialization.
- Internal greeting queue states are retained for diagnostics but hidden from user-facing logs.
- The floating delivery panel and full run log refresh from the same visible record source until final websocket confirmation settles.

## Deployment

- Public userscript and install page are being updated to `0.0.102-official-greeting-send`.
- Backend was not changed or restarted.
- Production health: `ai-job.service` active, ports `9100` and `6768` listening, and Nginx configuration valid.
- Real BOSS acceptance still requires one controlled single-job delivery in an authenticated browser session.

## Verification

- All 16 local UI smoke tests passed.
- Frontend `vue-tsc --noEmit` passed.
- Frontend production build passed.
- Backend `mvn -DskipTests package` passed.
- Online silent-login, profile read, grounded greeting, normal-job screening, and risk-job screening passed without sending a BOSS message.
- Server service is active; ports 9100 and 6768 are listening; privileged `nginx -t` passed.

## Known Limits

- A real BOSS page interaction still requires the user to perform one manual single-job acceptance test.
- The online smoke test does not send messages or verify BOSS's final visual send confirmation.
- AI output quality and screening decisions remain dependent on the configured SiliconFlow model, profile completeness, and the live job data.

## Next Action

Install the public script, refresh the BOSS page, set the delivery count to `1`, and manually inspect the greeting, full log, and bottom-right delivery panel before normal use.
