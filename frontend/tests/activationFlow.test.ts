import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';

const tools = readFileSync(new URL('../src/utils/tools.ts', import.meta.url), 'utf8');
const aiJob = readFileSync(new URL('../src/components/ui/AiJob.vue', import.meta.url), 'utf8');

test('a new account is asked to import a resume instead of uploading automatically', () => {
  const branchStart = tools.indexOf('if (resp.data.code === 2000)');
  const branchEnd = tools.indexOf('if (resp.data.code !== 200', branchStart);

  assert.notEqual(branchStart, -1);
  assert.notEqual(branchEnd, -1);

  const newUserBranch = tools.slice(branchStart, branchEnd);
  assert.match(newUserBranch, /不会自动上传简历/);
  assert.doesNotMatch(newUserBranch, /handlerImport|\/api\/user\/import\/resume/);
});

test('resume upload is wired to the user-triggered import button', () => {
  assert.match(aiJob, /@click="handlerImport"/);
  assert.match(aiJob, /const handlerImport = async/);
  assert.match(aiJob, /axios\.post\("\/api\/user\/import\/resume"/);
});
