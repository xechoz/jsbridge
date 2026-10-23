package me.xechoz.jsbridge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class BridgeEngine(
    private val sendFrame: (String) -> Unit,
    private val scope: CoroutineScope,
    private val config: JsBridgeConfig,
    startConsumer: Boolean = true,
) {
    private data class PendingCall(
        val generation: Long,
        val deferred: CompletableDeferred<ByteArray>,
    )

    private enum class SendResult {
        SENT,
        STALE,
        FAILED,
    }

    private val stateLock = Any()
    private val closed = AtomicBoolean(false)
    private val idCounter = AtomicLong()
    private val pending = ConcurrentHashMap<String, PendingCall>()
    private val handlers = ConcurrentHashMap<String, suspend (InboundCall) -> ByteArray>()
    private val inboundJobs = ConcurrentHashMap<String, Job>()
    private val incoming = Channel<String>(capacity = max(8, config.maxInFlight))

    private var generation = 0L
    private var ready = CompletableDeferred<Unit>()

    init {
        if (startConsumer) {
            scope.launch {
                for (frame in incoming) {
                    dispatch(frame)
                }
            }
        }
    }

    /**
     * Called from the WebView/JavaScriptInterface thread. Only enqueues, never blocks.
     */
    fun enqueue(frame: String) {
        if (closed.get()) return
        val result = incoming.trySend(frame)
        if (result.isFailure) {
            config.logger.warn("JsBridge incoming queue is full; frame dropped")
        }
    }

    fun register(method: String, handler: suspend (InboundCall) -> ByteArray): Registration {
        require(method.isNotBlank()) { "method must not be blank" }
        check(!closed.get()) { "JsBridge is closed" }
        val previous = handlers.putIfAbsent(method, handler)
        require(previous == null) { "Handler already registered for $method" }
        return Registration { handlers.remove(method, handler) }
    }

    suspend fun awaitReady(timeout: Duration? = null) {
        awaitReadyGeneration(timeout)
    }

    suspend fun call(
        method: String,
        payload: ByteArray,
        options: CallOptions = CallOptions(),
    ): ByteArray {
        ensureOpen()
        require(method.isNotBlank()) { "method must not be blank" }
        if (payload.size > config.maxMessageBytes) {
            throw BridgeException(
                BridgeErrorCode.MESSAGE_TOO_LARGE,
                "payload is ${payload.size} bytes; limit is ${config.maxMessageBytes}",
            )
        }

        val callGeneration = awaitReadyGeneration(config.readyTimeout)
        val timeout = options.timeout ?: config.defaultTimeout
        require(timeout > Duration.ZERO) { "timeout must be positive" }

        val id = nextId()
        val pendingCall = PendingCall(callGeneration, CompletableDeferred())
        synchronized(stateLock) {
            ensureOpen()
            if (generation != callGeneration) {
                throw BridgeException(BridgeErrorCode.PAGE_RELOADED, "page reloaded while starting call")
            }
            if (pending.size >= config.maxInFlight) {
                throw BridgeException(BridgeErrorCode.RESOURCE_EXHAUSTED, "too many in-flight calls")
            }
            pending[id] = pendingCall
        }

        var sent = false
        try {
            when (send(Envelope(EnvelopeKind.REQUEST, id = id, method = method, payload = payload), callGeneration)) {
                SendResult.SENT -> sent = true
                SendResult.STALE -> throw BridgeException(
                    BridgeErrorCode.PAGE_RELOADED,
                    "page reloaded while sending call",
                )
                SendResult.FAILED -> throw BridgeException(
                    BridgeErrorCode.UNAVAILABLE,
                    "failed to send request",
                )
            }

            return if (timeout == Duration.INFINITE) {
                pendingCall.deferred.await()
            } else {
                withTimeout(timeout) { pendingCall.deferred.await() }
            }
        } catch (e: TimeoutCancellationException) {
            if (sent) sendCancel(id, "timeout", callGeneration)
            throw BridgeException(
                BridgeErrorCode.DEADLINE_EXCEEDED,
                "call timed out after $timeout: $method",
                cause = e,
            )
        } catch (e: CancellationException) {
            if (sent) sendCancel(id, "cancelled", callGeneration)
            throw e
        } catch (e: BridgeException) {
            throw e
        } catch (e: Throwable) {
            throw BridgeException(
                BridgeErrorCode.UNAVAILABLE,
                "call failed: ${e.message}",
                cause = e,
            )
        } finally {
            pending.remove(id)
        }
    }

    fun onPageStarted() {
        if (closed.get()) return
        val oldReady: CompletableDeferred<Unit>
        val oldPending: List<PendingCall>
        synchronized(stateLock) {
            generation++
            oldReady = ready
            ready = CompletableDeferred()
            oldPending = pending.values.toList()
            pending.clear()
        }
        val error = BridgeException(BridgeErrorCode.PAGE_RELOADED, "page reloaded")
        oldReady.completeExceptionally(error)
        oldPending.forEach { it.deferred.completeExceptionally(error) }
        inboundJobs.values.forEach { it.cancel(CancellationException("page reloaded")) }
        inboundJobs.clear()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        incoming.close()
        val oldReady: CompletableDeferred<Unit>
        val oldPending: List<PendingCall>
        synchronized(stateLock) {
            oldReady = ready
            oldPending = pending.values.toList()
            pending.clear()
        }
        val error = BridgeException(BridgeErrorCode.BRIDGE_CLOSED, "bridge closed")
        oldReady.completeExceptionally(error)
        oldPending.forEach { it.deferred.completeExceptionally(error) }
        inboundJobs.values.forEach { it.cancel(CancellationException("bridge closed")) }
        inboundJobs.clear()
    }

    internal fun dispatch(frame: String) {
        if (closed.get()) return
        if (frame.length > config.maxMessageBytes * 2) {
            config.logger.warn("Dropping oversized frame (${frame.length} chars)")
            return
        }
        val envelope = try {
            EnvelopeCodec.decode(frame)
        } catch (e: Exception) {
            config.logger.warn("Dropping malformed frame", e)
            return
        }
        when (envelope.kind) {
            EnvelopeKind.HELLO -> handleHello(envelope)
            EnvelopeKind.HELLO_ACK -> handleHelloAck(envelope)
            EnvelopeKind.REQUEST -> handleRequest(envelope)
            EnvelopeKind.RESPONSE -> handleResponse(envelope)
            EnvelopeKind.CANCEL -> handleCancel(envelope)
        }
    }

    private suspend fun awaitReadyGeneration(timeout: Duration?): Long {
        val (deferred, startGeneration) = synchronized(stateLock) {
            ensureOpen()
            ready to generation
        }
        try {
            if (timeout == null || timeout == Duration.INFINITE) {
                deferred.await()
            } else {
                withTimeout(timeout) { deferred.await() }
            }
        } catch (e: TimeoutCancellationException) {
            throw BridgeException(BridgeErrorCode.UNAVAILABLE, "JS bridge is not ready", cause = e)
        }
        synchronized(stateLock) {
            ensureOpen()
            if (generation != startGeneration) {
                throw BridgeException(BridgeErrorCode.PAGE_RELOADED, "page reloaded while waiting for JS bridge")
            }
        }
        return startGeneration
    }

    private fun handleHello(envelope: Envelope) {
        checkSchema(envelope.schemaHash)
        synchronized(stateLock) {
            if (!ready.isCompleted) ready.complete(Unit)
        }
        send(Envelope(EnvelopeKind.HELLO_ACK, schemaHash = config.schemaHash), generation)
    }

    private fun handleHelloAck(envelope: Envelope) {
        checkSchema(envelope.schemaHash)
        synchronized(stateLock) {
            if (!ready.isCompleted) ready.complete(Unit)
        }
    }

    private fun checkSchema(remoteHash: String?) {
        val localHash = config.schemaHash
        if (!remoteHash.isNullOrEmpty() && localHash.isNotEmpty() && remoteHash != localHash) {
            config.logger.warn("Schema hash mismatch: local=$localHash remote=$remoteHash")
        }
    }

    private fun handleRequest(envelope: Envelope) {
        val id = envelope.id
        val method = envelope.method
        if (id.isNullOrBlank() || method.isNullOrBlank()) {
            config.logger.warn("Dropping request without id/method")
            return
        }

        val currentGeneration = synchronized(stateLock) { generation }
        val payload = envelope.payload ?: ByteArray(0)
        if (payload.size > config.maxMessageBytes) {
            sendError(id, BridgeErrorCode.MESSAGE_TOO_LARGE, "request payload too large", currentGeneration)
            return
        }

        val handler = handlers[method]
        if (handler == null) {
            sendError(
                id,
                BridgeErrorCode.UNIMPLEMENTED,
                "no handler registered for $method",
                currentGeneration,
            )
            return
        }
        if (inboundJobs.size >= config.maxInFlight) {
            sendError(
                id,
                BridgeErrorCode.RESOURCE_EXHAUSTED,
                "too many in-flight handlers",
                currentGeneration,
            )
            return
        }
        if (inboundJobs.containsKey(id)) {
            sendError(id, BridgeErrorCode.INVALID_ARGUMENT, "duplicate request id", currentGeneration)
            return
        }

        val inboundCall = InboundCall(payload, CallContext(id, currentGeneration))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext()[Job]
            try {
                val result = handler(inboundCall)
                if (currentCoroutineContext().isActive && isCurrent(currentGeneration)) {
                    sendResponse(id, result, currentGeneration)
                }
            } catch (e: CancellationException) {
                if (currentCoroutineContext().isActive) {
                    sendError(
                        id,
                        BridgeErrorCode.DEADLINE_EXCEEDED,
                        e.message ?: "handler cancelled",
                        currentGeneration,
                    )
                }
            } catch (e: BridgeException) {
                sendError(id, e.code, e.message, currentGeneration)
            } catch (e: Exception) {
                config.logger.error("Handler for $method failed", e)
                sendError(
                    id,
                    BridgeErrorCode.INTERNAL,
                    e.message ?: "handler failed",
                    currentGeneration,
                )
            } finally {
                if (self != null) inboundJobs.remove(id, self)
            }
        }

        val previous = inboundJobs.putIfAbsent(id, job)
        if (previous != null) {
            job.cancel()
            sendError(id, BridgeErrorCode.INVALID_ARGUMENT, "duplicate request id", currentGeneration)
        } else {
            job.start()
        }
    }

    private fun handleResponse(envelope: Envelope) {
        val id = envelope.id ?: return
        val pendingCall = pending.remove(id) ?: return
        val error = envelope.error
        if (error != null) {
            pendingCall.deferred.completeExceptionally(BridgeException(error.code, error.message))
            return
        }

        val payload = envelope.payload ?: ByteArray(0)
        if (payload.size > config.maxMessageBytes) {
            pendingCall.deferred.completeExceptionally(
                BridgeException(BridgeErrorCode.MESSAGE_TOO_LARGE, "response payload too large"),
            )
        } else {
            pendingCall.deferred.complete(payload)
        }
    }

    private fun handleCancel(envelope: Envelope) {
        val id = envelope.id ?: return
        pending.remove(id)?.deferred?.completeExceptionally(
            BridgeException(BridgeErrorCode.CANCELLED, envelope.reason ?: "cancelled"),
        )
        inboundJobs.remove(id)?.cancel(
            CancellationException(envelope.reason ?: "cancelled"),
        )
    }

    private fun sendResponse(id: String, payload: ByteArray, expectedGeneration: Long) {
        if (payload.size > config.maxMessageBytes) {
            sendError(
                id,
                BridgeErrorCode.MESSAGE_TOO_LARGE,
                "response payload too large",
                expectedGeneration,
            )
            return
        }
        send(Envelope(EnvelopeKind.RESPONSE, id = id, payload = payload), expectedGeneration)
    }

    private fun sendError(
        id: String,
        code: BridgeErrorCode,
        message: String,
        expectedGeneration: Long,
    ) {
        send(
            Envelope(
                EnvelopeKind.RESPONSE,
                id = id,
                error = EnvelopeError(code, message),
            ),
            expectedGeneration,
        )
    }

    private fun sendCancel(id: String, reason: String, expectedGeneration: Long) {
        send(Envelope(EnvelopeKind.CANCEL, id = id, reason = reason), expectedGeneration)
    }

    private fun send(envelope: Envelope, expectedGeneration: Long): SendResult {
        if (closed.get()) return SendResult.STALE
        val stale = synchronized(stateLock) { generation != expectedGeneration }
        if (stale) return SendResult.STALE
        return try {
            sendFrame(EnvelopeCodec.encode(envelope))
            SendResult.SENT
        } catch (e: Exception) {
            config.logger.error("Failed to send ${envelope.kind.wire} frame", e)
            SendResult.FAILED
        }
    }

    private fun isCurrent(expectedGeneration: Long): Boolean {
        if (closed.get()) return false
        return synchronized(stateLock) { generation == expectedGeneration }
    }

    private fun nextId(): String =
        "a-${idCounter.incrementAndGet()}-${Random.nextInt(0, Int.MAX_VALUE).toString(16)}"

    private fun ensureOpen() {
        if (closed.get()) throw BridgeException(BridgeErrorCode.BRIDGE_CLOSED, "bridge closed")
    }
}
