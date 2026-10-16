import assert from 'node:assert/strict';
import test from 'node:test';
import {setTimeout as delay} from 'node:timers/promises';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import ts from 'typescript';
import {MessageMergeQueue} from '../src/platform/messageMerge.ts';
import {batchPlatformBodyTypeOf} from '../src/platform/messageBatchMetadata.ts';
import * as packetMessage from '../src/webSocket/packetMessage.ts';
import {normalizedMessageBatch} from '../src/webSocket/messageBody.ts';
import {messageFingerprint} from '../src/platform/messageFingerprint.ts';
import {isCompletedMessage, terminalWithoutReply} from '../src/platform/messageCompletion.ts';
import {ConversationPauseRegistry} from '../src/platform/pauseRegistry.ts';
import {canDeliverAiSeatTurn, parseAiSeatRuntimeStatus} from '../src/platform/aiSeatDelivery.ts';
import {shouldDelegateConversationTextToAi} from '../src/platform/conversationInput.ts';

async function collectBatches(messages: Array<{type: number; text: string; mid?: string}>) {
    const queue = new MessageMergeQueue<typeof messages[number]>(1);
    const batches: Array<{texts: string[]; items: typeof messages}> = [];
    await Promise.all(messages.map(message => queue.enqueue(
        'job-1',
        message.text,
        message,
        batch => {
            batches.push({texts: batch.texts, items: batch.items});
            return true;
        },
        String(message.type),
    )));
    return batches;
}

for (const types of [[1, 14], [14, 1], [7, 14], [14, 7]]) {
    test(`one safety batch retains every packet for body types ${types.join(' then ')}`, async () => {
        const messages = types.map((type, index) => ({
            type,
            text: `HR message ${index}`,
            mid: `mid-${index}`,
        }));
        assert.deepEqual(await collectBatches(messages), [{
            texts: ['HR message 0', 'HR message 1'],
            items: messages,
        }]);
    });
}

test('continues coalescing distinct messages with the same body type', async () => {
    const messages = [
        {type: 1, text: 'First HR text'},
        {type: 1, text: 'Second HR text'},
    ];
    assert.deepEqual(await collectBatches(messages), [{
        texts: ['First HR text', 'Second HR text'],
        items: messages,
    }]);
});

test('deduplicates repeated text but retains each packet for read acknowledgements', async () => {
    const messages = [
        {type: 1, text: 'Repeated HR text', mid: 'mid-1'},
        {type: 1, text: 'Repeated HR text', mid: 'mid-2'},
    ];
    assert.deepEqual(await collectBatches(messages), [{
        texts: ['Repeated HR text'],
        items: messages,
    }]);
});

test('identical text on different body types cannot discard interview metadata', async () => {
    const messages = [
        {type: 1, text: 'Same preview', mid: 'text-mid'},
        {type: 14, text: 'Same preview', mid: 'card-mid'},
    ];
    const batches = await collectBatches(messages);
    assert.equal(batches.length, 1);
    assert.deepEqual(batches[0].texts, ['Same preview', 'Same preview']);
    assert.deepEqual(batches[0].items, messages);
});

function deferred() {
    let resolve!: () => void;
    const promise = new Promise<void>(done => { resolve = done; });
    return {promise, resolve};
}

for (const stopReason of ['USER_CONFIRMATION_REQUIRED', 'HIGH_RISK_JOB']) {
    for (const resumeFirst of [false, true]) {
        test(`${stopReason} blocks a fast resume request, resumeFirst=${resumeFirst}`, async () => {
            const queue = new MessageMergeQueue<{type: number; text: string}>(1);
            const classification = deferred();
            const actions: string[] = [];
            const decisions: string[][] = [];
            const settled: boolean[] = [];
            let paused = false;
            const resume = {type: 7, text: 'Please send your resume'};
            const risky = {type: 1, text: stopReason === 'HIGH_RISK_JOB'
                ? 'Pay a deposit before applying'
                : 'Confirm attendance tomorrow at 10:00'};
            const messages = resumeFirst ? [resume, risky] : [risky, resume];
            // Only the remote classifier latency is controlled; batching is the real queue.
            const results = messages.map(message => queue.enqueue(
                'job-safety',
                message.text,
                message,
                async batch => {
                    decisions.push(batch.texts);
                    if (batch.texts.includes(risky.text)) {
                        await classification.promise;
                        paused = true;
                    }
                    if (!paused && batch.items.some(item => item.type === 7)) {
                        actions.push('SEND_RESUME');
                    }
                    return true;
                },
                String(message.type),
            ).then(result => {
                settled.push(result);
                return result;
            }));
            await delay(10);
            const prematureActions = [...actions];
            classification.resolve();
            assert.deepEqual(await Promise.all(results), [true, true]);
            assert.deepEqual(prematureActions, [], 'resume escaped before the shared safety decision');
            assert.deepEqual(actions, []);
            assert.deepEqual(decisions, [messages.map(message => message.text)]);
            assert.deepEqual(settled, [true, true]);
        });
    }
}

test('a safe mixed text and resume burst still permits one resume action', async () => {
    const queue = new MessageMergeQueue<{type: number}>(1);
    const actions: string[] = [];
    const process = async (batch: {items: Array<{type: number}>}) => {
        if (batch.items.some(item => item.type === 7)) {
            actions.push('SEND_RESUME');
        }
        return true;
    };
    assert.deepEqual(await Promise.all([
        queue.enqueue('safe-job', 'Hello', {type: 1}, process, '1'),
        queue.enqueue('safe-job', 'Please send your resume', {type: 7}, process, '7'),
    ]), [true, true]);
    assert.deepEqual(actions, ['SEND_RESUME']);
});

test('successive windows wait for the prior safety decision in the same conversation', async () => {
    const queue = new MessageMergeQueue<string>(1);
    const started = deferred();
    const classification = deferred();
    const events: string[] = [];
    const first = queue.enqueue('job-1', 'risk', 'risk', async () => {
        events.push('classifying');
        started.resolve();
        await classification.promise;
        events.push('paused');
        return true;
    }, '1');
    await started.promise;
    const second = queue.enqueue('job-1', 'resume', 'resume', () => {
        events.push('next window');
        return true;
    }, '7');
    await delay(10);
    const beforeDecision = [...events];
    classification.resolve();
    await Promise.all([first, second]);
    assert.deepEqual(beforeDecision, ['classifying']);
    assert.deepEqual(events, ['classifying', 'paused', 'next window']);
});

test('a slow conversation does not block a different conversation', async () => {
    const queue = new MessageMergeQueue<string>(1);
    const started = deferred();
    const classification = deferred();
    const first = queue.enqueue('slow-job', 'risk', 'risk', async () => {
        started.resolve();
        await classification.promise;
        return true;
    });
    await started.promise;
    try {
        assert.equal(await queue.enqueue('independent-job', 'hello', 'hello', () => true), true);
    } finally {
        classification.resolve();
        await first;
    }
});

test('settling an earlier window cannot release the active conversation tail', async () => {
    const queue = new MessageMergeQueue<string>(1);
    const firstStarted = deferred();
    const releaseFirst = deferred();
    const secondStarted = deferred();
    const releaseSecond = deferred();
    const events: string[] = [];
    const first = queue.enqueue('job-1', 'first', 'first', async () => {
        firstStarted.resolve();
        await releaseFirst.promise;
        events.push('first finished');
        return true;
    });
    await firstStarted.promise;
    const second = queue.enqueue('job-1', 'second', 'second', async () => {
        secondStarted.resolve();
        await releaseSecond.promise;
        events.push('second finished');
        return true;
    });
    await delay(10);
    releaseFirst.resolve();
    await first;
    await secondStarted.promise;
    const third = queue.enqueue('job-1', 'third', 'third', () => {
        events.push('third finished');
        return true;
    });
    await delay(10);
    const beforeRelease = [...events];
    releaseSecond.resolve();
    assert.deepEqual(await Promise.all([second, third]), [true, true]);
    assert.deepEqual(beforeRelease, ['first finished']);
    assert.deepEqual(events, ['first finished', 'second finished', 'third finished']);
});

for (const failure of ['throw', 'reject', 'false']) {
    test(`a ${failure} result releases queued windows and allows conversation reuse`, async () => {
        const queue = new MessageMergeQueue<string>(1);
        const started = deferred();
        const release = deferred();
        const events: string[] = [];
        const first = queue.enqueue('job-1', 'first', 'first', async () => {
            started.resolve();
            await release.promise;
            events.push('failure');
            if (failure === 'throw') throw new Error('classification failed');
            if (failure === 'reject') return Promise.reject(new Error('classification rejected'));
            return false;
        });
        await started.promise;
        const second = queue.enqueue('job-1', 'second', 'second', () => {
            events.push('recovered');
            return true;
        });
        await delay(10);
        const beforeRelease = [...events];
        release.resolve();
        assert.deepEqual(await Promise.all([first, second]), [false, true]);
        assert.deepEqual(beforeRelease, []);
        assert.deepEqual(events, ['failure', 'recovered']);
        assert.equal(await queue.enqueue('job-1', 'third', 'third', () => true), true);
    });
}

test('every caller in a failed mixed burst gets false and can retry together', async () => {
    const queue = new MessageMergeQueue<number>(1);
    let attempts = 0;
    const process = () => {
        attempts++;
        throw new Error('request failed');
    };
    const first = queue.enqueue('job-1', 'hello', 1, process, '1');
    const second = queue.enqueue('job-1', 'resume', 7, process, '7');
    assert.equal(first, second);
    assert.deepEqual(await Promise.all([first, second]), [false, false]);
    assert.equal(attempts, 1);
    assert.deepEqual(await Promise.all([
        queue.enqueue('job-1', 'hello', 1, () => true, '1'),
        queue.enqueue('job-1', 'resume', 7, () => true, '7'),
    ]), [true, true]);
});

function metadataItem(type: unknown) {
    return {msgObj: {messages: [{from: {uid: 42}, type: 1, body: {type}}]}};
}

for (const types of [[1, 14], [14, 1], [7, 14], [14, 7], [1, 7, 14]]) {
    test(`interview confirmation survives a mixed burst of ${types.join(', ')}`, async () => {
        const queue = new MessageMergeQueue<ReturnType<typeof metadataItem>>(1);
        const decisions: number[] = [];
        const results = await Promise.all(types.map((type, index) => queue.enqueue(
            'interview-job',
            `Preview ${index}`,
            metadataItem(type),
            batch => {
                // This is the metadata sent to the existing backend contract.
                decisions.push(batchPlatformBodyTypeOf(batch.items) ?? -1);
                return true;
            },
            String(type),
        )));
        assert.deepEqual(results, types.map(() => true));
        assert.deepEqual(decisions, [14]);
    });
}

for (const {types, expected} of [
    {types: [7, 1], expected: 1},
    {types: [1, 7], expected: 1},
    {types: [7], expected: undefined},
    {types: [1], expected: 1},
    {types: [4, 4], expected: 4},
    {types: [8], expected: 8},
    {types: [12], expected: 12},
    {types: [4, 1], expected: 1},
    {types: [4, 7], expected: undefined},
    {types: [4, 8], expected: undefined},
    {types: ['14', 7], expected: undefined},
    {types: [undefined, 1], expected: 1},
    {types: [], expected: undefined},
]) {
    test(`batch metadata preserves the protocol boundary for ${JSON.stringify(types)}`, () => {
        assert.equal(batchPlatformBodyTypeOf(types.map(metadataItem)), expected);
    });
}

// Run the production classes, replacing browser storage, UI, and remote APIs only.
// Omit BOSS's startup scanner so these ingress tests never start background work.
function loadProductionClass(path: string, name: string, globals: Record<string, unknown>) {
    const source = ts.createSourceFile(path,
        readFileSync(new URL(path, import.meta.url), 'utf8'),
        ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
    const declaration = source.statements.find(node =>
        ts.isClassDeclaration(node) && node.name?.text === name) as ts.ClassDeclaration;
    assert.ok(declaration, `missing production class ${name}`);
    const withoutStartup = ts.factory.updateClassDeclaration(
        declaration, declaration.modifiers, declaration.name, declaration.typeParameters,
        declaration.heritageClauses,
        declaration.members.filter(member => !ts.isClassStaticBlockDeclaration(member)),
    );
    const code = ts.createPrinter().printNode(ts.EmitHint.Unspecified, withoutStartup, source);
    const compiled = ts.transpileModule(code, {
        compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS},
    }).outputText;
    const exports: Record<string, any> = {};
    runInNewContext(compiled, {exports, ...globals});
    return exports[name];
}

function ingressHarness() {
    ConversationPauseRegistry.reset();
    const storage = new Map<string, string>();
    const MessageCache = loadProductionClass('../src/platform/utils.ts', 'MessageCache', {
        GM_getValue: (key: string, fallback: string) => storage.get(key) ?? fallback,
        GM_setValue: (key: string, value: string) => storage.set(key, value),
        messageFingerprint, isCompletedMessage, terminalWithoutReply,
    });
    const status = {
        globalEnabled: true, manuallyPaused: false, gatePaused: false,
        globalRevision: 0, sessionRevision: 0,
    };
    const calls: Array<{question: string; bodyType?: number}> = [];
    const actions: string[] = [];
    let failNext = false;
    const log = {info() {}, warn() {}, error() {}, debug() {}, trace() {}};
    const BossOption = loadProductionClass('../src/platform/bossPlatform.ts', 'BossOption', {
        ...packetMessage,
        MessageCache, MessageMergeQueue, batchPlatformBodyTypeOf, normalizedMessageBatch,
        ConversationPauseRegistry, canDeliverAiSeatTurn, parseAiSeatRuntimeStatus,
        shouldDelegateConversationTextToAi,
        userStore: {user: {aiSeatStatus: true}},
        remotelyPausedJobs: new Set<string>(),
        Tools: {window: {_PAGE: {uid: 9}}, getEndChar: () => ''},
        getAiSeatStatusRevision: () => 0,
        logger: log, logging: log,
        LogRecorder: class {info = log.info; warn = log.warn; error = log.error;},
        JobSeekerClonedAnswerTypeEnum: {MSG_TEXT: 1, BOSS_OPERATION: 2, STOP: 3, AI_SERVICE_EXCEPTION: 4},
        BossOperationTypeEnum: {SEND_RESUME: 1},
        ElNotification() {},
        document: {querySelectorAll: () => []},
        setTimeout() {},
        MessageRead: class {send() {}},
        AiPower: {
            SEMANTIC_SCREENING_PROMPT: 'test',
            getSessionStatus: async () => ({data: {data: {...status}}}),
            ask: async (question: string, _job: string, _boss: unknown, _prompt: string, bodyType?: number) => {
                calls.push({question, bodyType});
                if (failNext) {
                    failNext = false;
                    throw new Error('remote request failed');
                }
                if (bodyType === 14) {
                    status.manuallyPaused = true;
                    status.sessionRevision++;
                    return {data: {data: {answerTypeList: [3], stopReason: 'USER_CONFIRMATION_REQUIRED'}}};
                }
                return {data: {data: {answerTypeList: [2], operationTypeList: [1]}}};
            },
        },
    });
    BossOption.messageMergeQueue = new MessageMergeQueue(1);
    const boss = new BossOption();
    boss.getBossUserInfoByBossId = async () => ({
        bossId: 42, encryptJobId: 'job-42', jobTitle: 'Test job',
    });
    boss.sendResumeFile = async (_bossId: number, canSend: () => Promise<boolean>) => {
        if (!(await canSend())) return false;
        actions.push('SEND_RESUME');
        return true;
    };
    const receive = (type: number, text = 'Please send your resume', senderId = 42, mid = `${type}-mid`) =>
        boss.handlerBossMessage({
            messages: [{
                mid, type: 1, from: {uid: senderId},
                to: {uid: 9}, body: {type, text},
            }],
        }, 42, text);
    return {
        boss, BossOption, status, calls, actions, receive,
        cache: BossOption.messageCache,
        entries: () => Object.values(JSON.parse(storage.get('messageCache') || '{}')) as any[],
        failNext: () => { failNext = true; },
    };
}

for (const types of [[1, 14], [14, 1]]) {
    test(`ingress retains identical-preview interview confirmation for ${types.join(' then ')}`, async () => {
        const harness = ingressHarness();
        await Promise.all(types.map(type => harness.receive(type)));
        assert.deepEqual(harness.calls.map(call => call.bodyType), [14]);
        assert.equal(harness.calls[0].question, 'Please send your resume Please send your resume');
        assert.deepEqual(harness.actions, []);
        assert.equal(harness.entries().every(entry => entry.completed === true), true);
        harness.status.manuallyPaused = false;
        ConversationPauseRegistry.resume('job-42:9');
        await harness.receive(14);
        assert.equal(harness.calls.length, 1, 'a completed card must not be classified again after resume');
    });
}

test('an interview card cannot consume a pending resume marker with the same preview', async () => {
    const harness = ingressHarness();
    harness.cache.markPendingOperation(42, 'Please send your resume', 'SEND_RESUME');
    await harness.receive(14);
    assert.deepEqual(harness.calls.map(call => call.bodyType), [14]);
    assert.equal(harness.cache.hasPendingOperation(42, 'Please send your resume', 'SEND_RESUME'), true);
    assert.deepEqual(harness.actions, []);
    await harness.receive(7);
    assert.deepEqual(harness.actions, [], 'the uncertain resume action must still suppress retries');
});

test('a paused interview replay leaves an unrelated completed text marker intact', async () => {
    const harness = ingressHarness();
    harness.cache.markMessageAsCompleted(42, 'Please send your resume');
    harness.status.manuallyPaused = true;
    await harness.receive(14);
    assert.deepEqual(harness.calls, []);
    assert.equal(harness.cache.isMessageCompleted(42, 'Please send your resume'), true);
});

test('failed identical-preview ingress batches clear every identity and retry safely', async () => {
    const harness = ingressHarness();
    harness.failNext();
    assert.deepEqual(await Promise.all([harness.receive(1), harness.receive(14)]), [false, false]);
    assert.deepEqual(harness.entries(), []);
    assert.deepEqual(await Promise.all([harness.receive(1), harness.receive(14)]), [true, true]);
    assert.deepEqual(harness.calls.map(call => call.bodyType), [14, 14]);
    assert.deepEqual(harness.actions, []);
    assert.equal(harness.entries().every(entry => entry.completed === true), true);
});

test('contact lookup failure clears only the interview identity and allows its retry', async () => {
    const harness = ingressHarness();
    harness.cache.markMessageAsCompleted(42, 'Please send your resume');
    const lookup = harness.boss.getBossUserInfoByBossId;
    harness.boss.getBossUserInfoByBossId = async () => { throw new Error('contact lookup failed'); };
    assert.equal(await harness.receive(14), false);
    assert.equal(harness.cache.isMessageCompleted(42, 'Please send your resume'), true);
    harness.boss.getBossUserInfoByBossId = lookup;
    assert.equal(await harness.receive(14), true);
    assert.deepEqual(harness.calls.map(call => call.bodyType), [14]);
    assert.equal(harness.entries().every(entry => entry.completed === true), true);
});

test('an oversized ingress batch terminates every identity without marking a reply sent', async () => {
    const harness = ingressHarness();
    const text = 'A'.repeat(5001);
    assert.deepEqual(await Promise.all([harness.receive(1, text), harness.receive(14, text)]), [true, true]);
    assert.deepEqual(harness.calls, []);
    assert.deepEqual(harness.actions, []);
    assert.equal(harness.entries().length, 2);
    assert.equal(harness.entries().every(entry => entry.completed === true && entry.replySent === false), true);
});

test('a new interview packet with the same preview still requires confirmation after resume', async () => {
    const harness = ingressHarness();
    await harness.receive(14, 'Same interview preview', 42, 'first-card');
    harness.status.manuallyPaused = false;
    ConversationPauseRegistry.resume('job-42:9');
    await harness.receive(14, 'Same interview preview', 42, 'second-card');
    assert.deepEqual(harness.calls.map(call => call.bodyType), [14, 14]);
    assert.deepEqual(harness.actions, []);
    assert.equal(harness.entries().every(entry => entry.completed === true), true);
});

test('same-preview interview packets coalesce once and complete every packet identity', async () => {
    const harness = ingressHarness();
    assert.deepEqual(await Promise.all([
        harness.receive(14, 'Same interview preview', 42, 'first-card'),
        harness.receive(14, 'Same interview preview', 42, 'second-card'),
    ]), [true, true]);
    assert.deepEqual(harness.calls, [{question: 'Same interview preview', bodyType: 14}]);
    assert.deepEqual(harness.actions, []);
    assert.equal(harness.entries().length, 2);
    assert.equal(harness.entries().every(entry => entry.completed === true), true);
});

for (const type of [1, 7]) {
    test(`normal type-${type} ingress dedupe still allows only one resume action`, async () => {
        const harness = ingressHarness();
        await Promise.all([harness.receive(type), harness.receive(type)]);
        await harness.receive(type === 1 ? 7 : 1);
        assert.equal(harness.calls.length, 1);
        assert.deepEqual(harness.actions, ['SEND_RESUME']);
        assert.equal(harness.cache.isMessageCompleted(42, 'Please send your resume'), true);
    });
}

test('interview identity does not bypass the packet sender check', async () => {
    const harness = ingressHarness();
    await harness.receive(14, 'Please send your resume', 99);
    assert.deepEqual(harness.calls, []);
    assert.deepEqual(harness.actions, []);
});
