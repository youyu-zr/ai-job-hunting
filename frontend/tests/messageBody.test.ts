import assert from 'node:assert/strict';
import test from 'node:test';
import {decodeMessageBatch, decodeMessageBody, getMsgBody, mapMessageBody, normalizedMessageBatch} from '../src/webSocket/messageBody.ts';

test('decodes every message of a batch, not only the first one', () => {
    const messages = [
        {from: {uid: '101'}, body: {type: 1, text: 'first'}},
        {from: {uid: '202'}, body: {type: 1}},
        {from: {uid: '303'}, body: {type: 1}},
    ];

    const handled = decodeMessageBatch(messages, 'payload-text', '接收');

    assert.equal(handled, 3);
    assert.deepEqual(messages.map(message => message.body.text), ['first', 'payload-text', 'payload-text']);
});

test('types the seventh body type for every message of a batch', () => {
    const messages = [
        {from: {uid: '101'}, body: {type: 7, text: 'stale-first'}},
        {from: {uid: '202'}, body: {type: 7}},
    ];

    decodeMessageBatch(messages, 'exchange-payload', '接收');

    assert.deepEqual(messages.map(message => message.body.text), ['exchange-payload', 'exchange-payload']);
});

test('keeps the send path free of fallback decoding', () => {
    const messages = [{from: {uid: '1'}, body: {type: 1}}];

    decodeMessageBatch(messages, 'outbound-text', '发送');

    assert.equal(messages[0].body.text, undefined);
});

test('message decoding is null safe and never throws', () => {
    assert.equal(decodeMessageBatch(undefined as any, 'text', '接收'), 0);
    assert.equal(decodeMessageBatch(null as any, 'text', '接收'), 0);
    assert.equal(decodeMessageBatch('not-an-array' as any, 'text', '接收'), 0);
    assert.equal(decodeMessageBatch([], 'text', '接收'), 0);
    assert.equal(decodeMessageBatch([{}, {body: null}, {body: 'x'}], 'text', '接收'), 3);
    assert.equal(decodeMessageBody(undefined as any, 'text', '接收'), false);
    assert.equal(decodeMessageBody({}, 'text', '接收'), false);
    assert.doesNotThrow(() => decodeMessageBatch([{body: {type: 7}}], 'text', '接收'));
});

test('getMsgBody returns the last readable message of a batch', () => {
    const packet = {
        type: 1,
        messages: [
            {from: {uid: '101'}, body: {type: 1, text: 'first'}},
            {from: {uid: '202'}, body: {type: 1, text: 'second'}},
        ],
    };

    assert.equal(getMsgBody(packet), 'second');
});

test('getMsgBody skips undecodable trailing messages instead of dropping the batch', () => {
    const packet = {
        type: 1,
        messages: [
            {from: {uid: '101'}, body: {type: 1, text: 'only-readable'}},
            {from: {uid: '202'}, body: {type: 1}},
        ],
    };

    assert.equal(getMsgBody(packet), 'only-readable');
});

test('getMsgBody keeps the historical single-message behaviour', () => {
    assert.equal(getMsgBody({messages: [{body: {text: 'single'}}]}), 'single');
    assert.equal(getMsgBody({messages: [{body: {type: 7, resume: {}}}]}), 'HR明确请求发送简历（结构化消息）');
    assert.equal(getMsgBody({messages: [{body: {type: 7, resumeShare: {}}}]}), '对方已分享简历（结构化消息）');
});

test('getMsgBody is null safe and never throws', () => {
    const payloads: any[] = [
        undefined,
        null,
        {},
        {messages: null},
        {messages: undefined},
        {messages: 'not-an-array'},
        {messages: []},
        {messages: [{body: {}}]},
        {messages: [{}, {}]},
    ];

    for (const payload of payloads) {
        assert.equal(getMsgBody(payload), '');
    }
    assert.equal(mapMessageBody(undefined), '');
    assert.equal(mapMessageBody({}), '');
});

test('normalizes a complete merged batch without truncating content after the ceiling', () => {
    const withinLimit = normalizedMessageBatch(['  first  ', 'second']);
    const overLimit = normalizedMessageBatch(['a'.repeat(3000), 'b'.repeat(2001)]);

    assert.equal(withinLimit.text, 'first second');
    assert.equal(withinLimit.tooLong, false);
    assert.equal(overLimit.text.length, 5002);
    assert.equal(overLimit.tooLong, true);
});

console.log('message body batch smoke: PASS');
