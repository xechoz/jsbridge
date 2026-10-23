package me.xechoz.jsbridge.demo

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import me.xechoz.jsbridge.BridgeErrorCode
import me.xechoz.jsbridge.BridgeException
import me.xechoz.jsbridge.CallContext
import me.xechoz.jsbridge.JsBridge
import me.xechoz.jsbridge.JsBridgeConfig
import me.xechoz.jsbridge.demo.proto.AddRequest
import me.xechoz.jsbridge.demo.proto.EchoRequest
import me.xechoz.jsbridge.demo.proto.EchoResponse
import me.xechoz.jsbridge.demo.proto.bridge.BridgeSchema
import me.xechoz.jsbridge.demo.proto.bridge.NativeApiHandler
import me.xechoz.jsbridge.demo.proto.bridge.WebApiClient
import me.xechoz.jsbridge.demo.proto.bridge.registerNativeApi

class MainActivity : Activity() {
    internal lateinit var webView: WebView
    internal lateinit var bridge: JsBridge

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
        }
        setContentView(webView)

        // Must be attached before loadUrl.
        bridge = JsBridge.attach(
            webView,
            scope,
            JsBridgeConfig(schemaHash = BridgeSchema.HASH),
        )
        registerHandlers()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                bridge.onPageStarted()
            }
        }
        webView.loadUrl("file:///android_asset/web/index.html")
    }

    private fun registerHandlers() {
        bridge.register("native.echo") { call -> call.payload }

        bridge.registerText("native.reverseEcho") { text, _ ->
            bridge.callText("js.echo", text)
        }

        bridge.registerText("native.fail") { _, _ ->
            throw BridgeException(BridgeErrorCode.INVALID_ARGUMENT, "intentional failure")
        }

        bridge.register("native.delayed") { call ->
            delay(5_000)
            call.payload
        }

        // Typed API implemented on Android and called from JS.
        bridge.registerNativeApi(
            object : NativeApiHandler {
                override suspend fun echo(
                    request: EchoRequest,
                    context: CallContext,
                ): EchoResponse = EchoResponse(text = "android:${request.text}")
            },
        )

        // Typed API implemented on JS and called from Android.
        val webApi = WebApiClient(bridge)
        bridge.registerText("native.typedAdd") { _, _ ->
            val response = webApi.add(AddRequest(a = 2, b = 3))
            response.sum.toString()
        }
    }

    override fun onDestroy() {
        bridge.close()
        scope.cancel()
        webView.destroy()
        super.onDestroy()
    }
}
