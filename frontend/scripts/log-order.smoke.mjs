import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const eventStream = readFileSync(new URL('../src/logging/eventStream.ts', import.meta.url), 'utf8');
const queries = readFileSync(new URL('../src/logging/queries.ts', import.meta.url), 'utf8');
const runRecord = readFileSync(new URL('../src/components/ui/RunRecord.vue', import.meta.url), 'utf8');
const aiJob = readFileSync(new URL('../src/components/ui/AiJob.vue', import.meta.url), 'utf8');

const readAllLogsBody = eventStream.match(/export function readAllLogs[\s\S]*?\n}\n/)?.[0] || '';
const latestPushLogsBody = eventStream.match(/export function getLatestPushLogs[\s\S]*?\n}\n/)?.[0] || '';

// 运行记录按最新在前展示，浮窗按最旧在前追加，两者必须来自同一个事件流。
assert.match(readAllLogsBody, /\.reverse\(\)/);
assert.match(latestPushLogsBody, /readLogsInWriteOrder\(source\)/);
assert.match(runRecord, /const allLogs = filterLogs\(readAllLogs\(logRecorder\), filter\.value\);/);
assert.match(aiJob, /getLatestPushLogs\(logRecorder, 10, pushSessionStartEventId\)/);

// 浮窗与运行记录共用同一份实现和同一套事件 ID，不得再各自实现读取管道。
assert.match(queries, /export \{[\s\S]*?readAllLogs,[\s\S]*?\} from "\.\/eventStream";/);
assert.match(queries, /export \{[\s\S]*?getLatestPushLogs,[\s\S]*?\} from "\.\/eventStream";/);
assert.doesNotMatch(aiJob, /const allLogs = filterLogs\(readAllLogs\(logRecorder\)/);
assert.doesNotMatch(eventStream, /getLogCount\(\)[\s\S]*?readAllLogs[\s\S]*?getLogCount\(\)/);

console.log('log order smoke: PASS');