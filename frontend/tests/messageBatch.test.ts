import assert from 'node:assert/strict';
import test from 'node:test';
import {splitChatProtocolMessages} from '../src/webSocket/messageBatch.ts';

test('splits a multi-message packet while preserving order and metadata', () => {
    const first = {from: {uid: '101'}, to: {uid: '1'}, type: 1, body: {type: 1, templateId: 1, text: 'first'}};
    const second = {from: {uid: '202'}, to: {uid: '1'}, type: 1, body: {type: 1, templateId: 1, text: 'second'}};
    const packet = {type: 1, messages: [first, second]} as any;

    const result = splitChatProtocolMessages(packet);

    assert.deepEqual(result.map(item => item.messages[0].from.uid), ['101', '202']);
    assert.equal(result.every(item => item.messages.length === 1), true);
    assert.equal(result[0].type, packet.type);
});

test('returns a single-message packet unchanged', () => {
    const message = {from: {uid: '101'}, to: {uid: '1'}, type: 1, body: {type: 1, templateId: 1, text: 'only'}};
    const packet = {type: 1, messages: [message]} as any;

    const result = splitChatProtocolMessages(packet);

    assert.deepEqual(result, [packet]);
    assert.strictEqual(result[0], packet);
});

test('does not throw and returns a one-element array when messages is missing', () => {
    const packet = {type: 1, sessionId: 'session-without-messages'} as any;

    const result = splitChatProtocolMessages(packet);

    assert.equal(result.length, 1);
    assert.strictEqual(result[0], packet);
    assert.deepEqual(result[0], {type: 1, sessionId: 'session-without-messages'});
});

test('does not throw and returns a one-element array when messages is not an array', () => {
    const payloads = [
        {type: 1, messages: 'not-an-array'},
        {type: 1, messages: {from: {uid: '101'}}},
        {type: 1, messages: null},
        {type: 1, messages: undefined},
        {type: 1, messages: 42},
    ];

    for (const packet of payloads) {
        const result = splitChatProtocolMessages(packet as any);

        assert.equal(result.length, 1);
        assert.strictEqual(result[0], packet);
    }
});

test('does not throw when the packet itself is null or undefined', () => {
    assert.deepEqual(splitChatProtocolMessages(null as any), [null]);
    assert.deepEqual(splitChatProtocolMessages(undefined as any), [undefined]);
});

test('returns an empty-message packet unchanged in a one-element array', () => {
    const packet = {type: 1, messages: [] as unknown[]} as any;

    const result = splitChatProtocolMessages(packet);

    assert.equal(result.length, 1);
    assert.strictEqual(result[0], packet);
    assert.strictEqual(result[0].messages, packet.messages);
});

test('splits every message of a larger batch in order without sharing the original array', () => {
    const messages = Array.from({length: 5}, (_, index) => ({
        from: {uid: String(100 + index)},
        to: {uid: '1'},
        type: 1,
        body: {type: 1, templateId: 1, text: `message-${index}`},
    }));
    const packet = {type: 7, requestId: 'req-1', extra: {trace: 'abc'}, messages} as any;

    const result = splitChatProtocolMessages(packet);

    assert.equal(result.length, messages.length);
    assert.deepEqual(
        result.map(item => item.messages[0].body.text),
        messages.map(message => message.body.text),
    );
    assert.deepEqual(result.map(item => item.messages[0].from.uid), messages.map(message => message.from.uid));
    const ownArrays = new Set<unknown>();
    result.forEach((item, index) => {
        assert.notStrictEqual(item.messages, packet.messages);
        assert.strictEqual(item.messages[0], messages[index]);
        assert.equal(item.type, packet.type);
        assert.equal(item.requestId, packet.requestId);
        assert.strictEqual(item.extra, packet.extra);
        ownArrays.add(item.messages);
    });
    assert.equal(ownArrays.size, result.length);
    assert.equal(packet.messages, messages);
    assert.equal(packet.messages.length, messages.length);
});

test('does not mutate the input packet while splitting', () => {
    const first = {from: {uid: '101'}, to: {uid: '1'}, type: 1, body: {text: 'first'}};
    const second = {from: {uid: '202'}, to: {uid: '1'}, type: 1, body: {text: 'second'}};
    const messages = [first, second];
    const packet = {type: 1, messages} as any;

    splitChatProtocolMessages(packet);

    assert.deepEqual(packet.messages, [first, second]);
    assert.strictEqual(packet.messages, messages);
    assert.equal(packet.messages.length, 2);
});
