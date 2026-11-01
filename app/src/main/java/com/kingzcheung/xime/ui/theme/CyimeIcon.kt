package com.kingzcheung.xime.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.remember
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.kingzcheung.xime.R

private fun iconResource(style: VisualStyle): Int = when (style) {
    VisualStyle.NEON -> R.drawable.cyime_mark_neon
    VisualStyle.GLASS -> R.drawable.cyime_mark_glass
    VisualStyle.FROST -> R.drawable.cyime_mark_frost
    else -> R.drawable.cyime_mark_facet
}

/** Shared generated vector. No baked-in tile, outer frame, or launcher mask in the artwork. */
@Composable
fun CyimeGeneratedIcon(style: VisualStyle, modifier: Modifier = Modifier, background: Boolean = false, themeAccent: Color? = null) {
    val context = LocalContext.current
    val painter = if (themeAccent == null) {
        painterResource(iconResource(style))
    } else {
        val bitmap = remember(context, style, themeAccent) {
            CyimeIconGenerator.renderThemed(context, style, themeAccent.toArgb())
        }
        remember(bitmap) { BitmapPainter(bitmap.asImageBitmap()) }
    }
    Image(painter, "Lime · ${style.title}", modifier, contentScale = ContentScale.Fit)
}

/** Export the production vector, rather than a second drawing implementation. */
object CyimeIconGenerator {
    fun render(context: Context, style: VisualStyle, pixels: Int = 432, adaptiveForeground: Boolean = false): Bitmap {
        require(pixels in 48..2048)
        val source = requireNotNull(ResourcesCompat.getDrawable(context.resources, iconResource(style), context.theme))
        val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
        val inset = if (adaptiveForeground) (pixels * .16f).toInt() else 0
        source.setBounds(inset, inset, pixels - inset, pixels - inset)
        source.draw(Canvas(bitmap))
        return bitmap
    }

    /** Tint only the keyboard mark while retaining each vector gradient and its transparent edges. */
    fun renderThemed(context: Context, style: VisualStyle, accent: Int): Bitmap {
        val bitmap = render(context, style, pixels = 192)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val accentHsl = FloatArray(3)
        val pixelHsl = FloatArray(3)
        ColorUtils.colorToHSL(accent, accentHsl)
        val lightnessShift = (accentHsl[2] - .6f) * .35f
        for (i in pixels.indices) {
            val pixel = pixels[i]
            if (AndroidColor.alpha(pixel) == 0) continue
            ColorUtils.colorToHSL(pixel, pixelHsl)
            pixelHsl[0] = accentHsl[0]
            pixelHsl[1] = (pixelHsl[1] * accentHsl[1] / .65f).coerceIn(0f, 1f)
            pixelHsl[2] = (pixelHsl[2] + lightnessShift).coerceIn(0f, 1f)
            pixels[i] = (pixel and -0x1000000) or (ColorUtils.HSLToColor(pixelHsl) and 0x00FFFFFF)
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return bitmap
    }
}
