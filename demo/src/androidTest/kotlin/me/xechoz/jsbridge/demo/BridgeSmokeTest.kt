package me.xechoz.jsbridge.demo

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.xechoz.jsbridge.JsBridge
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BridgeSmokeTest {
    @Test
    fun bidirectionalCallsWork() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var bridge: JsBridge? = null
            scenario.onActivity { activity ->
                bridge = activity.bridge
            }
            val currentBridge = requireNotNull(bridge)

            runBlocking {
                withTimeout(20_000) {
                    currentBridge.awaitReady()
                }
                assertEquals("js:native", currentBridge.callText("js.echo", "native"))
            }

            evaluate(scenario, "window.__bridgeTest && window.__bridgeTest.runSelfTest()")
            waitForTrue(scenario, "window.__bridgeTest && window.__bridgeTest.done")

            val rawSummary = requireNotNull(
                evaluate(scenario, "window.__bridgeTest && window.__bridgeTest.summary"),
            )
            val summaryJson = JSONTokener(rawSummary).nextValue() as String
            val summary = JSONObject(summaryJson)
            assertEquals("ok", summary.getString("echo"))
            assertEquals("ok", summary.getString("reverse"))
            assertEquals("ok", summary.getString("typedEcho"))
            assertEquals("ok", summary.getString("typedAdd"))
            assertEquals("ok", summary.getString("timeout"))
            assertEquals("ok", summary.getString("cancel"))
            assertEquals("ok", summary.getString("fail"))
        }
    }

    private fun evaluate(scenario: ActivityScenario<MainActivity>, script: String): String? {
        var result: String? = null
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            evaluate(activity.webView, script) { value ->
                result = value
                latch.countDown()
            }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) {
            fail("evaluateJavascript timed out: $script")
        }
        return result
    }

    private fun evaluate(webView: WebView, script: String, onResult: (String?) -> Unit) {
        webView.evaluateJavascript(script, onResult)
    }

    private fun waitForTrue(scenario: ActivityScenario<MainActivity>, script: String) {
        repeat(100) {
            if (evaluate(scenario, script) == "true") return
            Thread.sleep(100)
        }
        fail("condition was not true: $script")
    }
}
