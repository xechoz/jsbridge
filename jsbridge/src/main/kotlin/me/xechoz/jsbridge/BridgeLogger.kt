package me.xechoz.jsbridge

interface BridgeLogger {
    fun debug(message: String) {}

    fun warn(message: String, throwable: Throwable? = null) {}

    fun error(message: String, throwable: Throwable? = null) {}

    object None : BridgeLogger
}
