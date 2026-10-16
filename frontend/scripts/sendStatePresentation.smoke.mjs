import assert from 'node:assert/strict';
import {formatUserVisibleLog} from '../src/logging/presentation.ts';

const failedGreeting = formatUserVisibleLog(
  '[SEND_STATE] status=FAILED traceId=t1 jobKey=AI应用助理-商丘 action=POST_DELIVERY_GREETING channel=post-delivery cmid= msg=post-delivery greeting sender unavailable content=您好',
);
assert.equal(failedGreeting, '岗位【AI应用助理-商丘】投递后招呼语发送失败：当前页面消息通道未就绪');

const confirmedReply = formatUserVisibleLog(
  '[SEND_STATE] status=SEND_CONFIRMED traceId=t2 jobKey=文员-商丘 action=AI_REPLY channel=reply cmid=123 msg=confirmed by own websocket echo text content=您好',
);
assert.equal(confirmedReply, '岗位【文员-商丘】AI回复已确认发送');

const submittedReply = formatUserVisibleLog(
  '[SEND_STATE] status=SEND_SUBMITTED traceId=t3 jobKey=文员-商丘 action=AI_REPLY channel=reply cmid=123 msg=official channel accepted content=您好',
);
assert.equal(submittedReply, '岗位【文员-商丘】AI回复已提交，等待平台确认');

assert.equal(formatUserVisibleLog('工作【行政助理】投递成功'), '工作【行政助理】投递成功');
assert.equal(formatUserVisibleLog('loaded server config'), '已加载服务器配置');

console.log('send-state presentation smoke: PASS');
