import assert from 'node:assert/strict';
import test from 'node:test';
import * as friendSource from '../src/platform/friendSource.ts';

test('message sender is read from message props, never inherited contact data', () => {
    const readSender = typeof friendSource.readMessageSenderId === 'function'
        ? friendSource.readMessageSenderId
        : () => null;
    const contact = {props: {source: {uid: 200}}};

    assert.equal(readSender({
        __vueParentComponent: {props: {message: {from: {uid: 100}}}, parent: contact},
    }), 100);
    assert.equal(readSender({
        __vueParentComponent: {props: {from: {uid: 200}}, parent: contact},
    }), 200);
    assert.equal(readSender({
        __vueParentComponent: {props: {}, parent: contact},
    }), null);
    assert.equal(readSender({getAttribute: (name: string) => name === 'data-from-uid' ? '300' : null}), 300);
    assert.equal(readSender(null), null);
});
