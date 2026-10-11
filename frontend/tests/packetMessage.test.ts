import assert from 'node:assert/strict';
import test from 'node:test';
import {
    bodyImageOf,
    bodyTypeOf,
    firstMessageOf,
    lastMessageOf,
    messageIdOf,
    msgTypeOf,
    senderOf,
} from '../src/webSocket/packetMessage.ts';

const packetWithOne = () => ({
    type: 1,
    messages: [{from: {uid: '101', name: '张经理', avatar: 'https://a/1.png'}, type: 1, mid: 'mid-1', body: {type: 7, image: {tinyImage: {url: 'x'}}}}],
});

const packetWithTwo = () => ({
    type: 1,
    messages: [
        {from: {uid: '101', name: '张经理'}, type: 1, mid: 'mid-1', body: {type: 1, text: 'first'}},
        {from: {uid: '202', name: '李经理'}, type: 3, mid: 'mid-2', body: {type: 8, image: {tinyImage: {url: 'y'}}}},
    ],
});

const malformedPackets = [
    {type: 1},
    {type: 1, messages: undefined},
    {type: 1, messages: null},
    {type: 1, messages: []},
    {type: 1, messages: 'not-an-array'},
    {type: 1, messages: {}},
    {type: 1, messages: 42},
    null,
    undefined,
];

test('lastMessageOf returns the final message of a batch', () => {
    const packet = packetWithTwo();
    assert.strictEqual(lastMessageOf(packet), packet.messages[1]);
    assert.equal(lastMessageOf(packet)?.body.text, undefined);
    assert.equal(lastMessageOf(packet)?.body.type, 8);
});

test('firstMessageOf returns the head message of a batch', () => {
    const packet = packetWithTwo();
    assert.strictEqual(firstMessageOf(packet), packet.messages[0]);
    assert.equal(firstMessageOf(packet)?.body.text, 'first');
});

test('every accessor returns undefined instead of throwing on malformed packets', () => {
    for (const packet of malformedPackets) {
        assert.equal(lastMessageOf(packet as any), undefined);
        assert.equal(firstMessageOf(packet as any), undefined);
        assert.equal(bodyTypeOf(packet as any), undefined);
        assert.equal(bodyImageOf(packet as any), undefined);
        assert.equal(msgTypeOf(packet as any), undefined);
        assert.equal(messageIdOf(packet as any), undefined);
        assert.deepEqual(senderOf(packet as any), {name: '', avatar: ''});
    }
});

test('accessors read the head message fields of a single-message packet', () => {
    const packet = packetWithOne();
    assert.equal(bodyTypeOf(packet as any), 7);
    assert.equal(msgTypeOf(packet as any), 1);
    assert.equal(messageIdOf(packet as any), 'mid-1');
    assert.deepEqual(bodyImageOf(packet as any), {tinyImage: {url: 'x'}});
    assert.equal(senderOf(packet as any).name, '张经理');
    assert.equal(senderOf(packet as any).avatar, 'https://a/1.png');
});

test('accessors read the head message, not the tail, after a split packet', () => {
    const split = {type: 1, messages: [packetWithTwo().messages[1]]};
    assert.equal(bodyTypeOf(split as any), 8);
    assert.equal(msgTypeOf(split as any), 3);
    assert.equal(messageIdOf(split as any), 'mid-2');
    assert.equal(senderOf(split as any).name, '李经理');
});

test('senderOf never throws when from is missing or not an object', () => {
    assert.deepEqual(senderOf({messages: [{mid: 'm'}]} as any), {name: '', avatar: ''});
    assert.deepEqual(senderOf({messages: [{from: null}]} as any), {name: '', avatar: ''});
    assert.deepEqual(senderOf({messages: [{from: 'uid-1'}]} as any), {name: '', avatar: ''});
});

test('bodyImageOf exposes the truthy image object used to filter image sends', () => {
    assert.equal(Boolean(bodyImageOf({messages: [{body: {type: 1, text: 'plain'}}]} as any)), false);
    assert.equal(Boolean(bodyImageOf({messages: [{body: {type: 1, image: {tinyImage: {url: 'z'}}}}]} as any)), true);
});