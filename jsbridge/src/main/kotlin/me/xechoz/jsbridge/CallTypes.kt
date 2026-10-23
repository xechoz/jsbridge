package me.xechoz.jsbridge

import kotlin.time.Duration

data class CallOptions(
    /**
     * Timeout for the remote response. `null` means use [JsBridgeConfig.defaultTimeout].
     * Use [Duration.INFINITE] to disable the timeout.
     */
    val timeout: Duration? = null,
)

data class CallContext(
    val requestId: String,
    val generation: Long,
)

class InboundCall(
    val payload: ByteArray,
    val context: CallContext,
)

fun interface Registration : AutoCloseable {
    override fun close()
}
