package starred.skies.odin.features.impl.cheats

import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import starred.skies.odin.utils.Skit
import kotlin.math.sqrt

object SingleplayerSpeedSim : Module(
    name = "SP Speed Sim",
    description = "Singleplayer-only movement simulation for Hypixel-like speed values.",
    category = Skit.CHEATS
) {
    // Old CT baseline: 400 speed uses 0.50000000745 for move/ability speed.
    private const val CT_400_SPEED_VALUE = 0.50000000745
    private const val TARGET_400_BPS = 12.26

    private val debugSpeedReadout by BooleanSetting(
        "Debug Speed Readout",
        desc = "Show live speed readout in action bar for calibration."
    )
    private val debugIntervalTicks by NumberSetting(
        "Debug Interval",
        5.0,
        1.0,
        20.0,
        1.0,
        unit = "t",
        desc = "How often to refresh debug readout."
    ).withDependency { debugSpeedReadout }

    private val skyblockSpeed by NumberSetting(
        "Skyblock Speed",
        400.0,
        100.0,
        500.0,
        1.0,
        desc = "Simulated Skyblock speed value (100 base, 400 = 4x)."
    )

    private var debugTickCounter = 0
    private var cachedBaseMoveSpeed: Double? = null
    private var cachedWalkingSpeed: Float? = null
    private var cachedFlyingSpeed: Float? = null

    init {
        on<TickEvent.End> {
            val player = mc.player as? LocalPlayer ?: return@on

            if (debugSpeedReadout) {
                debugTickCounter++
                if (debugTickCounter >= debugIntervalTicks.toInt().coerceAtLeast(1)) {
                    debugTickCounter = 0
                    val vx = player.deltaMovement.x
                    val vz = player.deltaMovement.z
                    val bpt = sqrt(vx * vx + vz * vz) // blocks per tick
                    val bps = bpt * 20.0
                    val estimatedStat = (bps / TARGET_400_BPS) * 400.0
                    player.displayClientMessage(
                        Component.literal("Speed Debug | bps=%.2f bpt=%.3f estStat=%.0f".format(bps, bpt, estimatedStat)),
                        true
                    )
                }
            }

            if (!mc.hasSingleplayerServer()) return@on
            if (mc.screen != null) return@on
            if (player.isSpectator || player.isPassenger || player.isFallFlying || player.isInWater || player.isInLava) return@on
            applyCtStyleSpeed(player, skyblockSpeed)
        }
    }

    override fun onDisable() {
        val player = mc.player as? LocalPlayer
        if (player != null) {
            restoreOriginalSpeeds(player)
        }
        super.onDisable()
    }

    private fun applyCtStyleSpeed(player: LocalPlayer, speedStat: Double) {
        val ctStyleValue = (speedStat / 400.0) * CT_400_SPEED_VALUE
        setMovementAttribute(player, ctStyleValue)
        setAbilitySpeeds(player, ctStyleValue.toFloat())
    }

    private fun setMovementAttribute(player: LocalPlayer, baseValue: Double) {
        runCatching {
            val attributesClass = Class.forName("net.minecraft.world.entity.ai.attributes.Attributes")
            val movementSpeedKey = attributesClass.fields.firstOrNull { it.name.equals("MOVEMENT_SPEED", true) }?.get(null) ?: return
            val getAttributeMethod = player.javaClass.methods.firstOrNull {
                it.name.equals("getAttribute", true) && it.parameterCount == 1
            } ?: return
            val attributeInstance = getAttributeMethod.invoke(player, movementSpeedKey) ?: return
            val attrClass = attributeInstance.javaClass
            val getBase = attrClass.methods.firstOrNull { it.name.equals("getBaseValue", true) && it.parameterCount == 0 }
            val setBase = attrClass.methods.firstOrNull { it.name.equals("setBaseValue", true) && it.parameterCount == 1 } ?: return
            if (cachedBaseMoveSpeed == null && getBase != null) {
                cachedBaseMoveSpeed = (getBase.invoke(attributeInstance) as? Number)?.toDouble()
            }
            setBase.invoke(attributeInstance, baseValue)
        }
    }

    private fun setAbilitySpeeds(player: LocalPlayer, speed: Float) {
        runCatching {
            val abilities = player.javaClass.methods.firstOrNull {
                it.name.equals("getAbilities", true) && it.parameterCount == 0
            }?.invoke(player) ?: return

            val aClass = abilities.javaClass
            val getWalk = aClass.methods.firstOrNull { it.name.equals("getWalkingSpeed", true) && it.parameterCount == 0 }
            val getFly = aClass.methods.firstOrNull { it.name.equals("getFlyingSpeed", true) && it.parameterCount == 0 }
            val setWalk = aClass.methods.firstOrNull { it.name.equals("setWalkingSpeed", true) && it.parameterCount == 1 }
            val setFly = aClass.methods.firstOrNull { it.name.equals("setFlyingSpeed", true) && it.parameterCount == 1 }

            if (cachedWalkingSpeed == null) cachedWalkingSpeed = (getWalk?.invoke(abilities) as? Number)?.toFloat()
            if (cachedFlyingSpeed == null) cachedFlyingSpeed = (getFly?.invoke(abilities) as? Number)?.toFloat()

            if (setWalk != null) setWalk.invoke(abilities, speed)
            if (setFly != null) setFly.invoke(abilities, speed)
        }
    }

    private fun restoreOriginalSpeeds(player: LocalPlayer) {
        cachedBaseMoveSpeed?.let { setMovementAttribute(player, it) }
        runCatching {
            val abilities = player.javaClass.methods.firstOrNull {
                it.name.equals("getAbilities", true) && it.parameterCount == 0
            }?.invoke(player) ?: return
            val aClass = abilities.javaClass
            val setWalk = aClass.methods.firstOrNull { it.name.equals("setWalkingSpeed", true) && it.parameterCount == 1 }
            val setFly = aClass.methods.firstOrNull { it.name.equals("setFlyingSpeed", true) && it.parameterCount == 1 }
            cachedWalkingSpeed?.let { if (setWalk != null) setWalk.invoke(abilities, it) }
            cachedFlyingSpeed?.let { if (setFly != null) setFly.invoke(abilities, it) }
        }
    }
}
