package me.xechoz.jsbridge

import org.json.JSONObject

internal enum class EnvelopeKind(val wire: String) {
    HELLO("hello"),
    HELLO_ACK("helloAck"),
    REQUEST("request"),
    RESPONSE("response"),
    CANCEL("cancel"),
    ;

    companion object {
        fun fromWire(value: String): EnvelopeKind? = entries.firstOrNull { it.wire == value }
    }
}

internal data class EnvelopeError(
    val code: BridgeErrorCode,
    val message: String,
)

internal data class Envelope(
    val kind: EnvelopeKind,
    val id: String? = null,
    val method: String? = null,
    val payload: ByteArray? = null,
    val error: EnvelopeError? = null,
    val schemaHash: String? = null,
    val reason: String? = null,
)

internal object EnvelopeCodec {
    const val VERSION = 1

    fun encode(envelope: Envelope): String {
        val json = JSONObject()
        json.put("v", VERSION)
        json.put("kind", envelope.kind.wire)
        envelope.id?.let { json.put("id", it) }
        envelope.method?.let { json.put("method", it) }
        envelope.payload?.let { json.put("payload", Base64Codec.encode(it)) }
        envelope.error?.let { error ->
            json.put(
                "error",
                JSONObject()
                    .put("code", error.code.name)
                    .put("message", error.message),
            )
        }
        envelope.schemaHash?.let { json.put("schemaHash", it) }
        envelope.reason?.let { json.put("reason", it) }
        return json.toString()
    }

    fun decode(frame: String): Envelope {
        val json = JSONObject(frame)
        val version = json.optInt("v", -1)
        require(version == VERSION) { "Unsupported protocol version: $version" }

        val kindValue = json.optString("kind")
        val kind = requireNotNull(EnvelopeKind.fromWire(kindValue)) {
            "Unknown envelope kind: $kindValue"
        }

        val payload = if (json.has("payload") && !json.isNull("payload")) {
            Base64Codec.decode(json.getString("payload"))
        } else {
            null
        }

        val error = if (json.has("error") && !json.isNull("error")) {
            val errorJson = json.getJSONObject("error")
            val codeName = errorJson.optString("code")
            val code = BridgeErrorCode.entries.firstOrNull { it.name == codeName }
                ?: BridgeErrorCode.INTERNAL
            EnvelopeError(code, errorJson.optString("message"))
        } else {
            null
        }

        return Envelope(
            kind = kind,
            id = json.optNullableString("id"),
            method = json.optNullableString("method"),
            payload = payload,
            error = error,
            schemaHash = json.optNullableString("schemaHash"),
            reason = json.optNullableString("reason"),
        )
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null
}
