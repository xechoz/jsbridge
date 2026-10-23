import {
  BridgeError,
  type BridgeErrorCode,
  type Envelope,
  PROTOCOL_VERSION,
  base64ToBytes,
  bytesToBase64,
  decodeEnvelope,
  encodeEnvelope,
  utf8Decode,
  utf8Encode,
} from './protocol';

export interface JsBridgeHost {
  postMessage(message: string): void;
}

export interface BridgeLogger {
  debug(message: string, error?: unknown): void;
  warn(message: string, error?: unknown): void;
  error(message: string, error?: unknown): void;
}

export interface CallOptions {
  /** 0 disables the timeout. Defaults to the bridge default. */
  timeoutMs?: number;
  signal?: AbortSignal;
}

export interface CallContext {
  callId: string;
  signal: AbortSignal;
}

export type BridgeHandler = (
  payload: Uint8Array,
  context: CallContext,
) => Uint8Array | Promise<Uint8Array>;

export interface Disposable {
  dispose(): void;
}

export interface Bridge {
  readonly ready: Promise<void>;
  call(method: string, payload: Uint8Array, options?: CallOptions): Promise<Uint8Array>;
  callText(method: string, text: string, options?: CallOptions): Promise<string>;
  register(method: string, handler: BridgeHandler): Disposable;
  registerText(
    method: string,
    handler: (text: string, context: CallContext) => string | Promise<string>,
  ): Disposable;
  close(): void;
}

export interface BridgeOptions {
  host?: JsBridgeHost;
  defaultTimeoutMs?: number;
  readyTimeoutMs?: number;
  maxMessageBytes?: number;
  schemaHash?: string;
  logger?: BridgeLogger;
}

declare global {
  interface Window {
    __jsbridgeHost?: JsBridgeHost;
    __jsbridge?: { receive(frame: string): void };
  }
}

interface PendingCall {
  resolve(value: Uint8Array): void;
  reject(reason: BridgeError): void;
  timer?: ReturnType<typeof setTimeout>;
  signal?: AbortSignal;
  abortListener?: () => void;
  settled: boolean;
}

interface Deferred<T> {
  promise: Promise<T>;
  resolve(value: T): void;
  reject(reason: unknown): void;
}

const DEFAULT_TIMEOUT_MS = 30_000;
const DEFAULT_READY_TIMEOUT_MS = 5_000;
const DEFAULT_MAX_MESSAGE_BYTES = 1 << 20;

const consoleLogger: BridgeLogger = {
  debug(message) {
    console.debug(`[JsBridge] ${message}`);
  },
  warn(message, error) {
    console.warn(`[JsBridge] ${message}`, error);
  },
  error(message, error) {
    console.error(`[JsBridge] ${message}`, error);
  },
};

class BridgeImpl implements Bridge {
  readonly ready: Promise<void>;

  private readonly targetWindow: Window;
  private readonly host: JsBridgeHost;
  private readonly defaultTimeoutMs: number;
  private readonly readyTimeoutMs: number;
  private readonly maxMessageBytes: number;
  private readonly schemaHash: string;
  private readonly logger: BridgeLogger;
  private readonly receiver = {
    receive: (frame: string) => this.receive(frame),
  };

  private readonly readyDeferred: Deferred<void> = createDeferred<void>();
  private readonly pending = new Map<string, PendingCall>();
  private readonly handlers = new Map<string, BridgeHandler>();
  private readonly inbound = new Map<string, AbortController>();
  private readonly pageHideListener: () => void;

  private readySettled = false;
  private readyTimer: ReturnType<typeof setTimeout> | undefined;
  private closed = false;
  private idCounter = 0;

  constructor(options: BridgeOptions) {
    if (typeof window === 'undefined') {
      throw new BridgeError('UNAVAILABLE', 'JsBridge requires a browser window');
    }
    this.targetWindow = window;
    const host = options.host ?? window.__jsbridgeHost;
    if (!host || typeof host.postMessage !== 'function') {
      throw new BridgeError(
        'UNAVAILABLE',
        'window.__jsbridgeHost is not available; attach the Android bridge before loading the page',
      );
    }

    this.host = host;
    this.defaultTimeoutMs = options.defaultTimeoutMs ?? DEFAULT_TIMEOUT_MS;
    this.readyTimeoutMs = options.readyTimeoutMs ?? DEFAULT_READY_TIMEOUT_MS;
    this.maxMessageBytes = options.maxMessageBytes ?? DEFAULT_MAX_MESSAGE_BYTES;
    this.schemaHash = options.schemaHash ?? '';
    this.logger = options.logger ?? consoleLogger;

    this.ready = this.readyDeferred.promise;
    // Avoid an unhandled rejection when the app never awaits ready().
    void this.ready.catch(() => {});

    this.readyTimer = setTimeout(() => {
      if (this.readySettled) return;
      this.readySettled = true;
      this.readyDeferred.reject(
        new BridgeError(
          'UNAVAILABLE',
          `JS bridge handshake timed out after ${this.readyTimeoutMs}ms`,
        ),
      );
    }, this.readyTimeoutMs);

    window.__jsbridge = this.receiver;
    this.pageHideListener = () => this.failAll('PAGE_RELOADED', 'page hidden');
    if (typeof window.addEventListener === 'function') {
      window.addEventListener('pagehide', this.pageHideListener);
    }

    try {
      this.post({ v: PROTOCOL_VERSION, kind: 'hello', schemaHash: this.schemaHash });
    } catch (error) {
      if (!this.readySettled) {
        this.readySettled = true;
        this.readyDeferred.reject(
          new BridgeError('UNAVAILABLE', 'failed to send hello', undefined, {
            cause: error,
          }),
        );
      }
    }
  }

  get isClosed(): boolean {
    return this.closed;
  }

  async call(
    method: string,
    payload: Uint8Array,
    options: CallOptions = {},
  ): Promise<Uint8Array> {
    if (this.closed) throw new BridgeError('BRIDGE_CLOSED', 'bridge closed');
    if (!method) throw new BridgeError('INVALID_ARGUMENT', 'method must not be empty');
    if (payload.byteLength > this.maxMessageBytes) {
      throw new BridgeError(
        'MESSAGE_TOO_LARGE',
        `payload is ${payload.byteLength} bytes; limit is ${this.maxMessageBytes}`,
      );
    }

    await this.ready;
    if (this.closed) throw new BridgeError('BRIDGE_CLOSED', 'bridge closed');

    const timeoutMs = options.timeoutMs ?? this.defaultTimeoutMs;
    if (!Number.isFinite(timeoutMs) || timeoutMs < 0) {
      throw new BridgeError('INVALID_ARGUMENT', `invalid timeout: ${timeoutMs}`);
    }
    if (options.signal?.aborted) {
      throw new BridgeError('CANCELLED', 'call aborted');
    }

    const id = this.nextId();
    return new Promise<Uint8Array>((resolve, reject) => {
      const pending: PendingCall = { resolve, reject, settled: false };
      this.pending.set(id, pending);

      if (timeoutMs > 0) {
        pending.timer = setTimeout(() => {
          if (!this.takePending(pending, id)) return;
          this.sendCancel(id, 'timeout');
          reject(
            new BridgeError(
              'DEADLINE_EXCEEDED',
              `call timed out after ${timeoutMs}ms: ${method}`,
            ),
          );
        }, timeoutMs);
      }

      if (options.signal) {
        const signal = options.signal;
        const onAbort = () => {
          if (!this.takePending(pending, id)) return;
          this.sendCancel(id, 'cancelled');
          reject(new BridgeError('CANCELLED', 'call aborted'));
        };
        pending.signal = signal;
        pending.abortListener = onAbort;
        signal.addEventListener('abort', onAbort, { once: true });
      }

      try {
        this.post({
          v: PROTOCOL_VERSION,
          kind: 'request',
          id,
          method,
          payload: bytesToBase64(payload),
        });
      } catch (error) {
        pending.settled = true;
        this.pending.delete(id);
        this.cleanupPending(pending);
        reject(
          error instanceof BridgeError
            ? error
            : new BridgeError('UNAVAILABLE', 'failed to send request', undefined, {
                cause: error,
              }),
        );
      }
    });
  }

  callText(method: string, text: string, options?: CallOptions): Promise<string> {
    return this.call(method, utf8Encode(text), options).then(utf8Decode);
  }

  register(method: string, handler: BridgeHandler): Disposable {
    if (this.closed) throw new BridgeError('BRIDGE_CLOSED', 'bridge closed');
    if (!method) throw new BridgeError('INVALID_ARGUMENT', 'method must not be empty');
    if (this.handlers.has(method)) {
      throw new BridgeError('INVALID_ARGUMENT', `handler already registered: ${method}`);
    }
    this.handlers.set(method, handler);
    return {
      dispose: () => {
        if (this.handlers.get(method) === handler) {
          this.handlers.delete(method);
        }
      },
    };
  }

  registerText(
    method: string,
    handler: (text: string, context: CallContext) => string | Promise<string>,
  ): Disposable {
    return this.register(method, async (payload, context) => {
      const request = utf8Decode(payload);
      const response = await handler(request, context);
      return utf8Encode(response);
    });
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;

    if (this.readyTimer !== undefined) {
      clearTimeout(this.readyTimer);
      this.readyTimer = undefined;
    }
    if (!this.readySettled) {
      this.readySettled = true;
      this.readyDeferred.reject(new BridgeError('BRIDGE_CLOSED', 'bridge closed'));
    }
    this.failAll('BRIDGE_CLOSED', 'bridge closed');

    if (typeof this.targetWindow.removeEventListener === 'function') {
      this.targetWindow.removeEventListener('pagehide', this.pageHideListener);
    }
    if (this.targetWindow.__jsbridge === this.receiver) {
      delete this.targetWindow.__jsbridge;
    }
    if (activeBridge === this) {
      activeBridge = null;
    }
  }

  private receive(frame: string): void {
    if (this.closed) return;
    if (frame.length > this.maxMessageBytes * 2) {
      this.logger.warn(`dropping oversized frame (${frame.length} chars)`);
      return;
    }
    let envelope: Envelope;
    try {
      envelope = decodeEnvelope(frame);
    } catch (error) {
      this.logger.warn('dropping malformed frame', error);
      return;
    }

    switch (envelope.kind) {
      case 'helloAck':
        this.handleHelloAck(envelope);
        return;
      case 'request':
        this.handleRequest(envelope);
        return;
      case 'response':
        this.handleResponse(envelope);
        return;
      case 'cancel':
        this.handleCancel(envelope);
        return;
      case 'hello':
        // The Android side initiates the handshake by acknowledging ours.
        return;
    }
  }

  private handleHelloAck(envelope: Envelope): void {
    this.checkSchema(envelope.schemaHash);
    if (this.readySettled) return;
    this.readySettled = true;
    if (this.readyTimer !== undefined) {
      clearTimeout(this.readyTimer);
      this.readyTimer = undefined;
    }
    this.readyDeferred.resolve();
  }

  private handleResponse(envelope: Envelope): void {
    const id = envelope.id;
    if (!id) return;
    const pending = this.pending.get(id);
    if (!pending || pending.settled) return;

    pending.settled = true;
    this.pending.delete(id);
    this.cleanupPending(pending);

    if (envelope.error) {
      pending.reject(new BridgeError(envelope.error.code, envelope.error.message));
      return;
    }

    if (!envelope.payload) {
      pending.resolve(new Uint8Array(0));
      return;
    }

    try {
      pending.resolve(base64ToBytes(envelope.payload));
    } catch (error) {
      pending.reject(
        new BridgeError('DATA_LOSS', 'failed to decode response payload', undefined, {
          cause: error,
        }),
      );
    }
  }

  private handleRequest(envelope: Envelope): void {
    const id = envelope.id;
    const method = envelope.method;
    if (!id || !method) {
      this.logger.warn('dropping request without id/method');
      return;
    }

    const handler = this.handlers.get(method);
    if (!handler) {
      this.sendError(id, 'UNIMPLEMENTED', `no handler registered for ${method}`);
      return;
    }

    let payload: Uint8Array;
    try {
      payload = envelope.payload ? base64ToBytes(envelope.payload) : new Uint8Array(0);
    } catch (error) {
      this.sendError(id, 'INVALID_ARGUMENT', 'failed to decode request payload');
      this.logger.warn('failed to decode request payload', error);
      return;
    }

    if (payload.byteLength > this.maxMessageBytes) {
      this.sendError(id, 'MESSAGE_TOO_LARGE', 'request payload too large');
      return;
    }

    const controller = new AbortController();
    this.inbound.set(id, controller);

    void (async () => {
      try {
        const result = await handler(payload, {
          callId: id,
          signal: controller.signal,
        });
        if (!controller.signal.aborted) {
          this.sendResponse(id, result);
        }
      } catch (error) {
        if (!controller.signal.aborted) {
          this.sendHandlerError(id, error);
        }
      } finally {
        if (this.inbound.get(id) === controller) {
          this.inbound.delete(id);
        }
      }
    })();
  }

  private handleCancel(envelope: Envelope): void {
    const id = envelope.id;
    if (!id) return;

    const controller = this.inbound.get(id);
    if (controller) {
      controller.abort();
      this.inbound.delete(id);
      return;
    }

    const pending = this.pending.get(id);
    if (!pending || pending.settled) return;
    pending.settled = true;
    this.pending.delete(id);
    this.cleanupPending(pending);
    pending.reject(new BridgeError('CANCELLED', envelope.reason ?? 'cancelled'));
  }

  private sendResponse(id: string, payload: Uint8Array): void {
    if (this.closed) return;
    if (payload.byteLength > this.maxMessageBytes) {
      this.sendError(id, 'MESSAGE_TOO_LARGE', 'response payload too large');
      return;
    }
    this.tryPost({
      v: PROTOCOL_VERSION,
      kind: 'response',
      id,
      payload: bytesToBase64(payload),
    });
  }

  private sendHandlerError(id: string, error: unknown): void {
    if (error instanceof BridgeError) {
      this.sendError(id, error.code, error.message);
      return;
    }
    const message = error instanceof Error ? error.message : String(error);
    this.sendError(id, 'INTERNAL', message || 'handler failed');
  }

  private sendError(id: string, code: BridgeErrorCode, message: string): void {
    if (this.closed) return;
    this.tryPost({
      v: PROTOCOL_VERSION,
      kind: 'response',
      id,
      error: { code, message },
    });
  }

  private sendCancel(id: string, reason: string): void {
    if (this.closed) return;
    this.tryPost({
      v: PROTOCOL_VERSION,
      kind: 'cancel',
      id,
      reason,
    });
  }

  private post(envelope: Envelope): void {
    this.host.postMessage(encodeEnvelope(envelope));
  }

  private tryPost(envelope: Envelope): void {
    try {
      this.post(envelope);
    } catch (error) {
      this.logger.warn(`failed to send ${envelope.kind} frame`, error);
    }
  }

  private failAll(code: BridgeErrorCode, message: string): void {
    for (const [id, pending] of this.pending) {
      if (pending.settled) continue;
      pending.settled = true;
      this.cleanupPending(pending);
      this.pending.delete(id);
      pending.reject(new BridgeError(code, message));
    }
    this.pending.clear();

    for (const controller of this.inbound.values()) {
      controller.abort();
    }
    this.inbound.clear();
  }

  private takePending(pending: PendingCall, id: string): boolean {
    if (pending.settled) return false;
    if (this.pending.get(id) !== pending) return false;
    pending.settled = true;
    this.pending.delete(id);
    this.cleanupPending(pending);
    return true;
  }

  private cleanupPending(pending: PendingCall): void {
    if (pending.timer !== undefined) {
      clearTimeout(pending.timer);
      pending.timer = undefined;
    }
    if (pending.signal && pending.abortListener) {
      pending.signal.removeEventListener('abort', pending.abortListener);
      pending.abortListener = undefined;
    }
  }

  private checkSchema(remoteHash?: string): void {
    if (remoteHash && this.schemaHash && remoteHash !== this.schemaHash) {
      this.logger.warn(
        `schema hash mismatch: local=${this.schemaHash} remote=${remoteHash}`,
      );
    }
  }

  private nextId(): string {
    this.idCounter += 1;
    return `w-${this.idCounter}-${Math.random().toString(36).slice(2, 8)}`;
  }
}

function createDeferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

let activeBridge: BridgeImpl | null = null;

export function createBridge(options: BridgeOptions = {}): Bridge {
  if (activeBridge !== null && !activeBridge.isClosed) {
    return activeBridge;
  }
  activeBridge = new BridgeImpl(options);
  return activeBridge;
}
