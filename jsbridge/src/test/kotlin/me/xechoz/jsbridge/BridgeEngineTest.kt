package me.xechoz.jsbridge

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeEngineTest {
    private lateinit var sent: MutableList<String>
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var engine: BridgeEngine

    @Before
    fun setUp() {
        sent = mutableListOf()
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        scope = CoroutineScope(dispatcher + SupervisorJob())
        engine = BridgeEngine(
            sendFrame = { sent += it },
            scope = scope,
            config = JsBridgeConfig(
                defaultTimeout = 1.seconds,
                readyTimeout = 1.seconds,
            ),
            startConsumer = false,
        )
    }

    @After
    fun tearDown() {
        engine.close()
        scope.cancel()
    }

    @Test
    fun `outbound call resolves after hello and response`() = runTest(dispatcher) {
        hello()

        val result = async { engine.call("native.echo", "hello".toByteArray()) }
        runCurrent()

        val request = JSONObject(sent.last())
        assertEquals("request", request.getString("kind"))
        assertEquals("native.echo", request.getString("method"))
        assertEquals("hello", String(Base64Codec.decode(request.getString("payload"))))

        engine.dispatch(responseFrame(request.getString("id"), "world".toByteArray()))
        runCurrent()

        assertEquals("world", String(result.await()))
    }

    @Test
    fun `outbound call waits for hello`() = runTest(dispatcher) {
        val result = async { engine.call("native.echo", "hello".toByteArray()) }
        runCurrent()
        assertTrue(sent.isEmpty())

        hello()
        runCurrent()

        val request = JSONObject(sent.last())
        assertEquals("request", request.getString("kind"))
        engine.dispatch(responseFrame(request.getString("id"), "world".toByteArray()))
        runCurrent()

        assertEquals("world", String(result.await()))
    }

    @Test
    fun `inbound request invokes handler and responds`() = runTest(dispatcher) {
        engine.register("native.echo") { call -> call.payload }
        engine.dispatch(requestFrame("w-1", "native.echo", "ping".toByteArray()))
        advanceUntilIdle()

        val response = JSONObject(sent.last())
        assertEquals("response", response.getString("kind"))
        assertEquals("w-1", response.getString("id"))
        assertEquals("ping", String(Base64Codec.decode(response.getString("payload"))))
    }

    @Test
    fun `unknown method returns UNIMPLEMENTED`() = runTest(dispatcher) {
        engine.dispatch(requestFrame("w-1", "missing"))
        advanceUntilIdle()

        val response = JSONObject(sent.last())
        assertEquals("response", response.getString("kind"))
        assertEquals("UNIMPLEMENTED", response.getJSONObject("error").getString("code"))
    }

    @Test
    fun `handler BridgeException is propagated`() = runTest(dispatcher) {
        engine.register("native.fail") {
            throw BridgeException(BridgeErrorCode.INVALID_ARGUMENT, "bad input")
        }
        engine.dispatch(requestFrame("w-1", "native.fail"))
        advanceUntilIdle()

        val error = JSONObject(sent.last()).getJSONObject("error")
        assertEquals("INVALID_ARGUMENT", error.getString("code"))
        assertEquals("bad input", error.getString("message"))
    }

    @Test
    fun `handler generic exception becomes INTERNAL`() = runTest(dispatcher) {
        engine.register("native.fail") {
            error("boom")
        }
        engine.dispatch(requestFrame("w-1", "native.fail"))
        advanceUntilIdle()

        val error = JSONObject(sent.last()).getJSONObject("error")
        assertEquals("INTERNAL", error.getString("code"))
    }

    @Test
    fun `outbound timeout sends cancel`() = runTest(dispatcher) {
        hello()
        val (_, failure) = startCallExpectingFailure {
            engine.call("native.slow", ByteArray(0), CallOptions(100.milliseconds))
        }
        runCurrent()
        assertTrue(sent.last().contains("\"kind\":\"request\""))

        advanceTimeBy(200)
        runCurrent()

        val error = failure.await()
        assertEquals(BridgeErrorCode.DEADLINE_EXCEEDED, error.code)
        val cancel = JSONObject(sent.last())
        assertEquals("cancel", cancel.getString("kind"))
    }

    @Test
    fun `inbound cancel cancels handler`() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        engine.register("native.slow") {
            started.complete(Unit)
            try {
                awaitCancellation()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled.set(true)
                throw e
            }
        }

        engine.dispatch(requestFrame("w-1", "native.slow"))
        advanceUntilIdle()
        assertTrue(started.isCompleted)

        engine.dispatch(cancelFrame("w-1"))
        advanceUntilIdle()
        assertTrue(cancelled.get())
    }

    @Test
    fun `page reset fails pending calls`() = runTest(dispatcher) {
        hello()
        val (_, failure) = startCallExpectingFailure {
            engine.call("native.echo", "hello".toByteArray())
        }
        runCurrent()

        engine.onPageStarted()
        runCurrent()

        val error = failure.await()
        assertEquals(BridgeErrorCode.PAGE_RELOADED, error.code)
    }

    @Test
    fun `close fails pending calls`() = runTest(dispatcher) {
        hello()
        val (_, failure) = startCallExpectingFailure {
            engine.call("native.echo", "hello".toByteArray())
        }
        runCurrent()

        engine.close()
        runCurrent()

        val error = failure.await()
        assertEquals(BridgeErrorCode.BRIDGE_CLOSED, error.code)
    }

    @Test
    fun `late response after timeout is ignored`() = runTest(dispatcher) {
        hello()
        val (_, failure) = startCallExpectingFailure {
            engine.call("native.echo", ByteArray(0), CallOptions(50.milliseconds))
        }
        runCurrent()
        val request = JSONObject(sent.last())
        advanceTimeBy(100)
        runCurrent()

        failure.await()
        engine.dispatch(responseFrame(request.getString("id"), "late".toByteArray()))
        runCurrent()
        assertFalse(sent.last().contains("\"kind\":\"response\""))
    }

    @Test
    fun `oversized payload is rejected before sending`() = runTest(dispatcher) {
        hello()
        val payload = ByteArray(2 * 1024 * 1024)
        val error = assertFailsWithBridgeException {
            engine.call("native.echo", payload)
        }
        assertEquals(BridgeErrorCode.MESSAGE_TOO_LARGE, error.code)
    }

    @Test
    fun `malformed frame is ignored`() = runTest(dispatcher) {
        engine.dispatch("not json")
        advanceUntilIdle()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `base64 round trip`() {
        val values = listOf(
            ByteArray(0),
            "a".toByteArray(),
            "ab".toByteArray(),
            "abc".toByteArray(),
            "hello world".toByteArray(),
            ByteArray(256) { it.toByte() },
        )
        values.forEach { value ->
            assertArrayEquals(value, Base64Codec.decode(Base64Codec.encode(value)))
        }
    }

    @Test
    fun `decodes hardcoded wire fixture`() {
        val frame = """{"v":1,"kind":"request","id":"w-1","method":"native.echo","payload":"aGVsbG8="}"""
        val envelope = EnvelopeCodec.decode(frame)
        assertEquals(EnvelopeKind.REQUEST, envelope.kind)
        assertEquals("w-1", envelope.id)
        assertEquals("native.echo", envelope.method)
        assertEquals("hello", String(envelope.payload!!))
    }

    private fun hello() {
        engine.dispatch(EnvelopeCodec.encode(Envelope(EnvelopeKind.HELLO)))
    }

    private fun requestFrame(
        id: String,
        method: String,
        payload: ByteArray = ByteArray(0),
    ): String = EnvelopeCodec.encode(
        Envelope(EnvelopeKind.REQUEST, id = id, method = method, payload = payload),
    )

    private fun responseFrame(id: String, payload: ByteArray): String = EnvelopeCodec.encode(
        Envelope(EnvelopeKind.RESPONSE, id = id, payload = payload),
    )

    private fun cancelFrame(id: String): String = EnvelopeCodec.encode(
        Envelope(EnvelopeKind.CANCEL, id = id, reason = "test"),
    )

    private fun CoroutineScope.startCallExpectingFailure(
        block: suspend () -> ByteArray,
    ): Pair<Job, CompletableDeferred<BridgeException>> {
        val failure = CompletableDeferred<BridgeException>()
        val job = launch {
            try {
                block()
                failure.completeExceptionally(AssertionError("Expected BridgeException"))
            } catch (e: BridgeException) {
                failure.complete(e)
            }
        }
        return job to failure
    }

    private suspend fun assertFailsWithBridgeException(block: suspend () -> Unit): BridgeException {
        try {
            block()
        } catch (e: BridgeException) {
            return e
        }
        throw AssertionError("Expected BridgeException")
    }
}
