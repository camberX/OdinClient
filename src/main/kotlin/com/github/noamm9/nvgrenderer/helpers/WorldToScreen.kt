package com.github.noamm9.nvgrenderer.helpers

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

object WorldToScreen {
    /**
     * Screen-space projection result in GUI coordinates.
     */
    data class ScreenPoint(
        val x: Float,
        val y: Float,
        val depth: Float,
        val onScreen: Boolean
    )

    @JvmStatic
    fun project(pos: Vec3) = project(pos.x, pos.y, pos.z)

    @JvmStatic
    fun project(pos: BlockPos) = project(pos.x, pos.y, pos.z)

    @JvmStatic
    fun project(x: Number, y: Number, z: Number): ScreenPoint? {
        val mc = Minecraft.getInstance()
        mc.level ?: return null
        val window = mc.window
        val projected = mc.gameRenderer.projectPointToScreen(Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
        val ndcX = projected.x.toFloat()
        val ndcY = projected.y.toFloat()
        val ndcZ = projected.z.toFloat()

        val guiWidth = window.screenWidth.toFloat()
        val guiHeight = window.screenHeight.toFloat()

        return ScreenPoint(
            x = (ndcX * 0.5f + 0.5f) * guiWidth,
            y = (1f - (ndcY * 0.5f + 0.5f)) * guiHeight,
            depth = ndcZ,
            onScreen = ndcX in - 1f .. 1f && ndcY in - 1f .. 1f && ndcZ in - 1f .. 1f
        )
    }
}
