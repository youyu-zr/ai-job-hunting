# Project Progress

## Current Release

- Version: `0.0.95-greeting-log-fix`
- Status: deployed and pending one-job manual acceptance on BOSS
- Public install: https://aijob.zxpnb.top/ai-job-hunting.user.js

## Changes

- Job greetings now receive the complete current job context and candidate profile.
- Generic greetings are rejected before sending; generation is retried once, with no generic fallback.
- Successful delivery logs include company, job, salary, location, and the complete screening explanation when available.
- The bottom-right delivery panel and the full run log read from the same session records and preserve message order.
- Internal send-state markers are translated into concise Chinese user-facing records.

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
