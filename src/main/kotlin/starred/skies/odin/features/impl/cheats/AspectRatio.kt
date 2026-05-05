package starred.skies.odin.features.impl.cheats

import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.features.Module
import starred.skies.odin.utils.Skit

object AspectRatio : Module(
    name = "Aspect Ratio",
    description = "Force a custom 3D camera aspect ratio.",
    category = Skit.CHEATS
) {
    private val force by BooleanSetting("Force", true, desc = "Enable the custom aspect ratio override.")
    private val percent by NumberSetting(
        "Ratio",
        100.0,
        50.0,
        200.0,
        1.0,
        unit = "%",
        desc = "100% = normal. Lower gives taller view; higher gives wider/stretched view."
    ).withDependency { force }

    @JvmStatic
    fun isAspectRatioActive(): Boolean = enabled && force

    @JvmStatic
    fun aspectRatioMultiplier(): Float = (percent / 100.0).toFloat().coerceIn(0.1f, 4.0f)
}
