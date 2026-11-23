package com.example.musicfy.core.resolver

import java.io.ByteArrayOutputStream

/**
 * Port of android.net.Uri.encode / Uri.decode (libcore UriCodec), so moved code builds the same
 * URLs on every platform. Not URLEncoder: that turns ' ' into '+' and escapes "~!*'()".
 */
object UriCodec {
    private const val HEX = "0123456789ABCDEF"
    private const val ALLOWED = "_-!.~'()*"

    fun encode(s: String): String {
        val out = StringBuilder(s.length)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in ALLOWED)) {
                out.append(ch)
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    /** '+' stays '+'. Malformed escapes become U+FFFD exactly as Android does. */
    fun decode(s: String): String {
        val out = StringBuilder(s.length)
        val pending = ByteArrayOutputStream()
        fun flush() {
            if (pending.size() == 0) return
            out.append(String(pending.toByteArray(), Charsets.UTF_8))
            pending.reset()
        }
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '%') {
                flush()
                out.append(c)
                continue
            }
            var value = 0
            for (j in 0 until 2) {
                if (i >= s.length) {
                    flush()
                    out.append('�')
                    return out.toString()
                }
                val digit = hexValue(s[i++])
                if (digit < 0) {
                    flush()
                    out.append('�')
                    break
                }
                value = value * 16 + digit
            }
            // Android writes the accumulated byte even after an invalid digit; kept for parity.
            pending.write(value and 0xFF)
        }
        flush()
        return out.toString()
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
