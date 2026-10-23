package me.xechoz.jsbridge

/**
 * Small dependency-free Base64 codec. The bridge uses standard Base64 with padding,
 * which is what the WebView side's btoa/atob produce and consume.
 */
internal object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val decodeTable = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, character ->
            table[character.code] = index
        }
    }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val result = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index++].toInt() and 0xff
            val second = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
            val third = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1

            result.append(ALPHABET[first shr 2])
            result.append(ALPHABET[((first and 0x03) shl 4) or if (second >= 0) second shr 4 else 0])
            result.append(
                if (second >= 0) {
                    ALPHABET[((second and 0x0f) shl 2) or if (third >= 0) third shr 6 else 0]
                } else {
                    '='
                },
            )
            result.append(if (third >= 0) ALPHABET[third and 0x3f] else '=')
        }
        return result.toString()
    }

    fun decode(value: String): ByteArray {
        if (value.isEmpty()) return ByteArray(0)
        val result = ByteArray(value.length * 3 / 4 + 3)
        var resultIndex = 0
        var buffer = 0
        var bits = 0

        for (character in value) {
            if (character == '=') break
            if (character == '\n' || character == '\r' || character == ' ' || character == '\t') continue
            val code = character.code
            val decoded = if (code < decodeTable.size) decodeTable[code] else -1
            require(decoded >= 0) { "Invalid Base64 character: $character" }

            buffer = (buffer shl 6) or decoded
            bits += 6
            if (bits >= 8) {
                bits -= 8
                result[resultIndex++] = ((buffer shr bits) and 0xff).toByte()
            }
        }
        return result.copyOf(resultIndex)
    }
}
