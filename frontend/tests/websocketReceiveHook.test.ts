import assert from 'node:assert/strict';
import test from 'node:test';
import {installReceiveDispatchHook, installReceiveListener} from '../src/webSocket/receiveHook.ts';

class FakeSocket {
    readonly listeners: Array<(event: {data: unknown}) => void> = [];
    onmessage: ((event: {data: unknown}) => void) | null = null;

    addEventListener(type: string, listener: (event: {data: unknown}) => void): void {
        if (type === 'message') {
            this.listeners.push(listener);
        }
    }

    emit(data: unknown): void {
        const event = {data};
        this.listeners.forEach(listener => listener(event));
        this.onmessage?.(event);
    }
}

test('installs a receive listener even when the existing socket has no onmessage handler yet', () => {
    const socket = new FakeSocket();
    const received: unknown[] = [];

    installReceiveListener(socket, data => received.push(data));
    socket.onmessage = () => undefined;
    socket.emit('hr-message');

    assert.deepEqual(received, ['hr-message']);
});

test('does not install duplicate receive listeners for the same socket', () => {
    const socket = new FakeSocket();
    let calls = 0;

    installReceiveListener(socket, () => calls += 1);
    installReceiveListener(socket, () => calls += 1);
    socket.emit('one-message');

    assert.equal(calls, 1);
    assert.equal(socket.listeners.length, 1);
});

test('observes messages from a socket that existed before the hook was installed', () => {
    class ExistingSocket {
        onmessage: ((event: {type: string; data: unknown}) => void) | null = null;

        dispatchEvent(event: {type: string; data: unknown}): boolean {
            this.onmessage?.(event);
            return true;
        }

        emit(data: unknown): void {
            this.dispatchEvent({type: 'message', data});
        }
    }

    const socket = new ExistingSocket();
    const received: unknown[] = [];
    const installed = installReceiveDispatchHook(
        ExistingSocket.prototype,
        (target, event) => {
            if (target === socket && event.type === 'message') {
                received.push(event.data);
            }
        },
    );

    socket.emit('hr-message-before-any-send');

    assert.equal(installed, true);
    assert.deepEqual(received, ['hr-message-before-any-send']);
});

test('does not duplicate a message when both the prototype fallback and instance listener are present', () => {
    class ExistingSocket {
        readonly listeners: Array<(event: {type: string; data: unknown}) => void> = [];
        onmessage: ((event: {type: string; data: unknown}) => void) | null = null;

        addEventListener(type: string, listener: (event: {type: string; data: unknown}) => void): void {
            if (type === 'message') {
                this.listeners.push(listener);
            }
        }

        dispatchEvent(event: {type: string; data: unknown}): boolean {
            this.onmessage?.(event);
            this.listeners.forEach(listener => listener(event));
            return true;
        }
    }

    const socket = new ExistingSocket();
    const received: unknown[] = [];
    installReceiveListener(socket, data => received.push(data));
    installReceiveDispatchHook(ExistingSocket.prototype, (_target, event) => received.push(event.data));

    socket.dispatchEvent({type: 'message', data: 'one-message'});

    assert.deepEqual(received, ['one-message']);
});
