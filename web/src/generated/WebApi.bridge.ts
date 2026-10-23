// GENERATED CODE - DO NOT EDIT
import { BridgeError, type Bridge, type CallContext, type CallOptions, type Disposable } from '../bridge';
import { AddRequest, AddResponse } from './demo';

export class WebApiClient {
  constructor(private readonly bridge: Bridge) {}

  async add(request: AddRequest, options?: CallOptions): Promise<AddResponse> {
    const bytes = await this.bridge.call(
      'demo.bridge.WebApi/Add',
      AddRequest.encode(request).finish(),
      options,
    );
    try {
      return AddResponse.decode(bytes);
    } catch (e) {
      throw new BridgeError('DATA_LOSS', 'decode AddResponse failed', undefined, {
        cause: e,
      });
    }
  }
}

export interface WebApiHandler {
  add(request: AddRequest, context: CallContext): AddResponse | Promise<AddResponse>;
}

export function registerWebApi(
  bridge: Bridge,
  handler: WebApiHandler,
): Disposable {
  const registrations: Disposable[] = [
    bridge.register('demo.bridge.WebApi/Add', async (payload, context) => {
      let request: AddRequest;
      try {
        request = AddRequest.decode(payload);
      } catch (e) {
        throw new BridgeError('INVALID_ARGUMENT', 'decode AddRequest failed', undefined, {
          cause: e,
        });
      }
      const response = await handler.add(request, context);
      return AddResponse.encode(response).finish();
    }),
  ];
  return {
    dispose() {
      registrations.forEach((registration) => registration.dispose());
    },
  };
}
