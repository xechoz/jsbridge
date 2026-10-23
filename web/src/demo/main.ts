import {
  BridgeError,
  type Bridge,
  createBridge,
  utf8Decode,
} from '../bridge';
import { NativeApiClient } from '../generated/NativeApi.bridge';
import { SCHEMA_HASH } from '../generated/schema';
import { registerWebApi } from '../generated/WebApi.bridge';

interface BridgeTestHooks {
  ready: boolean;
  done: boolean;
  summary: string;
  runSelfTest(): Promise<void>;
}

declare global {
  interface Window {
    __bridgeTest?: BridgeTestHooks;
  }
}

const logElement = document.getElementById('log') as HTMLDivElement;
const statusElement = document.getElementById('status') as HTMLParagraphElement;

function log(message: string, kind: 'info' | 'ok' | 'error' = 'info'): void {
  const line = document.createElement('div');
  line.textContent = `[${new Date().toLocaleTimeString()}] ${message}`;
  if (kind === 'ok') line.className = 'ok';
  if (kind === 'error') line.className = 'err';
  logElement.appendChild(line);
  logElement.scrollTop = logElement.scrollHeight;
}

function describeError(error: unknown): string {
  if (error instanceof BridgeError) return `${error.code}: ${error.message}`;
  if (error instanceof Error) return `${error.name}: ${error.message}`;
  return String(error);
}

let bridge: Bridge;
try {
  bridge = createBridge({ schemaHash: SCHEMA_HASH });
} catch (error) {
  statusElement.textContent = 'bridge unavailable';
  log(`bridge unavailable: ${describeError(error)}`, 'error');
  throw error;
}

const nativeApi = new NativeApiClient(bridge);

registerWebApi(bridge, {
  async add(request) {
    return { sum: request.a + request.b };
  },
});

const hooks: BridgeTestHooks = {
  ready: false,
  done: false,
  summary: '',
  async runSelfTest() {
    const results: Record<string, string> = {};
    hooks.done = false;
    hooks.summary = '';

    try {
      const echoed = await bridge.callText('native.echo', 'self');
      results.echo = echoed === 'self' ? 'ok' : `unexpected:${echoed}`;
    } catch (error) {
      results.echo = `error:${describeError(error)}`;
    }

    try {
      const reversed = await bridge.callText('native.reverseEcho', 'self');
      results.reverse = reversed === 'js:self' ? 'ok' : `unexpected:${reversed}`;
    } catch (error) {
      results.reverse = `error:${describeError(error)}`;
    }

    try {
      const typed = await nativeApi.echo({ text: 'self' });
      results.typedEcho =
        typed.text === 'android:self' ? 'ok' : `unexpected:${typed.text}`;
    } catch (error) {
      results.typedEcho = `error:${describeError(error)}`;
    }

    try {
      const sum = await bridge.callText('native.typedAdd', '');
      results.typedAdd = sum === '5' ? 'ok' : `unexpected:${sum}`;
    } catch (error) {
      results.typedAdd = `error:${describeError(error)}`;
    }

    try {
      await bridge.callText('native.delayed', '', { timeoutMs: 200 });
      results.timeout = 'no-error';
    } catch (error) {
      results.timeout =
        error instanceof BridgeError && error.code === 'DEADLINE_EXCEEDED'
          ? 'ok'
          : `error:${describeError(error)}`;
    }

    const controller = new AbortController();
    const abortTimer = setTimeout(() => controller.abort(), 100);
    try {
      await bridge.callText('native.delayed', '', { signal: controller.signal });
      results.cancel = 'no-error';
    } catch (error) {
      results.cancel =
        error instanceof BridgeError && error.code === 'CANCELLED'
          ? 'ok'
          : `error:${describeError(error)}`;
    } finally {
      clearTimeout(abortTimer);
    }

    try {
      await bridge.callText('native.fail', '');
      results.fail = 'no-error';
    } catch (error) {
      results.fail =
        error instanceof BridgeError && error.code === 'INVALID_ARGUMENT'
          ? 'ok'
          : `error:${describeError(error)}`;
    }

    const passed = Object.values(results).every((value) => value === 'ok');
    hooks.summary = JSON.stringify(results);
    hooks.done = true;
    log(`self test ${passed ? 'passed' : 'failed'}: ${hooks.summary}`, passed ? 'ok' : 'error');
  },
};

window.__bridgeTest = hooks;

bridge.registerText('js.echo', async (text) => `js:${text}`);

bridge.register('js.delayed', async (payload, context) => {
  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(resolve, 5_000);
    const onAbort = () => {
      clearTimeout(timer);
      reject(new BridgeError('CANCELLED', 'handler aborted'));
    };
    if (context.signal.aborted) {
      onAbort();
    } else {
      context.signal.addEventListener('abort', onAbort, { once: true });
    }
  });
  return payload;
});

bridge.register('js.bytes', async (payload) => {
  const text = utf8Decode(payload);
  return new TextEncoder().encode(`bytes:${text}`);
});

bridge.ready.then(
  () => {
    hooks.ready = true;
    statusElement.textContent = 'bridge ready';
    log('bridge ready', 'ok');
  },
  (error) => {
    statusElement.textContent = 'handshake failed';
    log(`handshake failed: ${describeError(error)}`, 'error');
  },
);

async function run(label: string, action: () => Promise<string>): Promise<void> {
  log(`${label} → …`);
  try {
    const result = await action();
    log(`${label} ← ${result}`, 'ok');
  } catch (error) {
    log(`${label} ✕ ${describeError(error)}`, 'error');
  }
}

document.getElementById('echo')?.addEventListener('click', () => {
  void run('native.echo', () => bridge.callText('native.echo', 'hello'));
});

document.getElementById('reverse')?.addEventListener('click', () => {
  void run('native.reverseEcho', () => bridge.callText('native.reverseEcho', 'hello'));
});

document.getElementById('timeout')?.addEventListener('click', () => {
  void run('timeout', () => bridge.callText('native.delayed', '', { timeoutMs: 300 }));
});

document.getElementById('cancel')?.addEventListener('click', () => {
  const controller = new AbortController();
  setTimeout(() => controller.abort(), 100);
  void run('cancel', () =>
    bridge.callText('native.delayed', '', { signal: controller.signal }),
  );
});

document.getElementById('fail')?.addEventListener('click', () => {
  void run('native.fail', () => bridge.callText('native.fail', ''));
});

document.getElementById('selftest')?.addEventListener('click', () => {
  void hooks.runSelfTest();
});

document.getElementById('typed-echo')?.addEventListener('click', () => {
  void run('NativeApi.echo', async () => (await nativeApi.echo({ text: 'typed' })).text);
});

document.getElementById('typed-add')?.addEventListener('click', () => {
  void run('native.typedAdd', () => bridge.callText('native.typedAdd', ''));
});

document.getElementById('reload')?.addEventListener('click', () => {
  location.reload();
});
