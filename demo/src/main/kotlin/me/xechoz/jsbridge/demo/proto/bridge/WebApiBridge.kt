// GENERATED CODE - DO NOT EDIT
package me.xechoz.jsbridge.demo.proto.bridge

import java.io.IOException
import me.xechoz.jsbridge.BridgeErrorCode
import me.xechoz.jsbridge.BridgeException
import me.xechoz.jsbridge.CallContext
import me.xechoz.jsbridge.CallOptions
import me.xechoz.jsbridge.JsBridge
import me.xechoz.jsbridge.Registration
import me.xechoz.jsbridge.demo.proto.AddRequest
import me.xechoz.jsbridge.demo.proto.AddResponse

public class WebApiClient(private val bridge: JsBridge) {
  public suspend fun add(
    request: AddRequest,
    options: CallOptions = CallOptions(),
  ): AddResponse {
    val bytes = bridge.call(
      "demo.bridge.WebApi/Add",
      AddRequest.ADAPTER.encode(request),
      options,
    )
    return try {
      AddResponse.ADAPTER.decode(bytes)
    } catch (e: IOException) {
      throw BridgeException(
        BridgeErrorCode.DATA_LOSS,
        "decode AddResponse failed",
        cause = e,
      )
    }
  }
}

public interface WebApiHandler {
  public suspend fun add(
    request: AddRequest,
    context: CallContext,
  ): AddResponse
}

public fun JsBridge.registerWebApi(handler: WebApiHandler): Registration {
  val registrations: List<Registration> = listOf(
    register("demo.bridge.WebApi/Add") { call ->
      val request = try {
        AddRequest.ADAPTER.decode(call.payload)
      } catch (e: IOException) {
        throw BridgeException(
          BridgeErrorCode.INVALID_ARGUMENT,
          "decode AddRequest failed",
          cause = e,
        )
      }
      AddResponse.ADAPTER.encode(handler.add(request, call.context))
    },
  )
  return Registration { registrations.forEach { it.close() } }
}
