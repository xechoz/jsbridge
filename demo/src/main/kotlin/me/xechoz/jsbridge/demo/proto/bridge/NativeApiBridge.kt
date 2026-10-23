// GENERATED CODE - DO NOT EDIT
package me.xechoz.jsbridge.demo.proto.bridge

import java.io.IOException
import me.xechoz.jsbridge.BridgeErrorCode
import me.xechoz.jsbridge.BridgeException
import me.xechoz.jsbridge.CallContext
import me.xechoz.jsbridge.CallOptions
import me.xechoz.jsbridge.JsBridge
import me.xechoz.jsbridge.Registration
import me.xechoz.jsbridge.demo.proto.EchoRequest
import me.xechoz.jsbridge.demo.proto.EchoResponse

public class NativeApiClient(private val bridge: JsBridge) {
  public suspend fun echo(
    request: EchoRequest,
    options: CallOptions = CallOptions(),
  ): EchoResponse {
    val bytes = bridge.call(
      "demo.bridge.NativeApi/Echo",
      EchoRequest.ADAPTER.encode(request),
      options,
    )
    return try {
      EchoResponse.ADAPTER.decode(bytes)
    } catch (e: IOException) {
      throw BridgeException(
        BridgeErrorCode.DATA_LOSS,
        "decode EchoResponse failed",
        cause = e,
      )
    }
  }
}

public interface NativeApiHandler {
  public suspend fun echo(
    request: EchoRequest,
    context: CallContext,
  ): EchoResponse
}

public fun JsBridge.registerNativeApi(handler: NativeApiHandler): Registration {
  val registrations: List<Registration> = listOf(
    register("demo.bridge.NativeApi/Echo") { call ->
      val request = try {
        EchoRequest.ADAPTER.decode(call.payload)
      } catch (e: IOException) {
        throw BridgeException(
          BridgeErrorCode.INVALID_ARGUMENT,
          "decode EchoRequest failed",
          cause = e,
        )
      }
      EchoResponse.ADAPTER.encode(handler.echo(request, call.context))
    },
  )
  return Registration { registrations.forEach { it.close() } }
}
