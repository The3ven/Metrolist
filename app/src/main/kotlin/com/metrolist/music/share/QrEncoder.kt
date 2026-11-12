package com.metrolist.music.share

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Renders a QR code bitmap for the story poster's link. */
object QrEncoder {
    fun encode(
        content: String,
        size: Int,
        foreground: Int = Color.BLACK,
        background: Int = Color.WHITE,
    ): Bitmap? {
        if (content.isBlank() || size <= 0) return null
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1,
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) foreground else background)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
