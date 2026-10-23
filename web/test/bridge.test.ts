import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  BridgeError,
  type Bridge,
  type JsBridgeHost,
  base64ToBytes,
  bytesToBase64,
  createBridge,
  utf8Decode,
  utf8Encode,
} from '../src/bridge';

class FakeHost implements JsBridgeHost {
  readonly sent: string[] = [];

  postMessage(message: string): void {
    this.sent.push(message);
  }

  deliver(frame: Record<string, unknown>): void {
    const receiver = (globalThis as unknown as { window: Window }).window.__jsbridge;
    if (!receiver) throw new Error('bridge receiver is not installed');
    receiver.receive(JSON.stringify({ v: 1, ...frame }));
  }

  last(): Record<string, unknown> {
    const frame = this.sent[this.sent.length - 1];
    if (!frame) throw new Error('no frame was sent');
    return JSON.parse(frame) as Record<string, unknown>;
  }
}

let host: FakeHost;
let bridge: Bridge | null = null;

beforeEach(() => {
  vi.useFakeTimers();
  (globalThis as unknown as { window: unknown }).window = {
    addEventListener: () => {},
    removeEventListener: () => {},
  };
  host = new FakeHost();
});

afterEach(() => {
  bridge?.close();
  bridge = null;
  vi.useRealTimers();
  delete (globalThis as unknown as { window?: unknown }).window;
});

function ack(schemaHash = ''): void {
  host.deliver({ kind: 'helloAck', schemaHash });
}

async function createReadyBridge(): Promise<Bridge> {
  bridge = createBridge({ host, readyTimeoutMs: 1000, defaultTimeoutMs: 1000 });
  const ready = bridge.ready;
  ack();
  await ready;
  return bridge;
}

async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

describe('bridge runtime', () => {
  it('sends hello on creation', () => {
    bridge = createBridge({ host });
    const hello = host.last();
    expect(hello.kind).toBe('hello');
    expect(hello.v).toBe(1);
  });

  it('resolves ready after helloAck', async () => {
    bridge = createBridge({ host, readyTimeoutMs: 1000 });
    const ready = bridge.ready;
    ack();
    await ready;
  });

  it('resolves a call from a response', async () => {
    const b = await createReadyBridge();
    const resultPromise = b.call('native.echo', utf8Encode('hello'));
    await flush();

    const request = host.last();
    expect(request.kind).toBe('request');
    expect(request.method).toBe('native.echo');

    host.deliver({
      kind: 'response',
      id: request.id,
      payload: bytesToBase64(utf8Encode('world')),
    });

    expect(utf8Decode(await resultPromise)).toBe('world');
  });

  it('rejects a call from an error response', async () => {
    const b = await createReadyBridge();
    const resultPromise = b.call('native.fail', new Uint8Array());
    await flush();

    const request = host.last();
    host.deliver({
      kind: 'response',
      id: request.id,
      error: { code: 'INVALID_ARGUMENT', message: 'bad input' },
    });

    await expect(resultPromise).rejects.toMatchObject({
      code: 'INVALID_ARGUMENT',
      message: 'bad input',
    });
  });

  it('times out and sends cancel', async () => {
    const b = await createReadyBridge();
    const resultPromise = b.call('native.slow', new Uint8Array(), { timeoutMs: 100 });
    await flush();

    const request = host.last();
    vi.advanceTimersByTime(101);

    await expect(resultPromise).rejects.toMatchObject({ code: 'DEADLINE_EXCEEDED' });
    const cancel = host.last();
    expect(cancel.kind).toBe('cancel');
    expect(cancel.id).toBe(request.id);
  });

  it('aborts and sends cancel', async () => {
    const b = await createReadyBridge();
    const controller = new AbortController();
    const resultPromise = b.call('native.slow', new Uint8Array(), {
      signal: controller.signal,
    });
    await flush();

    const request = host.last();
    controller.abort();

    await expect(resultPromise).rejects.toMatchObject({ code: 'CANCELLED' });
    const cancel = host.last();
    expect(cancel.kind).toBe('cancel');
    expect(cancel.id).toBe(request.id);
  });

  it('invokes a registered handler', async () => {
    const b = await createReadyBridge();
    b.register('js.echo', async (payload) => payload);

    host.deliver({
      kind: 'request',
      id: 'w-1',
      method: 'js.echo',
      payload: bytesToBase64(utf8Encode('ping')),
    });
    await flush();
    await flush();

    const response = host.last();
    expect(response.kind).toBe('response');
    expect(response.id).toBe('w-1');
    expect(utf8Decode(base64ToBytes(String(response.payload)))).toBe('ping');
  });

  it('aborts an inbound handler when cancel arrives', async () => {
    const b = await createReadyBridge();
    let signal: AbortSignal | null = null;
    b.register(
      'js.slow',
      (_payload, context) =>
        new Promise<Uint8Array>((_resolve, reject) => {
          signal = context.signal;
          context.signal.addEventListener('abort', () =>
            reject(new DOMException('aborted', 'AbortError')),
          );
        }),
    );

    host.deliver({
      kind: 'request',
      id: 'w-1',
      method: 'js.slow',
    });
    await flush();
    expect(signal).not.toBeNull();

    host.deliver({ kind: 'cancel', id: 'w-1' });
    await flush();
    expect(signal!.aborted).toBe(true);
  });

  it('waits for ready before sending a call', async () => {
    bridge = createBridge({ host, readyTimeoutMs: 1000 });
    const b = bridge;
    const resultPromise = b.call('native.echo', utf8Encode('hello'));
    await flush();
    expect(host.sent.filter((frame) => JSON.parse(frame).kind === 'request')).toHaveLength(0);

    ack();
    await flush();
    const request = host.last();
    expect(request.kind).toBe('request');

    host.deliver({
      kind: 'response',
      id: request.id,
      payload: bytesToBase64(utf8Encode('ok')),
    });
    expect(utf8Decode(await resultPromise)).toBe('ok');
  });

  it('closes pending calls', async () => {
    const b = await createReadyBridge();
    const resultPromise = b.call('native.echo', utf8Encode('hello'));
    await flush();

    b.close();
    await expect(resultPromise).rejects.toMatchObject({ code: 'BRIDGE_CLOSED' });
  });

  it('rejects unknown error codes as INTERNAL', async () => {
    const b = await createReadyBridge();
    const resultPromise = b.call('native.echo', new Uint8Array());
    await flush();
    const request = host.last();

    host.deliver({
      kind: 'response',
      id: request.id,
      error: { code: 'SOMETHING_NEW', message: 'unknown' },
    });
    await expect(resultPromise).rejects.toMatchObject({ code: 'INTERNAL' });
  });

  it('ignores malformed frames', async () => {
    bridge = createBridge({
      host,
      logger: { debug: () => {}, warn: () => {}, error: () => {} },
    });
    (globalThis as unknown as { window: Window }).window.__jsbridge!.receive('not json');
    await flush();
    expect(host.sent).toHaveLength(1);
  });

  it('rejects calls when the payload is too large', async () => {
    const b = await createReadyBridge();
    await expect(
      b.call('native.echo', new Uint8Array(2 * 1024 * 1024)),
    ).rejects.toBeInstanceOf(BridgeError);
  });
});
