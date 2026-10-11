import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const remote = readFileSync(new URL('../src/stores/remote.ts', import.meta.url), 'utf8');
const preference = readFileSync(new URL('../src/components/ui/Preference.vue', import.meta.url), 'utf8');
const server = readFileSync(new URL('../src/stores/server.ts', import.meta.url), 'utf8');

// 不再存在任何跨账号的"全局最新"镜像。
assert.doesNotMatch(server, /getGlobalMirrorKey/);
assert.doesNotMatch(server, /global_latest/);
assert.doesNotMatch(remote, /getGlobalMirrorKey/);
assert.doesNotMatch(preference, /getGlobalMirrorKey/);

// 镜像 Key 必须同时绑定服务器地址与当前 BOSS 账号。
assert.match(server, /function getMirrorKey\(type: string, accountId\?: string \| number\)/);
assert.match(server, /mirror_\$\{type\}_\$\{safeUrl\}_acct_\$\{account\}/);
assert.match(remote, /getMirrorKey\('user_config', accountId\)/);
assert.match(preference, /getMirrorKey\('user_config', bossAccountId\)/);

// 账号未知时只写 localStorage，不写任何共享镜像。
assert.match(remote, /if \(!accountId\) \{[\s\S]*?return;[\s\S]*?\}/);
assert.doesNotMatch(remote, /GmGetValue\(serverStore\.getMirrorKey\('user_config'\)/);

console.log('account isolation smoke: PASS');