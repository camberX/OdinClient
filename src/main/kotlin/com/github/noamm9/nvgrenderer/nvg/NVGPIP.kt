package com.github.noamm9.nvgrenderer.nvg

import com.mojang.blaze3d.opengl.GlConst
import com.mojang.blaze3d.opengl.GlDevice
import com.mojang.blaze3d.opengl.GlStateManager
import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState
import net.minecraft.client.renderer.MultiBufferSource
import org.joml.Matrix3x2f
import org.lwjgl.opengl.GL33C

class NVGPIP(buffer: MultiBufferSource.BufferSource): PictureInPictureRenderer<NVGPIP.NVGRenderState>(buffer) {
    private var lastRenderAtNanos = System.nanoTime()

    override fun textureIsReadyToBlit(state: NVGRenderState) = System.nanoTime() - lastRenderAtNanos < FRAME_INTERVAL_NANOS
    override fun getTranslateY(height: Int, windowScaleFactor: Int) = height / 2f
    override fun getRenderStateClass() = NVGRenderState::class.java
    override fun getTextureLabel(): String = "nvgrenderer"

    override fun renderToTexture(state: NVGRenderState, poseStack: PoseStack) {
        val window = Minecraft.getInstance().window
        if (window.isIconified) return

        val colorTex = RenderSystem.outputColorTextureOverride ?: return
        val width = colorTex.getWidth(0).takeIf { it > 0 } ?: return
        val height = colorTex.getHeight(0).takeIf { it > 0 } ?: return
        val stateAccess = (RenderSystem.getDevice() as? GlDevice)?.directStateAccess() ?: return
        val depthTex = (RenderSystem.outputDepthTextureOverride?.texture() as? GlTexture) ?: return
        val fbo = (colorTex.texture() as? GlTexture)?.getFbo(stateAccess, depthTex) ?: return
        val rawWidth = width.toFloat()
        val rawHeight = height.toFloat()
        val guiWidth = window.guiScaledWidth.toFloat().coerceAtLeast(1f)
        val dpr = (rawWidth / guiWidth).takeIf { it.isFinite() && it > 0f } ?: 1f

        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fbo)
        GlStateManager._viewport(0, 0, width, height)
        GL33C.glBindSampler(0, 0)

        NVG.beginFrame(rawWidth, rawHeight, dpr)
        NVG.push()
        NVG.transform(state.poseMatrix)
        state.callback.run()
        NVG.pop()
        NVG.endFrame()

        GlStateManager._disableDepthTest()
        GlStateManager._disableCull()
        GlStateManager._enableBlend()
        GlStateManager._blendFuncSeparate(770, 771, 1, 0)

        lastRenderAtNanos = System.nanoTime()
    }

    data class NVGRenderState(
        private val width: Int,
        private val height: Int,
        val poseMatrix: Matrix3x2f,
        private val scissor: ScreenRectangle?,
        private val bounds: ScreenRectangle?,
        val callback: Runnable
    ): PictureInPictureRenderState {
        override fun x0() = 0
        override fun y0() = 0
        override fun x1() = width
        override fun y1() = height
        override fun scissorArea() = scissor
        override fun bounds() = bounds
        override fun scale() = 1f
    }

    companion object {
        private const val TARGET_FPS = 144L
        private const val FRAME_INTERVAL_NANOS = 1_000_000_000L / TARGET_FPS

        /**
         * Draws NanoVG content inside the current GuiGraphics transform and scissor state.
         */
        @JvmStatic
        fun GuiGraphics.drawNVG(callback: Runnable) {
            val window = Minecraft.getInstance().window
            if (window.isIconified || window.guiScaledWidth <= 0 || window.guiScaledHeight <= 0) return

            val scissor = scissorStack.peek()
            val pose = Matrix3x2f(pose())
            val screenRect = ScreenRectangle(0, 0, guiWidth(), guiHeight()).transformMaxBounds(pose)
            if (screenRect.width <= 0 || screenRect.height <= 0) return

            val bounds = scissor?.intersection(screenRect) ?: screenRect
            if (bounds.width <= 0 || bounds.height <= 0) return

            val state = NVGRenderState(guiWidth(), guiHeight(), pose, scissor, bounds, callback)
            guiRenderState.submitPicturesInPictureState(state)
        }
    }
}
