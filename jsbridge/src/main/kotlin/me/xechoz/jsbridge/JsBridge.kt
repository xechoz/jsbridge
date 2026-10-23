package me.xechoz.jsbridge

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject

class JsBridge private constructor(
    private val webView: WebView,
    private val config: JsBridgeConfig,
    private val engine: BridgeEngine,
    private val bridgeJob: Job,
    private val mainHandler: Handler,
) {
    private val closed = AtomicBoolean(false)

    companion object {
        /**
         * Attaches a bridge to [webView]. Must be called on the main thread and before
         * the first page is loaded.
         */
        fun attach(
            webView: WebView,
            scope: CoroutineScope,
            config: JsBridgeConfig = JsBridgeConfig(),
        ): JsBridge {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "JsBridge.attach must be called on the main thread"
            }

            val logger = if (config.logger === BridgeLogger.None) {
                AndroidBridgeLogger
            } else {
                config.logger
            }
            val effectiveConfig = config.copy(logger = logger)
            val mainHandler = Handler(Looper.getMainLooper())
            val bridgeJob = SupervisorJob(scope.coroutineContext[Job])
            val engineScope = CoroutineScope(
                scope.coroutineContext + bridgeJob + effectiveConfig.handlerDispatcher,
            )

            val sendFrame: (String) -> Unit = { frame ->
                mainHandler.post {
                    try {
                        webView.evaluateJavascript(
                            "window.__jsbridge && window.__jsbridge.receive(${JSONObject.quote(frame)})",
                            null,
                        )
                    } catch (e: Exception) {
                        logger.error("evaluateJavascript failed", e)
                    }
                }
            }

            val engine = BridgeEngine(sendFrame, engineScope, effectiveConfig)
            bridgeJob.invokeOnCompletion { engine.close() }
            val bridge = JsBridge(webView, effectiveConfig, engine, bridgeJob, mainHandler)
            webView.addJavascriptInterface(Host(engine), effectiveConfig.objectName)
            return bridge
        }
    }

    suspend fun call(
        method: String,
        payload: ByteArray,
        options: CallOptions = CallOptions(),
    ): ByteArray = engine.call(method, payload, options)

    suspend fun callText(
        method: String,
        text: String,
        options: CallOptions = CallOptions(),
    ): String {
        val response = call(method, text.toByteArray(Charsets.UTF_8), options)
        return String(response, Charsets.UTF_8)
    }

    suspend fun awaitReady(timeout: Duration? = null) {
        engine.awaitReady(timeout)
    }

    fun register(
        method: String,
        handler: suspend (InboundCall) -> ByteArray,
    ): Registration = engine.register(method, handler)

    fun registerText(
        method: String,
        handler: suspend (String, CallContext) -> String,
    ): Registration = register(method) { call ->
        val text = String(call.payload, Charsets.UTF_8)
        handler(text, call.context).toByteArray(Charsets.UTF_8)
    }

    /**
     * Must be called from the app's WebViewClient.onPageStarted so that calls from
     * the previous page are failed and old handlers are cancelled.
     */
    fun onPageStarted() {
        engine.onPageStarted()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        engine.close()
        mainHandler.post {
            try {
                webView.removeJavascriptInterface(config.objectName)
            } catch (e: Exception) {
                config.logger.warn("removeJavascriptInterface failed", e)
            }
        }
        bridgeJob.cancel()
    }

    private class Host(
        private val engine: BridgeEngine,
    ) {
        @JavascriptInterface
        fun postMessage(frame: String) {
            engine.enqueue(frame)
        }
    }

    private object AndroidBridgeLogger : BridgeLogger {
        private const val TAG = "JsBridge"

        override fun debug(message: String) {
            Log.d(TAG, message)
        }

        override fun warn(message: String, throwable: Throwable?) {
            Log.w(TAG, message, throwable)
        }

        override fun error(message: String, throwable: Throwable?) {
            Log.e(TAG, message, throwable)
        }
    }
}
