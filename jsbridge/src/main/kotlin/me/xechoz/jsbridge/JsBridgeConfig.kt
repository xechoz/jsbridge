package me.xechoz.jsbridge

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class JsBridgeConfig(
    val defaultTimeout: Duration = 30.seconds,
    val readyTimeout: Duration = 5.seconds,
    val maxMessageBytes: Int = 1 shl 20,
    val maxInFlight: Int = 64,
    val objectName: String = "__jsbridgeHost",
    val schemaHash: String = "",
    val handlerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val logger: BridgeLogger = BridgeLogger.None,
) {
    init {
        require(defaultTimeout > Duration.ZERO) { "defaultTimeout must be positive" }
        require(readyTimeout > Duration.ZERO) { "readyTimeout must be positive" }
        require(maxMessageBytes > 0) { "maxMessageBytes must be positive" }
        require(maxInFlight > 0) { "maxInFlight must be positive" }
        require(objectName.isNotBlank()) { "objectName must not be blank" }
    }
}
