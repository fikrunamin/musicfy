package com.example.musicfy.ui.screens.ritme

import com.example.musicfy.ritme.RitmeLinks
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeQrTest {

    /** Decodes [modules] with ZXing's own reader, each module drawn as a [scale]-pixel square. */
    private fun decode(modules: QrModules, scale: Int = 4): String {
        val side = modules.size * scale
        val pixels = IntArray(side * side) { i ->
            if (modules[(i % side) / scale, (i / side) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test
    fun theCodeHoldsExactlyTheShareLink() {
        val link = RitmeLinks.shareLink("https://ritme.dubeli.id", "K7M2QX")
        assertEquals("https://ritme.dubeli.id/r/K7M2QX", link)
        assertEquals(link, decode(RitmeQr.encode(link)))
    }

    @Test
    fun aCustomServerLinkRoundTripsToo() {
        val link = RitmeLinks.shareLink("http://192.168.1.20:8080/", "ABCDEF")
        assertEquals(link, decode(RitmeQr.encode(link)))
    }

    @Test
    fun theMatrixIsSquareWithAWhiteQuietZone() {
        val modules = RitmeQr.encode("https://ritme.dubeli.id/r/K7M2QX")
        assertEquals(modules.size * modules.size, modules.dark.size)
        // Smallest version that fits (21 + 4k modules) plus the margin on both sides.
        assertEquals(0, (modules.size - 2 * RitmeQr.MARGIN - 21) % 4)
        for (i in 0 until modules.size) {
            for (m in 0 until RitmeQr.MARGIN) {
                assertFalse(modules[i, m])
                assertFalse(modules[m, i])
                assertFalse(modules[i, modules.size - 1 - m])
                assertFalse(modules[modules.size - 1 - m, i])
            }
        }
        // The top-left finder pattern starts right after the quiet zone.
        assertTrue(modules[RitmeQr.MARGIN, RitmeQr.MARGIN])
    }
}
