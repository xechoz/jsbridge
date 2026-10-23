package me.xechoz.jsbridge

enum class BridgeErrorCode {
    CANCELLED,
    INVALID_ARGUMENT,
    DEADLINE_EXCEEDED,
    RESOURCE_EXHAUSTED,
    UNIMPLEMENTED,
    INTERNAL,
    UNAVAILABLE,
    PAGE_RELOADED,
    BRIDGE_CLOSED,
    MESSAGE_TOO_LARGE,
    DATA_LOSS,
}

class BridgeException(
    val code: BridgeErrorCode,
    override val message: String,
    val details: ByteArray? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
