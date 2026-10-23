// GENERATED CODE - DO NOT EDIT
import { BridgeError, type Bridge, type CallContext, type CallOptions, type Disposable } from '../bridge';
import { EchoRequest, EchoResponse } from './demo';

export class NativeApiClient {
  constructor(private readonly bridge: Bridge) {}

  async echo(request: EchoRequest, options?: CallOptions): Promise<EchoResponse> {
    const bytes = await this.bridge.call(
      'demo.bridge.NativeApi/Echo',
      EchoRequest.encode(request).finish(),
      options,
    );
    try {
      return EchoResponse.decode(bytes);
    } catch (e) {
      throw new BridgeError('DATA_LOSS', 'decode EchoResponse failed', undefined, {
        cause: e,
      });
    }
  }
}

export interface NativeApiHandler {
  echo(request: EchoRequest, context: CallContext): EchoResponse | Promise<EchoResponse>;
}

export function registerNativeApi(
  bridge: Bridge,
  handler: NativeApiHandler,
): Disposable {
  const registrations: Disposable[] = [
    bridge.register('demo.bridge.NativeApi/Echo', async (payload, context) => {
      let request: EchoRequest;
      try {
        request = EchoRequest.decode(payload);
      } catch (e) {
        throw new BridgeError('INVALID_ARGUMENT', 'decode EchoRequest failed', undefined, {
          cause: e,
        });
      }
      const response = await handler.echo(request, context);
      return EchoResponse.encode(response).finish();
    }),
  ];
  return {
    dispose() {
      registrations.forEach((registration) => registration.dispose());
    },
  };
}
