package com.example.musicfy.ui.screens.ritme

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A QR code as modules: [dark] is row-major, [size] × [size], quiet zone included. */
internal class QrModules(val size: Int, val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
}

/** QR encoding for the room link. Pure (ZXing core is plain Java), for unit tests. */
internal object RitmeQr {
    /** Quiet zone in modules on each side; the code is drawn on its own white square, so 2 is enough to scan. */
    const val MARGIN = 2

    /** One module per bit: width/height 0 make ZXing pick the smallest size for [text]. */
    fun encode(text: String): QrModules {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to MARGIN))
        val size = matrix.width
        return QrModules(size, BooleanArray(size * size) { matrix.get(it % size, it / size) })
    }
}

private fun QrModules.toBitmap(): ImageBitmap {
    val pixels = IntArray(size * size) { if (dark[it]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * The room link as a QR code (spec 3.3). Black on white regardless of theme on purpose: phone
 * cameras need the contrast to scan it (the one allowed exception to the sheet palette). Encoded
 * off the main thread, one pixel per module, then scaled up without filtering so the modules stay
 * sharp-edged (separate rects per module leave anti-aliased seams between them).
 */
@Composable
internal fun RitmeQrCode(text: String, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val image by produceState<ImageBitmap?>(initialValue = null, text) {
        value = withContext(Dispatchers.Default) { RitmeQr.encode(text).toBitmap() }
    }
    Canvas(
        modifier
            .aspectRatio(1f)
            .background(Color.White)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    ) {
        val bitmap = image ?: return@Canvas
        drawImage(
            image = bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.None,
        )
    }
}
