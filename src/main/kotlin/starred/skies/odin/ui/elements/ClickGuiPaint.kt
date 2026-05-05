package starred.skies.odin.ui.elements

import com.github.noamm9.nvgrenderer.helpers.NvgText
import com.github.noamm9.nvgrenderer.nvg.NVGPIP.Companion.drawNVG
import net.minecraft.client.gui.GuiGraphics
import java.awt.Color

object ClickGuiPaint {
    fun scaledNvgPx(scale: Float): Float = 9f * scale * 1.2f

    fun nvgLabelTopY(rowTop: Int, rowHeight: Int, scale: Float): Float {
        val px = scaledNvgPx(scale)
        val centered = rowTop + (rowHeight - px) / 2f
        return centered - 0.5f
    }

    fun drawScaledText(gui: GuiGraphics, text: String, x: Float, y: Float, color: Int, scale: Float) {
        val px = scaledNvgPx(scale)
        val clean = NvgText.stripFormatting(text)
        gui.drawNVG {
            NvgText.draw(clean, x, y, Color(color, true), px)
        }
    }

    fun drawScaledCenteredText(gui: GuiGraphics, text: String, centerX: Int, y: Float, color: Int, scale: Float) {
        val px = scaledNvgPx(scale)
        val clean = NvgText.stripFormatting(text)
        gui.drawNVG {
            NvgText.draw(
                clean,
                centerX.toFloat(),
                y,
                Color(color, true),
                px,
                align = NvgText.Align.CENTER
            )
        }
    }

    fun drawTopAccentBar(
        gui: GuiGraphics,
        x: Int,
        y: Int,
        width: Int,
        showRainbowBar: Boolean,
        accentBarSkeetFade: Boolean,
        accentColor: Int,
    ) {
        if (!showRainbowBar || width <= 0) return
        if (accentBarSkeetFade) drawRainbowBar(gui, x, y, width, 1)
        else drawAccentThemedBar(gui, x, y, width, 1, accentColor)
    }

    fun drawAccentThemedBar(gui: GuiGraphics, x: Int, y: Int, width: Int, height: Int, accentColor: Int) {
        if (width <= 0 || height <= 0) return
        val r0 = (accentColor shr 16) and 0xFF
        val g0 = (accentColor shr 8) and 0xFF
        val b0 = accentColor and 0xFF
        val hsb = Color.RGBtoHSB(r0, g0, b0, null)
        for (i in 0 until width) {
            val t = (i.toFloat() / (width - 1).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            val s = t * t * (3f - 2f * t)
            val br = 0.38f + 0.62f * s
            val sat = 0.78f + 0.22f * s
            val c = Color.getHSBColor(
                hsb[0],
                (hsb[1] * sat).coerceIn(0f, 1f),
                (br * hsb[2]).coerceIn(0.08f, 1f)
            )
            val argb = 0xFF000000.toInt() or (c.red shl 16) or (c.green shl 8) or c.blue
            gui.fill(x + i, y, x + i + 1, y + height, argb)
        }
    }

    fun drawRainbowBar(gui: GuiGraphics, x: Int, y: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        for (i in 0 until width) {
            val t = (i.toFloat() / (width - 1).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            val rgb = skeetAccentRgbAt(t)
            val argb = 0xFF000000.toInt() or rgb
            gui.fill(x + i, y, x + i + 1, y + height, argb)
        }
    }

    fun skeetAccentRgbAt(t: Float): Int {
        val stops = arrayOf(
            0.00f to intArrayOf(86, 188, 255),
            0.22f to intArrayOf(88, 220, 170),
            0.46f to intArrayOf(168, 226, 102),
            0.64f to intArrayOf(236, 198, 92),
            0.80f to intArrayOf(229, 106, 108),
            0.92f to intArrayOf(211, 103, 178),
            1.00f to intArrayOf(140, 122, 224)
        )
        val clamped = t.coerceIn(0f, 1f)
        var idx = 0
        while (idx < stops.size - 1 && clamped > stops[idx + 1].first) idx++
        val (t0, c0) = stops[idx]
        val (t1, c1) = stops[(idx + 1).coerceAtMost(stops.lastIndex)]
        val localT = if (t1 <= t0) 0f else ((clamped - t0) / (t1 - t0)).coerceIn(0f, 1f)
        val s = localT * localT * (3f - 2f * localT)
        var r = (c0[0] + (c1[0] - c0[0]) * s).toInt().coerceIn(0, 255)
        var g = (c0[1] + (c1[1] - c0[1]) * s).toInt().coerceIn(0, 255)
        var b = (c0[2] + (c1[2] - c0[2]) * s).toInt().coerceIn(0, 255)
        val avg = (r + g + b) / 3
        val satKeep = 0.88f
        r = (avg + (r - avg) * satKeep).toInt().coerceIn(0, 255)
        g = (avg + (g - avg) * satKeep).toInt().coerceIn(0, 255)
        b = (avg + (b - avg) * satKeep).toInt().coerceIn(0, 255)
        r = (r * 0.94f).toInt().coerceIn(0, 255)
        g = (g * 0.94f).toInt().coerceIn(0, 255)
        b = (b * 0.94f).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }
}
