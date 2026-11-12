package com.metrolist.music.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.R
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.utils.ComposeToImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Renders the album/song poster shared to an Instagram story: a full-bleed gradient
 * (or blurred-art) [background] plus a centered card [sticker] with cover art, title,
 * artist and a QR code linking back to the track.
 */
object StoryPoster {
    const val BACKGROUND_WIDTH = 1440
    const val BACKGROUND_HEIGHT = 2560
    const val STICKER_WIDTH_FRACTION = 0.78f

    private const val STICKER_DESIGN_WIDTH = 640.0f
    private const val COVER_ART_SIZE = 1200
    private const val PALETTE_SIZE = 6
    private const val STOP_COUNT = 3

    val STICKER_WIDTH = 1123

    data class Assets(
        val background: Bitmap,
        val sticker: Bitmap,
        val gradientColors: List<Int>,
        val palette: List<Int>,
    )

    suspend fun render(
        context: Context,
        coverArtUrl: String?,
        title: String,
        artist: String,
        linkUrl: String,
        style: StoryBackgroundStyle = StoryBackgroundStyle.GRADIENT,
        font: StoryFont = StoryFont.APP,
        colorOverride: List<Int>? = null,
    ): Assets = withContext(Dispatchers.Default) {
        val coverArt = loadCoverArt(context, coverArtUrl)
        val palette = artworkPalette(coverArt)
        val gradient = gradientStops(palette, colorOverride)
        Assets(
            background = renderBackground(coverArt, gradient, style),
            sticker = renderSticker(context, coverArt, title, artist, linkUrl, font),
            gradientColors = gradient,
            palette = palette,
        )
    }

    /** Composites the [Assets] into a single full-size poster bitmap. */
    suspend fun flatten(assets: Assets): Bitmap = withContext(Dispatchers.Default) {
        val bitmap = Bitmap.createBitmap(BACKGROUND_WIDTH, BACKGROUND_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        canvas.drawBitmap(
            assets.background,
            Rect(0, 0, assets.background.width, assets.background.height),
            RectF(0f, 0f, BACKGROUND_WIDTH.toFloat(), BACKGROUND_HEIGHT.toFloat()),
            paint,
        )
        val stickerWidth = STICKER_WIDTH_FRACTION * BACKGROUND_WIDTH
        val stickerHeight = assets.sticker.height * (stickerWidth / assets.sticker.width)
        val left = (BACKGROUND_WIDTH - stickerWidth) / 2f
        val top = (BACKGROUND_HEIGHT - stickerHeight) / 2f
        canvas.drawBitmap(
            assets.sticker,
            Rect(0, 0, assets.sticker.width, assets.sticker.height),
            RectF(left, top, left + stickerWidth, top + stickerHeight),
            paint,
        )
        bitmap
    }

    private suspend fun loadCoverArt(context: Context, coverArtUrl: String?): Bitmap? {
        if (coverArtUrl.isNullOrBlank()) return null
        return try {
            val request = ImageRequest.Builder(context)
                .data(coverArtUrl.resize(COVER_ART_SIZE, COVER_ART_SIZE))
                .size(COVER_ART_SIZE)
                .allowHardware(false)
                .build()
            ImageLoader(context).execute(request).image?.toBitmap()
        } catch (e: Exception) {
            null
        }
    }

    // --- palette / color helpers -------------------------------------------------

    private fun artworkPalette(coverArt: Bitmap?): List<Int> {
        if (coverArt == null) return emptyList()
        return try {
            Palette.from(coverArt).maximumColorCount(24).generate().swatches
                .sortedByDescending { it.population }
                .map { it.rgb }
                .filter { saturationOf(it) > 0.15f }
                .distinctBy { hueBucketOf(it) }
                .take(PALETTE_SIZE)
                .map { storyTone(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun gradientStops(palette: List<Int>, override: List<Int>?): List<Int> {
        val base = (override?.takeIf { it.isNotEmpty() } ?: palette).take(STOP_COUNT)
        val stops = when (base.size) {
            0 -> List(STOP_COUNT) { 0xFF212121.toInt() }
            1 -> listOf(0.46f, 0.3f, 0.16f).map { adjustLightness(base[0], it) }
            2 -> listOf(base[0], blend(base[0], base[1]), base[1])
            else -> base.take(STOP_COUNT)
        }
        return stops.sortedByDescending { lightnessOf(it) }
    }

    private fun saturationOf(color: Int): Float {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        return hsl[1]
    }

    private fun lightnessOf(color: Int): Float {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        return hsl[2]
    }

    private fun hueBucketOf(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        return (hsl[0] / 30.0f).toInt()
    }

    private fun storyTone(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = minOf(hsl[1], 0.75f)
        hsl[2] = hsl[2].coerceIn(0.18f, 0.52f)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun adjustLightness(color: Int, lightness: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = minOf(hsl[1], 0.7f)
        hsl[2] = lightness
        return ColorUtils.HSLToColor(hsl)
    }

    private fun blend(first: Int, second: Int): Int = ColorUtils.blendARGB(first, second, 0.5f)

    // --- drawing -----------------------------------------------------------------

    private fun renderBackground(
        coverArt: Bitmap?,
        colors: List<Int>,
        style: StoryBackgroundStyle,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(BACKGROUND_WIDTH, BACKGROUND_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(0f, 0f, BACKGROUND_WIDTH.toFloat(), BACKGROUND_HEIGHT.toFloat())
        val paint = Paint().apply { isAntiAlias = true }
        val intColors = colors.toIntArray()
        val positions = FloatArray(colors.size) { i -> i / (colors.size - 1.0f) }
        paint.shader = LinearGradient(
            0f, 0f, 0f, BACKGROUND_HEIGHT.toFloat(),
            intColors, positions, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(rect, paint)
        if (style == StoryBackgroundStyle.BLURRED_ART && coverArt != null) {
            drawBlurredCover(canvas, coverArt, BACKGROUND_WIDTH.toFloat(), BACKGROUND_HEIGHT.toFloat())
        }
        return bitmap
    }

    private fun drawBlurredCover(canvas: Canvas, coverArt: Bitmap, width: Float, height: Float) {
        try {
            val blurred = ComposeToImage.fastBlur(coverArt, 0.125f, 12)
                ?: Bitmap.createScaledBitmap(
                    coverArt,
                    max(1, coverArt.width / 8),
                    max(1, coverArt.height / 8),
                    true,
                )
            val paint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
                alpha = 210
            }
            val scale = max(width / blurred.width, height / blurred.height)
            val scaledW = blurred.width * scale
            val scaledH = blurred.height * scale
            val left = (width - scaledW) / 2f
            val top = (height - scaledH) / 2f
            canvas.drawBitmap(
                blurred,
                Rect(0, 0, blurred.width, blurred.height),
                RectF(left, top, left + scaledW, top + scaledH),
                paint,
            )
            canvas.drawRect(RectF(0f, 0f, width, height), Paint().apply { color = 0x59000000 })
        } catch (e: Exception) {
        }
    }

    private fun renderSticker(
        context: Context,
        coverArt: Bitmap?,
        title: String,
        artist: String,
        linkUrl: String,
        font: StoryFont,
    ): Bitmap {
        val width = STICKER_WIDTH
        val scale = width / STICKER_DESIGN_WIDTH
        val pad = scale * 44.0f
        val coverSize = width - pad * 2.0f
        val corner = scale * 40.0f

        val faces = font.resolve(context)
        val titlePaint = TextPaint().apply {
            isAntiAlias = true
            color = -0x1 // white
            textSize = scale * 46.0f
            typeface = faces.bold
            isFakeBoldText = faces.syntheticBold
        }
        val artistPaint = TextPaint().apply {
            isAntiAlias = true
            color = -0x4c000001 // white @ ~0.76 alpha
            textSize = scale * 34.0f
            typeface = faces.regular
        }
        val appNamePaint = TextPaint().apply {
            isAntiAlias = true
            color = -0x75000001 // white @ ~0.54 alpha
            textSize = 28.0f * scale
            typeface = faces.bold
            isFakeBoldText = faces.syntheticBold
        }

        val qrSize = 148.0f * scale
        val qrPad = scale * 8.0f
        val gapAfterCover = scale * 62.0f
        val gapAfterTitle = scale * 46.0f
        val gapAfterArtist = scale * 34.0f
        val coverRadius = 16.0f * scale
        val appMarkLeft = 46.0f * scale

        val height = (pad + coverSize + gapAfterCover + gapAfterTitle + gapAfterArtist + qrSize + pad).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // card background
        canvas.drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            corner, corner,
            Paint().apply { isAntiAlias = true; color = -0x0de1e1e6 },
        )

        // cover art
        val coverRect = RectF(pad, pad, pad + coverSize, pad + coverSize)
        if (coverArt != null) {
            drawRoundedBitmap(canvas, coverArt, coverRect, coverRadius)
        } else {
            canvas.drawRoundRect(
                coverRect, coverRadius, coverRadius,
                Paint().apply { isAntiAlias = true; color = -0x00d1d1d2 },
            )
        }

        val textWidth = width - pad * 2.0f
        var baseline = pad + coverSize + gapAfterCover
        canvas.drawText(
            TextUtils.ellipsize(title, titlePaint, textWidth, TextUtils.TruncateAt.END).toString(),
            pad, baseline, titlePaint,
        )
        baseline += gapAfterTitle
        canvas.drawText(
            TextUtils.ellipsize(artist, artistPaint, textWidth, TextUtils.TruncateAt.END).toString(),
            pad, baseline, artistPaint,
        )
        baseline += gapAfterArtist

        val qrLeft = (width - pad) - qrSize
        val qr = QrEncoder.encode(linkUrl, qrSize.toInt(), -0x1000000, -0x1)
        if (qr != null) {
            canvas.drawRoundRect(
                RectF(qrLeft - qrPad, baseline - qrPad, qrLeft + qrSize + qrPad, baseline + qrSize + qrPad),
                scale * 12.0f, 12.0f * scale,
                Paint().apply { isAntiAlias = true; color = -0x1 },
            )
            canvas.drawBitmap(qr, qrLeft, baseline, Paint().apply { isFilterBitmap = false })
        }

        drawAppMark(context, canvas, pad, baseline + qrSize / 2.0f, scale * 34.0f)
        val appNameLeft = pad + appMarkLeft
        canvas.drawText(
            TextUtils.ellipsize(
                context.getString(R.string.app_name),
                appNamePaint,
                (((qrLeft - qrPad) - (pad / 2.0f)) - appNameLeft).coerceAtLeast(0.0f),
                TextUtils.TruncateAt.END,
            ).toString(),
            appNameLeft, baseline + qrSize / 2.0f + 10.0f * scale, appNamePaint,
        )

        return bitmap
    }

    private fun drawAppMark(context: Context, canvas: Canvas, left: Float, centerY: Float, size: Float) {
        try {
            val drawable = ContextCompat.getDrawable(context, R.drawable.small_icon) ?: return
            val bitmap = drawable.toBitmap(size.toInt(), size.toInt())
            val dest = RectF(left, centerY - size / 2.0f, left + size, centerY + size / 2.0f)
            val paint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
            }
            canvas.drawBitmap(bitmap, null, dest, paint)
        } catch (e: Exception) {
        }
    }

    private fun drawRoundedBitmap(canvas: Canvas, source: Bitmap, dest: RectF, radius: Float) {
        val outer = canvas.save()
        try {
            val path = Path().apply { addRoundRect(dest, radius, radius, Path.Direction.CW) }
            canvas.clipPath(path)
            val scale = max(dest.width() / source.width, dest.height() / source.height)
            val scaledW = source.width * scale
            val scaledH = source.height * scale
            val left = dest.centerX() - scaledW / 2.0f
            val top = dest.centerY() - scaledH / 2.0f
            val inner = canvas.save()
            canvas.translate(left, top)
            try {
                canvas.drawBitmap(
                    source,
                    Rect(0, 0, source.width, source.height),
                    RectF(0f, 0f, scaledW, scaledH),
                    Paint().apply { isAntiAlias = true; isFilterBitmap = true },
                )
            } finally {
                canvas.restoreToCount(inner)
            }
        } finally {
            canvas.restoreToCount(outer)
        }
    }
}
