package com.github.noamm9.nvgrenderer.nvg

import java.nio.ByteBuffer
import java.nio.ByteOrder

class Font(val location: String) {
    val buffer by lazy {
        val bytes = ResourceLoader.read(location)
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).flip() as ByteBuffer
    }

    override fun hashCode() = location.hashCode()
    override fun equals(other: Any?) = other is Font && location == other.location
}
