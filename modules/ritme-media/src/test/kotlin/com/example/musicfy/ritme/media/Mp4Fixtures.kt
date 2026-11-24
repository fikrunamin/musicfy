package com.example.musicfy.ritme.media

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** The start of a faststart MP4: `ftyp`, then `moov` holding `mvhd` (and [extraBox], an empty box of that type). */
internal fun mp4Head(version: Int, timescale: Long, duration: Long, extraBox: String? = null): ByteArray {
    val out = ByteArrayOutputStream()
    fun box(type: String, body: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()
    out.write(box("ftyp", "isom\u0000\u0000\u0002\u0000isom".toByteArray(Charsets.ISO_8859_1)))
    val mvhd = ByteBuffer.allocate(if (version == 0) 100 else 112)
    mvhd.put(version.toByte()).put(byteArrayOf(0, 0, 0))
    if (version == 0) {
        mvhd.putInt(0).putInt(0).putInt(timescale.toInt()).putInt(duration.toInt())
    } else {
        mvhd.putLong(0).putLong(0).putInt(timescale.toInt()).putLong(duration)
    }
    val extra = extraBox?.let { box(it, ByteArray(0)) } ?: ByteArray(0)
    out.write(box("moov", box("mvhd", mvhd.array()) + extra))
    return out.toByteArray()
}
