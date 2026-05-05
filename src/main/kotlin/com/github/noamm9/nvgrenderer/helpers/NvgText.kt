package com.github.noamm9.nvgrenderer.helpers

import com.github.noamm9.nvgrenderer.nvg.Font
import com.github.noamm9.nvgrenderer.nvg.NVG
import java.awt.Color

/**
 * Text helpers built on top of [NVG].
 */
object NvgText {
    var font: Font = NVG.font
    var size: Float = 9f

    enum class Align { LEFT, CENTER, RIGHT }

    fun width(text: String, size: Float = this.size, font: Font = this.font): Float {
        return NVG.textWidth(stripFormatting(text), size, font)
    }

    fun draw(
        text: String,
        x: Float,
        y: Float,
        color: Color = Color.WHITE,
        size: Float = this.size,
        font: Font = this.font,
        align: Align = Align.LEFT,
        shadow: Boolean = false
    ) {
        val clean = stripFormatting(text)
        val drawX = when (align) {
            Align.LEFT -> x
            Align.CENTER -> x - (width(clean, size, font) / 2f)
            Align.RIGHT -> x - width(clean, size, font)
        }

        if (shadow) NVG.textShadow(clean, drawX, y, size, color, font)
        else NVG.text(clean, drawX, y, size, color, font)
    }

    fun drawGradient(
        text: String,
        x: Float,
        y: Float,
        color1: Color,
        color2: Color,
        size: Float = this.size,
        font: Font = this.font,
        align: Align = Align.LEFT
    ) {
        val clean = stripFormatting(text)
        val w = width(clean, size, font)
        val drawX = when (align) {
            Align.LEFT -> x
            Align.CENTER -> x - w / 2f
            Align.RIGHT -> x - w
        }

        NVG.textGradient(clean, drawX, y, size, w, color1, color2, font)
    }

    fun wrap(text: String, maxWidth: Float, size: Float = this.size, font: Font = this.font): List<String> {
        val words = stripFormatting(text).split(' ')
        val lines = mutableListOf<String>()
        var line = StringBuilder()

        fun flush() {
            if (line.isNotEmpty()) {
                lines.add(line.toString())
                line = StringBuilder()
            }
        }

        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (width(candidate, size, font) <= maxWidth || line.isEmpty()) {
                if (line.isNotEmpty()) line.append(' ')
                line.append(word)
            } else {
                flush()
                line.append(word)
            }
        }

        flush()
        return lines
    }

    /**
     * Removes Minecraft formatting codes from a string before measuring or rendering it.
     */
    fun stripFormatting(text: String): String {
        if (text.isEmpty()) return text

        val builder = StringBuilder(text.length)
        var index = 0

        while (index < text.length) {
            val current = text[index]

            if (current == '\u00A7') {
                index = (index + 2).coerceAtMost(text.length)
                continue
            }

            if (current == '\uFFFD' && index + 2 < text.length && text[index + 1] == '\u00A7') {
                index += 3
                continue
            }

            builder.append(current)
            index++
        }

        return builder.toString()
    }
}
