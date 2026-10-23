export const PROTOCOL_VERSION = 1 as const;

export type BridgeErrorCode =
  | 'CANCELLED'
  | 'INVALID_ARGUMENT'
  | 'DEADLINE_EXCEEDED'
  | 'RESOURCE_EXHAUSTED'
  | 'UNIMPLEMENTED'
  | 'INTERNAL'
  | 'UNAVAILABLE'
  | 'PAGE_RELOADED'
  | 'BRIDGE_CLOSED'
  | 'MESSAGE_TOO_LARGE'
  | 'DATA_LOSS';

const ERROR_CODES: ReadonlySet<string> = new Set<BridgeErrorCode>([
  'CANCELLED',
  'INVALID_ARGUMENT',
  'DEADLINE_EXCEEDED',
  'RESOURCE_EXHAUSTED',
  'UNIMPLEMENTED',
  'INTERNAL',
  'UNAVAILABLE',
  'PAGE_RELOADED',
  'BRIDGE_CLOSED',
  'MESSAGE_TOO_LARGE',
  'DATA_LOSS',
]);

export class BridgeError extends Error {
  constructor(
    readonly code: BridgeErrorCode,
    message: string,
    readonly details?: Uint8Array,
    options?: { cause?: unknown },
  ) {
    super(message, options);
    this.name = 'BridgeError';
  }
}

export type EnvelopeKind = 'hello' | 'helloAck' | 'request' | 'response' | 'cancel';

const ENVELOPE_KINDS: ReadonlySet<string> = new Set<EnvelopeKind>([
  'hello',
  'helloAck',
  'request',
  'response',
  'cancel',
]);

export interface EnvelopeError {
  code: BridgeErrorCode;
  message: string;
}

export interface Envelope {
  v: typeof PROTOCOL_VERSION;
  kind: EnvelopeKind;
  id?: string;
  method?: string;
  payload?: string;
  error?: EnvelopeError;
  schemaHash?: string;
  reason?: string;
}

export function encodeEnvelope(envelope: Envelope): string {
  return JSON.stringify(envelope);
}

export function decodeEnvelope(frame: string): Envelope {
  const parsed: unknown = JSON.parse(frame);
  if (!isRecord(parsed)) {
    throw new Error('Envelope is not an object');
  }
  if (parsed.v !== PROTOCOL_VERSION) {
    throw new Error(`Unsupported protocol version: ${String(parsed.v)}`);
  }
  const kind = parsed.kind;
  if (typeof kind !== 'string' || !ENVELOPE_KINDS.has(kind)) {
    throw new Error(`Unknown envelope kind: ${String(kind)}`);
  }

  const envelope: Envelope = {
    v: PROTOCOL_VERSION,
    kind: kind as EnvelopeKind,
  };

  if (typeof parsed.id === 'string') envelope.id = parsed.id;
  if (typeof parsed.method === 'string') envelope.method = parsed.method;
  if (typeof parsed.payload === 'string') envelope.payload = parsed.payload;
  if (typeof parsed.schemaHash === 'string') envelope.schemaHash = parsed.schemaHash;
  if (typeof parsed.reason === 'string') envelope.reason = parsed.reason;

  if (isRecord(parsed.error)) {
    const rawCode = parsed.error.code;
    const code = typeof rawCode === 'string' && ERROR_CODES.has(rawCode)
      ? (rawCode as BridgeErrorCode)
      : 'INTERNAL';
    const message = typeof parsed.error.message === 'string' ? parsed.error.message : '';
    envelope.error = { code, message };
  }

  return envelope;
}

export function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  const chunkSize = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    const end = Math.min(offset + chunkSize, bytes.length);
    for (let index = offset; index < end; index += 1) {
      binary += String.fromCharCode(bytes[index]!);
    }
  }
  return btoa(binary);
}

export function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

export function utf8Encode(value: string): Uint8Array {
  return new TextEncoder().encode(value);
}

export function utf8Decode(value: Uint8Array): string {
  return new TextDecoder().decode(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
