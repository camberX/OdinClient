@file:Suppress("unused")

package com.github.noamm9.nvgrenderer.nvg

import com.github.noamm9.nvgrenderer.nvg.NVG.createImage
import com.github.noamm9.nvgrenderer.nvg.NVG.deleteImage
import com.github.noamm9.nvgrenderer.nvg.enums.Gradient
import net.minecraft.client.Minecraft
import org.joml.Matrix3x2fc
import org.lwjgl.nanovg.NVGColor
import org.lwjgl.nanovg.NVGPaint
import org.lwjgl.nanovg.NanoSVG.*
import org.lwjgl.nanovg.NanoVG.*
import org.lwjgl.nanovg.NanoVGGL3.*
import org.lwjgl.opengl.GL33C
import org.lwjgl.stb.STBImage.stbi_image_free
import org.lwjgl.stb.STBImage.stbi_load_from_memory
import org.lwjgl.system.MemoryUtil.memAlloc
import org.lwjgl.system.MemoryUtil.memFree
import java.awt.Color
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

object NVG {
    private val nvgPaint = NVGPaint.malloc()
    private val nvgColor = NVGColor.malloc()
    private val nvgColor2 = NVGColor.malloc()

    private val images = HashMap<Image, NVGImage>()
    private val fontMap = HashMap<Font, NVGFont>()
    private val fontBounds = FloatArray(4)
    private var scissor: Scissor? = null
    private val scissorStack = ArrayDeque<Scissor?>()

    val font = Font("assets/nvgrenderer/inter.ttf")
    val vg by lazy {
        nvgCreate(NVG_ANTIALIAS or NVG_STENCIL_STROKES).takeUnless { it == - 1L }
            ?: throw RuntimeException("Failed to create nvg")
    }

    fun devicePixelRatio(): Float {
        return try {
            val window = Minecraft.getInstance().window
            window.width.toFloat() / window.screenWidth.toFloat()
        }
        catch (_: Throwable) {
            1f
        }
    }

    fun beginFrame(width: Float, height: Float) {
        beginFrame(width, height, devicePixelRatio())
    }

    fun beginFrame(width: Float, height: Float, dpr: Float) {
        scissor = null
        scissorStack.clear()
        nvgBeginFrame(vg, width / dpr, height / dpr, dpr)
        nvgTextAlign(vg, NVG_ALIGN_LEFT or NVG_ALIGN_TOP)
    }

    fun endFrame() = nvgEndFrame(vg)

    fun push() {
        scissorStack.addLast(scissor)
        nvgSave(vg)
    }

    fun pop() {
        nvgRestore(vg)
        scissor = if (scissorStack.isNotEmpty()) scissorStack.removeLast() else null
    }

    fun scale(x: Number, y: Number) = nvgScale(vg, x.toFloat(), y.toFloat())

    fun scale(n: Number) = nvgScale(vg, n.toFloat(), n.toFloat())

    fun translate(x: Number, y: Number) = nvgTranslate(vg, x.toFloat(), y.toFloat())

    fun rotate(amount: Number) = nvgRotate(vg, amount.toFloat())

    fun transform(matrix: Matrix3x2fc) {
        nvgTransform(vg, matrix.m00(), matrix.m01(), matrix.m10(), matrix.m11(), matrix.m20(), matrix.m21())
    }

    fun globalAlpha(amount: Number) = nvgGlobalAlpha(vg, amount.toFloat().coerceIn(0f, 1f))

    fun pushScissor(x: Number, y: Number, w: Number, h: Number) {
        scissor = Scissor(scissor, x.toFloat(), y.toFloat(), w.toFloat() + x.toFloat(), h.toFloat() + y.toFloat())
        scissor?.applyScissor()
    }

    fun popScissor() {
        nvgResetScissor(vg)
        scissor = scissor?.previous
        scissor?.applyScissor()
    }

    fun line(x1: Number, y1: Number, x2: Number, y2: Number, thickness: Number, color: Color) {
        nvgBeginPath(vg)
        nvgMoveTo(vg, x1.toFloat(), y1.toFloat())
        nvgLineTo(vg, x2.toFloat(), y2.toFloat())
        nvgStrokeWidth(vg, thickness.toFloat())
        color(color)
        nvgStrokeColor(vg, nvgColor)
        nvgStroke(vg)
    }

    fun drawHalfRoundedRect(x: Number, y: Number, w: Number, h: Number, color: Color, radius: Number, roundTop: Boolean) {
        val fx = x.toFloat()
        val fy = y.toFloat()
        val fw = w.toFloat()
        val fh = h.toFloat()
        val fr = radius.toFloat()

        nvgBeginPath(vg)

        if (roundTop) {
            nvgMoveTo(vg, fx, fy + fh)
            nvgLineTo(vg, fx + fw, fy + fh)
            nvgLineTo(vg, fx + fw, fy + fr)
            nvgArcTo(vg, fx + fw, fy, fx + fw - fr, fy, fr)
            nvgLineTo(vg, fx + fr, fy)
            nvgArcTo(vg, fx, fy, fx, fy + fr, fr)
            nvgLineTo(vg, fx, fy + fh)
        }
        else {
            nvgMoveTo(vg, fx, fy)
            nvgLineTo(vg, fx + fw, fy)
            nvgLineTo(vg, fx + fw, fy + fh - fr)
            nvgArcTo(vg, fx + fw, fy + fh, fx + fw - fr, fy + fh, fr)
            nvgLineTo(vg, fx + fr, fy + fh)
            nvgArcTo(vg, fx, fy + fh, fx, fy + fh - fr, fr)
            nvgLineTo(vg, fx, fy)
        }

        nvgClosePath(vg)
        color(color)
        nvgFillColor(vg, nvgColor)
        nvgFill(vg)
    }

    fun rect(x: Number, y: Number, w: Number, h: Number, color: Color, radius: Number) {
        nvgBeginPath(vg)
        nvgRoundedRect(vg, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat() + .5f, radius.toFloat())
        color(color)
        nvgFillColor(vg, nvgColor)
        nvgFill(vg)
    }

    fun rect(x: Number, y: Number, w: Number, h: Number, color: Color) {
        nvgBeginPath(vg)
        nvgRect(vg, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat() + .5f)
        color(color)
        nvgFillColor(vg, nvgColor)
        nvgFill(vg)
    }

    fun hollowRect(x: Number, y: Number, w: Number, h: Number, thickness: Number, color: Color, radius: Number) {
        nvgBeginPath(vg)
        nvgRoundedRect(vg, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), radius.toFloat())
        nvgStrokeWidth(vg, thickness.toFloat())
        nvgPathWinding(vg, NVG_HOLE)
        color(color)
        nvgStrokeColor(vg, nvgColor)
        nvgStroke(vg)
    }

    fun gradientRect(
        x: Number, y: Number, w: Number, h: Number,
        color1: Color, color2: Color,
        gradient: Gradient, radius: Float
    ) {
        nvgBeginPath(vg)
        nvgRoundedRect(vg, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), radius)
        gradient(color1, color2, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), gradient)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    fun dropShadow(x: Number, y: Number, width: Number, height: Number, blur: Number, spread: Number, radius: Number) {
        val fx = x.toFloat()
        val fy = y.toFloat()
        val fw = width.toFloat()
        val fh = height.toFloat()
        val fb = blur.toFloat()
        val fs = spread.toFloat()
        val fr = radius.toFloat()

        nvgRGBA(0, 0, 0, 125, nvgColor)
        nvgRGBA(0, 0, 0, 0, nvgColor2)

        nvgBoxGradient(
            vg,
            fx - fs,
            fy - fs,
            fw + 2 * fs,
            fh + 2 * fs,
            fr + fs,
            fb,
            nvgColor,
            nvgColor2,
            nvgPaint
        )

        nvgBeginPath(vg)

        nvgRoundedRect(
            vg,
            fx - fs - fb,
            fy - fs - fb,
            fw + 2 * fs + 2 * fb,
            fh + 2 * fs + 2 * fb,
            fr + fs
        )

        nvgRoundedRect(vg, fx, fy, fw, fh, fr)
        nvgPathWinding(vg, NVG_HOLE)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    fun circle(x: Number, y: Number, radius: Number, color: Color) {
        nvgBeginPath(vg)
        nvgCircle(vg, x.toFloat(), y.toFloat(), radius.toFloat())
        color(color)
        nvgFillColor(vg, nvgColor)
        nvgFill(vg)
    }

    fun text(text: String, x: Number, y: Number, size: Number, color: Color, font: Font) {
        nvgFontSize(vg, size.toFloat())
        nvgFontFaceId(vg, getFontID(font))
        color(color)
        nvgFillColor(vg, nvgColor)
        nvgText(vg, x.toFloat(), y.toFloat() + .5f, text)
    }

    fun textGradient(
        text: String,
        x: Number,
        y: Number,
        size: Number,
        width: Number,
        color1: Color,
        color2: Color,
        font: Font,
        direction: Gradient = Gradient.LEFT_RIGHT
    ) {
        nvgFontSize(vg, size.toFloat())
        nvgFontFaceId(vg, getFontID(font))

        if (text.isEmpty()) return

        if (direction == Gradient.TOP_BOTTOM) {
            gradient(color1, color2, x.toFloat(), y.toFloat(), width.toFloat(), size.toFloat(), direction)
            nvgFillPaint(vg, nvgPaint)
            nvgText(vg, x.toFloat(), y.toFloat() + .5f, text)
            return
        }

        val totalWidth = width.toFloat().coerceAtLeast(1f)
        var cursorX = x.toFloat()

        for (index in text.indices) {
            val ch = text[index].toString()
            val charWidth = nvgTextBounds(vg, 0f, 0f, ch, fontBounds).coerceAtLeast(0f)
            val center = ((cursorX - x.toFloat()) + charWidth * 0.5f) / totalWidth
            val t = center.coerceIn(0f, 1f)
            color(lerp(color1, color2, t))
            nvgFillColor(vg, nvgColor)
            nvgText(vg, cursorX, y.toFloat() + .5f, ch)
            cursorX += charWidth
        }
    }

    fun textShadow(text: String, x: Number, y: Number, size: Number, color: Color, font: Font) {
        nvgFontFaceId(vg, getFontID(font))
        nvgFontSize(vg, size.toFloat())
        color(Color.BLACK)
        nvgFillColor(vg, nvgColor)
        nvgText(vg, round(x.toFloat() + 2f), round(y.toFloat() + 2f), text)

        color(color)
        nvgFillColor(vg, nvgColor)
        nvgText(vg, round(x.toFloat()), round(y.toFloat()), text)
    }

    fun textWidth(text: String, size: Number, font: Font): Float {
        nvgFontSize(vg, size.toFloat())
        nvgFontFaceId(vg, getFontID(font))
        return nvgTextBounds(vg, 0f, 0f, text, fontBounds)
    }

    fun drawWrappedString(
        text: String,
        x: Number,
        y: Number,
        w: Number,
        size: Number,
        color: Color,
        font: Font,
        lineHeight: Number = 1f
    ) {
        nvgFontSize(vg, size.toFloat())
        nvgFontFaceId(vg, getFontID(font))
        nvgTextLineHeight(vg, lineHeight.toFloat())
        color(color)
        nvgFillColor(vg, nvgColor)
        nvgTextBox(vg, x.toFloat(), y.toFloat(), w.toFloat(), text)
    }

    fun wrappedTextBounds(
        text: String,
        w: Number,
        size: Number,
        font: Font,
        lineHeight: Number = 1f
    ): FloatArray {
        val bounds = FloatArray(4)
        nvgFontSize(vg, size.toFloat())
        nvgFontFaceId(vg, getFontID(font))
        nvgTextLineHeight(vg, lineHeight.toFloat())
        nvgTextBoxBounds(vg, 0f, 0f, w.toFloat(), text, bounds)
        return bounds
    }

    fun createNVGImage(textureId: Int, textureWidth: Number, textureHeight: Number): Int {
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, textureId)
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST)
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST)
        return nvglCreateImageFromHandle(vg, textureId, textureWidth.toInt(), textureHeight.toInt(), NVG_IMAGE_NEAREST or NVG_IMAGE_NODELETE)
    }

    fun image(image: Int, textureWidth: Number, textureHeight: Number, subX: Number, subY: Number, subW: Number, subH: Number, x: Number, y: Number, w: Number, h: Number, radius: Number) {
        if (image == - 1) return

        val sx = subX.toFloat() / textureWidth.toFloat()
        val sy = subY.toFloat() / textureHeight.toFloat()
        val sw = subW.toFloat() / textureWidth.toFloat()
        val sh = subH.toFloat() / textureHeight.toFloat()

        val iw = w.toFloat() / sw
        val ih = h.toFloat() / sh
        val ix = x.toFloat() - iw * sx
        val iy = y.toFloat() - ih * sy

        nvgImagePattern(vg, ix, iy, iw, ih, 0f, image, 1f, nvgPaint)
        nvgBeginPath(vg)
        nvgRoundedRect(vg, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat() + .5f, radius.toFloat())
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    fun image(image: Image, x: Number, y: Number, w: Number, h: Number, radius: Number) {
        nvgImagePattern(vg, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), 0f, getImage(image), 1f, nvgPaint)
        nvgBeginPath(vg)
        nvgRoundedRect(vg, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat() + .5f, radius.toFloat())
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    fun image(path: String, x: Number, y: Number, w: Number, h: Number, radius: Number) {
        val existing = images.keys.find { it.location == path }
        image(existing ?: createImage(path), x, y, w, h, radius)
    }

    fun image(image: Image, x: Number, y: Number, w: Number, h: Number) {
        nvgImagePattern(vg, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), 0f, getImage(image), 1f, nvgPaint)
        nvgBeginPath(vg)
        nvgRect(vg, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat() + .5f)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    /**
     * Loads an image from the classpath, file system, or a URL and keeps it cached until [deleteImage].
     */
    fun createImage(resourcePath: String): Image {
        val image = images.keys.find { it.location == resourcePath } ?: Image(resourcePath)
        if (image.isSVG) images.getOrPut(image) { NVGImage(0, loadSVG(image)) }.count ++
        else images.getOrPut(image) { NVGImage(0, loadImage(image)) }.count ++
        return image
    }

    /**
     * Deletes an image created through [createImage].
     */
    fun deleteImage(image: Image) {
        val nvgImage = images[image] ?: return
        nvgImage.count --
        if (nvgImage.count == 0) {
            nvgDeleteImage(vg, nvgImage.nvg)
            images.remove(image)
        }
    }

    private fun getImage(image: Image): Int {
        return images[image]?.nvg ?: throw IllegalStateException("Image (${image.location}) doesn't exist")
    }

    private fun loadImage(image: Image): Int {
        val w = IntArray(1)
        val h = IntArray(1)
        val channels = IntArray(1)
        val source = memAlloc(image.bytes.size)

        try {
            source.put(image.bytes).flip()
            val decoded = stbi_load_from_memory(source, w, h, channels, 4)
                ?: throw NullPointerException("Failed to load image: ${image.location}")

            try {
                return nvgCreateImageRGBA(vg, w[0], h[0], 0, decoded)
            }
            finally {
                stbi_image_free(decoded)
            }
        }
        finally {
            memFree(source)
        }
    }

    private fun loadSVG(image: Image): Int {
        val vec = String(image.bytes, StandardCharsets.UTF_8)
        val svg = nsvgParse(vec, "px", 96f) ?: throw IllegalStateException("Failed to parse ${image.location}")

        val width = svg.width().toInt()
        val height = svg.height().toInt()
        require(width > 0 && height > 0) { "SVG (${image.location}) has invalid dimensions: ${width}x${height}" }
        val buffer = memAlloc(width * height * 4)

        try {
            val rasterizer = nsvgCreateRasterizer()
            nsvgRasterize(rasterizer, svg, 0f, 0f, 1f, buffer, width, height, width * 4)
            val nvgImage = nvgCreateImageRGBA(vg, width, height, 0, buffer)
            nsvgDeleteRasterizer(rasterizer)
            return nvgImage
        }
        finally {
            nsvgDelete(svg)
            memFree(buffer)
        }
    }

    fun color(color: Color) {
        nvgRGBA(color.red.toByte(), color.green.toByte(), color.blue.toByte(), color.alpha.toByte(), nvgColor)
    }

    fun color(color1: Color, color2: Color) {
        nvgRGBA(color1.red.toByte(), color1.green.toByte(), color1.blue.toByte(), color1.alpha.toByte(), nvgColor)
        nvgRGBA(color2.red.toByte(), color2.green.toByte(), color2.blue.toByte(), color2.alpha.toByte(), nvgColor2)
    }

    private fun lerp(a: Color, b: Color, t: Float): Color {
        val clamped = t.coerceIn(0f, 1f)
        fun channel(start: Int, end: Int): Int = (start + ((end - start) * clamped)).toInt().coerceIn(0, 255)
        return Color(
            channel(a.red, b.red),
            channel(a.green, b.green),
            channel(a.blue, b.blue),
            channel(a.alpha, b.alpha)
        )
    }

    private fun gradient(color1: Color, color2: Color, x: Number, y: Number, w: Number, h: Number, direction: Gradient) {
        color(color1, color2)
        when (direction) {
            Gradient.LEFT_RIGHT -> nvgLinearGradient(vg, x.toFloat(), y.toFloat(), x.toFloat() + w.toFloat(), y.toFloat(), nvgColor, nvgColor2, nvgPaint)
            Gradient.TOP_BOTTOM -> nvgLinearGradient(vg, x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat() + h.toFloat(), nvgColor, nvgColor2, nvgPaint)
        }
    }

    private fun getFontID(font: Font) = fontMap.getOrPut(font) {
        NVGFont(nvgCreateFontMem(vg, font.location, font.buffer, false), font.buffer)
    }.id

    private class Scissor(val previous: Scissor?, val x: Number, val y: Number, val maxX: Number, val maxY: Number) {
        fun applyScissor() {
            if (previous == null) nvgScissor(vg, x.toFloat(), y.toFloat(), maxX.toFloat() - x.toFloat(), maxY.toFloat() - y.toFloat())
            else {
                val x = max(x.toFloat(), previous.x.toFloat())
                val y = max(y.toFloat(), previous.y.toFloat())
                val width = max(0f, (min(maxX.toFloat(), previous.maxX.toFloat()) - x))
                val height = max(0f, (min(maxY.toFloat(), previous.maxY.toFloat()) - y))
                nvgScissor(vg, x, y, width, height)
            }
        }
    }

    private data class NVGImage(var count: Int, val nvg: Int)
    private data class NVGFont(val id: Int, val buffer: ByteBuffer)
}