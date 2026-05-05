package com.github.noamm9.nvgrenderer.nvg

class Image internal constructor(
    val location: String,
    var isSVG: Boolean = location.endsWith(".svg", true),
) {
    val bytes by lazy { ResourceLoader.read(location) }

    override fun equals(other: Any?) = other is Image && location == other.location
    override fun hashCode() = location.hashCode()
}