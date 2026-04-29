package starred.skies.odin.features.impl.cheats

import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.ChatPacketEvent
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.WorldEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.noControlCodes
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.render.drawText
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.KeyMapping
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import starred.skies.odin.mixin.accessors.KeyMappingAccessor
import starred.skies.odin.utils.Skit
import starred.skies.odin.utils.leftClick
import starred.skies.odin.utils.rightClick
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

object AutoPy : Module(
    name = "Auto PY",
    description = "Automatically handles PY in dungeons boss.",
    category = Skit.CHEATS
) {
    private val classMode by SelectorSetting(
        "Class",
        "Archer",
        arrayListOf("Archer", "Mage"),
        desc = "Choose PY behavior profile."
    )
    private var bossOnly by BooleanSetting("Boss Only", true, desc = "Only run while in dungeon boss.")
    private var startAtTick by NumberSetting("Start At Tick", 5.0, 0.0, 95.0, 1.0, desc = "Start walking when PY timer reaches this value.")
    private var rightTicks by NumberSetting("Right Ticks", 3.0, 1.0, 30.0, 1.0, desc = "Ticks to walk right onto pad.")
    private var leftTicks by NumberSetting("Left Ticks", 4.0, 1.0, 30.0, 1.0, desc = "Ticks to walk left off pad.")
    private var postLeftTicks by NumberSetting("Post Left Ticks", 20.0, 1.0, 60.0, 1.0, desc = "Ticks to strafe left after post-rotate.")
    private var useBlockStepOff by BooleanSetting("Use Block Step-Off", true, desc = "Step off when crusher block appears in target column.")
    private var holdFallbackTicks by NumberSetting("Hold Fallback Ticks", 60.0, 1.0, 120.0, 1.0, desc = "Fallback hold duration if block check fails.").withDependency { useBlockStepOff }
    private var rotateAfterStepOff by BooleanSetting("Rotate After Step-Off", true, desc = "Rotate to 180/0 then strafe left.")
    private var rotateDurationTicks by NumberSetting("Rotate Duration Ticks", 12.0, 1.0, 40.0, 1.0, desc = "How many ticks smooth rotation should take.").withDependency { rotateAfterStepOff }
    private var archerRotateDurationTicks by NumberSetting(
        "Archer Rotate Ticks",
        10.0,
        1.0,
        40.0,
        1.0,
        desc = "How many ticks Archer aimlock smoothing should take."
    ).withDependency { isArcherMode() }
    private var archerStartAtTick by NumberSetting(
        "Archer Start Tick",
        86.0,
        0.0,
        95.0,
        1.0,
        desc = "Archer only starts aimlock/charge at or below this tick (after lightning phase)."
    ).withDependency { isArcherMode() }
    private var archerDebug by BooleanSetting(
        "Archer Debug",
        desc = "Print Archer AutoPY stage trace in chat."
    ).withDependency { isArcherMode() }
    private var archerServerTickHud by BooleanSetting(
        "Archer Server Tick HUD",
        true,
        desc = "Show server tick counter on crosshair/action bar after Storm P2 trigger."
    ).withDependency { isArcherMode() }
    private var archerWaypoints by BooleanSetting(
        "Archer Waypoints",
        true,
        desc = "Render world waypoints for Archer start/aim positions."
    ).withDependency { isArcherMode() }

    private val stormPyRegex = Regex("^\\[BOSS] Storm: (ENERGY HEED MY CALL|THUNDER LET ME BE YOUR CATALYST)!$")
    private val stormP2StartRegex = Regex("^\\[BOSS] Storm: Pathetic Maxor, just like expected\\.$")

    private enum class Phase { IDLE, RIGHT, HOLD, LEFT, POST_ROTATE }

    private var phase = Phase.IDLE
    private var ticksRemaining = 0
    private var startedCycle = false
    private var pyTickTime = -1
    private var pyTriggered = false
    private var manualMode = false
    private var manualLeftTicks = 0
    private var manualPadMode = false
    private var manualPadFallback = 12
    private var postTicksRemaining = 0
    private var postStarted = false
    private var rotateThread: Thread? = null
    private var crusherSeenTicksVar = 0
    private var archerCharging = false
    private var archerReleasedThisCycle = false
    private var archerPostJumpDelayTicks = 0
    private var archerJumpsRemaining = 0
    private var archerJumpCooldownTicks = 0
    private var archerAimlockCancelled = false
    private var archerAimlockApplied = false
    private var archerTerminatorSlot = -1
    private var archerSwapToTerminatorPending = false
    private var archerReleasedThisTick = false
    private var archerDropDelayTicks = -1
    private var archerDropTriggered = false
    private var archerLastMouseX = Double.NaN
    private var archerLastMouseY = Double.NaN
    private var archerServerTicking = false
    private var archerServerTicks = -1
    private var archerServerWindowStarted = false
    private var archerMissingLbLogged = false
    private var archerLastSelectedSlot = -1
    private var archerExpectedSlot = -1
    private var archerSlotChangeGraceTicks = 0
    private var debugSimulationActive = false

    private val crusherConfirmTicks = 2
    private val crusherX = 101
    private val crusherZ = 68
    private val crusherYTop = 185
    private val crusherYBottom = 181
    private val archerAimX = 101.0
    private val archerAimY = 181.0
    private val archerAimZ = 64.0
    private val archerReleaseTick = 74
    private val archerMinChargeTicks = 20
    private val archerJumpDelayTicks = 10 // 500ms at 20 TPS
    private val archerJumpGapTicks = 3
    private val archerServerStartTick = 0
    private val archerServerReleaseTick = 142
    private val archerStartCheckX = 87.7
    private val archerStartCheckY = 169.0
    private val archerStartCheckZ = 75.3

    private fun classModeLabel(): String = when (val v = classMode) {
        is String -> v
        is Number -> if (v.toInt() == 1) "Mage" else "Archer"
        else -> v.toString()
    }

    private fun isArcherMode(): Boolean = classModeLabel().equals("Archer", ignoreCase = true)
    private fun isArcherCycleActive(): Boolean =
        pyTickTime >= 0 || archerCharging || archerReleasedThisCycle || archerDropDelayTicks >= 0 || archerServerTicking
    private fun isArcherActionActive(): Boolean =
        archerServerWindowStarted || archerCharging || archerReleasedThisCycle || archerDropDelayTicks >= 0 || debugSimulationActive

    private fun archerDebugLog(msg: String) {
        if (!archerDebug) return
        modMessage("[AutoPY Archer Debug] $msg")
    }

    // GUI bridge (used by SkitGuiScreen)
    fun guiGetClassMode(): String = classModeLabel()
    fun guiGetBossOnly(): Boolean = bossOnly
    fun guiSetBossOnly(v: Boolean) { bossOnly = v }
    fun guiGetRightTicks(): Double = rightTicks
    fun guiSetRightTicks(v: Double) { rightTicks = v.coerceIn(1.0, 30.0) }
    fun guiGetLeftTicks(): Double = leftTicks
    fun guiSetLeftTicks(v: Double) { leftTicks = v.coerceIn(1.0, 30.0) }
    fun guiGetUseBlockStepOff(): Boolean = useBlockStepOff
    fun guiSetUseBlockStepOff(v: Boolean) { useBlockStepOff = v }
    fun debugCurrentPyTick(): Int = pyTickTime
    fun debugCurrentClassMode(): String = classModeLabel()

    init {
        on<ChatPacketEvent> {
            if (!DungeonUtils.inBoss) return@on
            if (isArcherMode() && value.matches(stormPyRegex)) {
                archerServerTicking = true
                archerServerTicks = 0
                archerServerWindowStarted = true
                archerCharging = false
                archerReleasedThisCycle = false
                archerDropDelayTicks = -1
                archerDropTriggered = false
                archerDebugLog("archer_lightning_trigger_matched serverTick=0")
            }
            if (!isArcherMode() && value.matches(stormP2StartRegex)) {
                archerServerTicking = true
                archerServerTicks = 0
                archerServerWindowStarted = false
                archerCharging = false
                archerReleasedThisCycle = false
                archerDebugLog("storm_p2_start_matched serverTick=0")
            }
            if (!isArcherMode() && !pyTriggered && value.matches(stormPyRegex)) {
                pyTriggered = true
                pyTickTime = 95
                archerCharging = false
                archerReleasedThisCycle = false
                archerDebugLog("storm_trigger_matched tick=95")
            }
        }

        on<TickEvent.Server> {
            if (!archerServerTicking) return@on
            archerServerTicks++
        }

        on<TickEvent.Start> {
            if (isArcherMode() && archerDebug) {
                val p = mc.player
                if (p != null) {
                    val inside = isWithinStartZone(p, archerStartCheckX, archerStartCheckZ)
                    if (inside) {
                        val dx = abs(p.x - archerStartCheckX)
                        val dz = abs(p.z - archerStartCheckZ)
                        archerDebugLog("start_zone_inside dx=%.2f dz=%.2f".format(dx, dz))
                    }
                }
            }
            if (bossOnly && !DungeonUtils.inBoss && !debugSimulationActive) { resetAllState(); return@on }

            if (!isArcherMode() || debugSimulationActive) {
                if (pyTickTime >= 0) pyTickTime--
                if (pyTickTime < 0) {
                    pyTriggered = false
                    if (isArcherMode()) debugSimulationActive = false
                }
            }

            if (isArcherMode()) {
                runArcherStep()
                return@on
            }

            if (manualPadMode) { runMovementStep(manual = false, padManual = true); return@on }
            if (manualMode) { runMovementStep(manual = true, padManual = false); return@on }

            if (!startedCycle && pyTickTime < 0) { resetMovementState(); return@on }

            if (!startedCycle && pyTickTime <= startAtTick.toInt()) {
                startedCycle = true
                phase = Phase.RIGHT
                ticksRemaining = rightTicks.toInt()
            }

            if (phase != Phase.IDLE) runMovementStep(manual = false, padManual = false)
        }

        on<WorldEvent.Load> {
            releaseKeys()
            resetAllState()
        }

        on<RenderEvent.Extract> {
            if (!isArcherMode() || !archerWaypoints) return@on
            renderArcherWaypoint("Archer Start", archerStartCheckX, archerStartCheckY, archerStartCheckZ, Colors.MINECRAFT_GREEN)
            renderArcherWaypoint("Archer Aim", archerAimX, archerAimY, archerAimZ, Colors.MINECRAFT_AQUA)
        }
    }

    override fun onDisable() {
        releaseKeys()
        releaseUse()
        resetAllState()
    }

    fun debugStartSimulation(startTick: Int = 95) {
        releaseKeys()
        releaseUse()
        resetAllState()
        debugSimulationActive = true
        pyTriggered = true
        pyTickTime = startTick.coerceIn(0, 95)
        archerDebugLog("sim_start tick=$pyTickTime")
    }

    fun debugSetSimulationTick(tick: Int) {
        pyTickTime = tick.coerceIn(-1, 95)
        pyTriggered = pyTickTime >= 0
        debugSimulationActive = pyTickTime >= 0
        archerDebugLog("sim_set tick=$pyTickTime")
    }

    fun debugStopSimulation() {
        releaseKeys()
        releaseUse()
        debugSimulationActive = false
        resetAllState()
        archerDebugLog("sim_stop")
    }

    fun runManualStrafe(right: Int, left: Int) {
        releaseKeys()
        resetMovementState()
        manualPadMode = false
        manualMode = true
        manualLeftTicks = left.coerceAtLeast(1)
        phase = Phase.RIGHT
        ticksRemaining = right.coerceAtLeast(1)
    }

    fun runManualPadStrafe(right: Int, left: Int, fallbackHold: Int = 12) {
        releaseKeys()
        resetMovementState()
        manualMode = false
        manualPadMode = true
        manualLeftTicks = left.coerceAtLeast(1)
        manualPadFallback = fallbackHold.coerceAtLeast(1)
        phase = Phase.RIGHT
        ticksRemaining = right.coerceAtLeast(1)
    }

    private fun runMovementStep(manual: Boolean, padManual: Boolean) {
        when (phase) {
            Phase.IDLE -> Unit

            Phase.RIGHT -> {
                setKeys(right = true)
                if (--ticksRemaining <= 0) {
                    if (padManual || (useBlockStepOff && !manual)) {
                        phase = Phase.HOLD
                        ticksRemaining = if (padManual) manualPadFallback else holdFallbackTicks.toInt()
                        crusherSeenTicksVar = 0
                    } else {
                        phase = Phase.LEFT
                        ticksRemaining = if (manual) manualLeftTicks else leftTicks.toInt()
                    }
                }
            }

            Phase.HOLD -> {
                releaseKeys()
                if ((padManual || useBlockStepOff) && isCrusherBlockPresentInRange()) crusherSeenTicksVar++ else crusherSeenTicksVar = 0
                val leave = crusherSeenTicksVar >= crusherConfirmTicks || --ticksRemaining <= 0
                if (leave) {
                    phase = Phase.LEFT
                    ticksRemaining = if (padManual || manual) manualLeftTicks else leftTicks.toInt()
                    crusherSeenTicksVar = 0
                }
            }

            Phase.LEFT -> {
                setKeys(left = true)
                if (--ticksRemaining <= 0) {
                    releaseKeys()
                    manualMode = false
                    manualPadMode = false
                    if (rotateAfterStepOff) {
                        phase = Phase.POST_ROTATE
                        postTicksRemaining = postLeftTicks.toInt()
                        postStarted = false
                        beginSmoothRotate(180f, 0f, rotateDurationTicks.toInt() * 50L)
                    } else {
                        phase = Phase.IDLE
                    }
                }
            }

            Phase.POST_ROTATE -> {
                val p = mc.player ?: run { phase = Phase.IDLE; return }
                if (!postStarted) {
                    postStarted = true
                    // Class profile selector: Archer keeps leap swap, Mage skips it.
                    if (isArcherMode()) {
                        findLeapHotbarSlot().takeIf { it >= 0 }?.let { p.inventory.setSelectedSlot(it) }
                    }
                }
                setKeys(left = true)
                if (--postTicksRemaining <= 0) {
                    releaseKeys()
                    rotateThread?.interrupt()
                    rotateThread = null
                    phase = Phase.IDLE
                }
            }
        }
    }

    private fun runArcherStep() {
        val p = mc.player ?: return
        val currentSlot = p.inventory.selectedSlot
        if (archerLastSelectedSlot < 0) archerLastSelectedSlot = currentSlot
        val slotChangedThisTick = currentSlot != archerLastSelectedSlot
        if (archerSlotChangeGraceTicks > 0) archerSlotChangeGraceTicks--
        val userHotbarSlotChanged = slotChangedThisTick &&
            archerSlotChangeGraceTicks <= 0 &&
            currentSlot != archerExpectedSlot
        archerLastSelectedSlot = currentSlot

        if (archerServerTickHud && archerServerTicking) {
            val state = when {
                !archerServerWindowStarted && archerServerTicks < archerServerStartTick -> "waiting_start"
                !archerServerWindowStarted -> "waiting_arm"
                archerCharging -> "charging"
                !archerReleasedThisCycle -> "armed_wait"
                else -> "post_release"
            }
            p.displayClientMessage(Component.literal("PY Server Ticks: $archerServerTicks [$state]"), true)
        }
        // Completely idle outside an active Archer cycle (prevents non-P2 interference/spam).
        if (!isArcherCycleActive()) return

        // Input reset is only meaningful once we're actively executing (charging/post-release),
        // not at the exact arming moment, otherwise the cycle can die before aimlock is visible.
        val executingArcherFlow = archerCharging || archerReleasedThisCycle || archerDropDelayTicks >= 0
        if (executingArcherFlow && (isAnyUserInputActive() || userHotbarSlotChanged)) {
            if (userHotbarSlotChanged) {
                archerDebugLog("manual_hotbar_change_detected -> cycle_end_reset")
            } else {
                archerDebugLog("manual_input_detected -> cycle_end_reset")
            }
            releaseKeys()
            releaseUse()
            resetAllState()
            return
        }
        // Local PY timer is only authoritative in debug simulation mode.
        // In live Archer mode we run purely from server ticks (650/689), so do not early-return here.
        if (debugSimulationActive && pyTickTime < 0) {
            if (archerCharging) releaseUse()
            if (archerPostJumpDelayTicks > 0 || archerJumpsRemaining > 0) {
                releaseKeys()
            }
            archerCharging = false
            archerReleasedThisCycle = false
            archerPostJumpDelayTicks = 0
            archerJumpsRemaining = 0
            archerJumpCooldownTicks = 0
            archerSwapToTerminatorPending = false
            archerReleasedThisTick = false
            archerTerminatorSlot = -1
            return
        }

        if (!debugSimulationActive) {
            if (!archerServerTicking) return
            if (!archerServerWindowStarted) {
                if (archerServerTicks < archerServerStartTick) return
                archerServerWindowStarted = true
                archerDebugLog("serverTick=$archerServerTicks lightning_window_armed")
            }
        } else {
            // Debug simulation fallback: old local tick gating.
            val effectiveStartTick = maxOf(archerStartAtTick.toInt(), archerReleaseTick + archerMinChargeTicks)
            if (pyTickTime > effectiveStartTick) {
                if (archerCharging) releaseUse()
                archerCharging = false
                return
            }
        }
        if (archerDropDelayTicks < 0 && !archerDropTriggered) {
            archerDropDelayTicks = 20 // 1 second at 20 TPS, measured from end of lightning window.
            archerDebugLog("drop_timer_started delay=${archerDropDelayTicks}t")
        }
        if (!archerDropTriggered && archerDropDelayTicks >= 0) {
            if (archerDropDelayTicks == 0) {
                pressDropKeyOnce()
                archerDropTriggered = true
                archerDebugLog("drop_key_pressed")
            } else {
                archerDropDelayTicks--
            }
        }

        val (targetYaw, targetPitch) = computeArcherAim(p)
        // Cancel aimlock on any user mouse input (cursor movement), not angle deviation.
        val window = mc.window.handle()
        val mx = DoubleArray(1)
        val my = DoubleArray(1)
        GLFW.glfwGetCursorPos(window, mx, my)
        if (!archerAimlockCancelled && archerAimlockApplied) {
            if (!archerLastMouseX.isNaN() && !archerLastMouseY.isNaN()) {
                if (mx[0] != archerLastMouseX || my[0] != archerLastMouseY) {
                    archerAimlockCancelled = true
                }
            }
        }
        archerLastMouseX = mx[0]
        archerLastMouseY = my[0]
        if (!archerAimlockCancelled) {
            applyArcherAimlockSmooth(targetYaw, targetPitch)
            archerAimlockApplied = true
        }
        if (!archerReleasedThisCycle) {
            val lbSlot = findLastBreathHotbarSlot()
            val useSlot = when {
                lbSlot >= 0 -> lbSlot
                isHeldBow(p) -> p.inventory.selectedSlot
                else -> -1
            }
            if (useSlot >= 0) {
                setArcherSelectedSlot(p, useSlot)
                archerMissingLbLogged = false
            } else {
                if (!archerMissingLbLogged) {
                    archerDebugLog("last_breath_not_found (and not holding bow)")
                    archerMissingLbLogged = true
                }
                if (archerCharging) releaseUse()
                archerCharging = false
                return
            }
        }

        val shouldCharge = if (debugSimulationActive) {
            pyTickTime > archerReleaseTick
        } else {
            archerServerWindowStarted && archerServerTicks in archerServerStartTick until archerServerReleaseTick
        }
        val shouldReleaseNow = if (debugSimulationActive) {
            pyTickTime == archerReleaseTick
        } else {
            archerServerWindowStarted && archerServerTicks == archerServerReleaseTick
        }

        if (!archerReleasedThisCycle && shouldCharge) {
            if (!archerCharging) {
                // Explicitly start use once, then hold.
                rightClick()
                archerDebugLog("charge_started t=${if (debugSimulationActive) pyTickTime else archerServerTicks} item=${p.mainHandItem.item}")
            }
            mc.options.keyUse.setDown(true)
            archerCharging = true
        } else if (!archerReleasedThisCycle && shouldReleaseNow) {
            releaseUse()
            archerCharging = false
            archerReleasedThisCycle = true
            archerReleasedThisTick = true
            archerDebugLog("release_tick_reached t=${if (debugSimulationActive) pyTickTime else archerServerTicks}")
            archerTerminatorSlot = findTerminatorHotbarSlot()
            archerSwapToTerminatorPending = archerTerminatorSlot >= 0
            archerDebugLog("terminator_slot=${archerTerminatorSlot}")
            archerPostJumpDelayTicks = archerJumpDelayTicks
            archerJumpsRemaining = 3
            archerJumpCooldownTicks = 0
        }

        if (archerReleasedThisCycle) {
            if (archerReleasedThisTick) {
                // Do nothing else on the exact release tick to avoid cancelling the shot.
                archerReleasedThisTick = false
                archerDebugLog("post_release_buffer_tick")
                return
            }
            if (archerSwapToTerminatorPending) {
                // Swap one tick after release so Last Breath shot is not cancelled by instant item switch.
                if (archerTerminatorSlot >= 0) setArcherSelectedSlot(p, archerTerminatorSlot)
                archerSwapToTerminatorPending = false
                archerDebugLog("swap_to_terminator slot=$archerTerminatorSlot")
            } else if (archerTerminatorSlot >= 0) {
                setArcherSelectedSlot(p, archerTerminatorSlot)
            }
            if (isUnderArcherTarget(p)) {
                releaseKeys()
                return
            }
            leftClick()
            setKeys(up = true)
            if (archerPostJumpDelayTicks > 0) {
                archerPostJumpDelayTicks--
            } else {
                if (archerJumpCooldownTicks > 0) {
                    archerJumpCooldownTicks--
                    setKeys(up = true)
                } else if (archerJumpsRemaining > 0) {
                    setKeys(up = true, jump = true)
                    archerDebugLog("jump_tap remaining_before=${archerJumpsRemaining}")
                    archerJumpsRemaining--
                    archerJumpCooldownTicks = archerJumpGapTicks
                } else {
                    setKeys(up = true)
                }
            }
        }
    }

    private fun isAnyUserInputActive(): Boolean {
        val windowHandle = mc.window.handle()
        val window = mc.window

        // Mouse buttons
        if (GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS) return true
        if (GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS) return true
        if (GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS) return true

        // Common movement/combat inputs (physical state, not programmatic keybind state)
        val watched = listOf(
            mc.options.keyUp,
            mc.options.keyDown,
            mc.options.keyLeft,
            mc.options.keyRight,
            mc.options.keyJump,
            mc.options.keyShift,
            mc.options.keySprint,
            mc.options.keyAttack,
            mc.options.keyUse
        )
        for (key in watched) {
            val bound = (key as? KeyMappingAccessor)?.boundKey ?: continue
            val code = bound.value
            if (code > 7) {
                if (InputConstants.isKeyDown(window, code)) return true
            } else if (code >= 0) {
                if (GLFW.glfwGetMouseButton(windowHandle, code) == GLFW.GLFW_PRESS) return true
            }
        }
        return false
    }

    private fun computeArcherAim(p: net.minecraft.client.player.LocalPlayer): Pair<Float, Float> {
        val dx = archerAimX - p.x
        val dz = archerAimZ - p.z
        val dy = archerAimY - (p.y + p.eyeHeight.toDouble())
        val distXZ = sqrt(dx * dx + dz * dz).coerceAtLeast(1.0E-6)
        val yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        val pitch = (-Math.toDegrees(atan2(dy, distXZ))).toFloat().coerceIn(-90f, 90f)
        return yaw to pitch
    }

    private fun applyArcherAimlock(yaw: Float, pitch: Float) {
        val p = mc.player ?: return

        p.yRotO = p.yRot; p.xRotO = p.xRot
        p.yHeadRotO = p.yHeadRot; p.yBodyRotO = p.yBodyRot
        p.yRot = yaw; p.xRot = pitch
        p.yHeadRot = yaw; p.yBodyRot = yaw
    }

    private fun applyArcherAimlockSmooth(yaw: Float, pitch: Float) {
        val p = mc.player ?: return
        val tickSetting = archerRotateDurationTicks.toInt().coerceAtLeast(1)
        val speedScale = (40f / tickSetting).coerceIn(1f, 12f)
        val maxYawStep = (3.5f * speedScale).coerceIn(6f, 28f)
        val maxPitchStep = (2.8f * speedScale).coerceIn(5f, 24f)

        val yawDelta = wrapDegrees(yaw - p.yRot)
        val pitchDelta = (pitch - p.xRot)
        val yawStep = yawDelta.coerceIn(-maxYawStep, maxYawStep)
        val pitchStep = pitchDelta.coerceIn(-maxPitchStep, maxPitchStep)

        // High-rate, low-jitter smoothing.
        val newYaw = p.yRot + yawStep * 0.92f
        val newPitch = (p.xRot + pitchStep * 0.92f).coerceIn(-90f, 90f)

        p.yRotO = p.yRot; p.xRotO = p.xRot
        p.yHeadRotO = p.yHeadRot; p.yBodyRotO = p.yBodyRot
        p.yRot = newYaw; p.xRot = newPitch
        p.yHeadRot = newYaw; p.yBodyRot = newYaw
    }

    private fun isUnderArcherTarget(p: net.minecraft.client.player.LocalPlayer): Boolean {
        return abs(p.x - archerAimX) <= 3.0 && abs(p.z - archerAimZ) <= 3.0
    }

    private fun isWithinStartZone(p: net.minecraft.client.player.LocalPlayer, x: Double, z: Double): Boolean {
        // Start gate should be X/Z only; Y varies with edge blocks/slabs.
        return abs(p.x - x) <= 3.5 && abs(p.z - z) <= 3.5
    }

    private fun RenderEvent.Extract.renderArcherWaypoint(name: String, x: Double, y: Double, z: Double, color: com.odtheking.odin.utils.Color) {
        val box = AABB(x - 0.5, y, z - 0.5, x + 0.5, y + 1.8, z + 0.5)
        drawStyledBox(box, color, 1, false) // 1 = Outline
        val textPos = Vec3(x, y + 2.1, z)
        drawText(name, textPos, 1.0f, false)
    }

    private fun setKeys(up: Boolean = false, down: Boolean = false, left: Boolean = false, right: Boolean = false, jump: Boolean = false) {
        mc.options.keyUp.setDown(up)
        mc.options.keyDown.setDown(down)
        mc.options.keyLeft.setDown(left)
        mc.options.keyRight.setDown(right)
        mc.options.keyJump.setDown(jump)
    }

    private fun releaseKeys() = setKeys()

    private fun releaseUse() {
        mc.options.keyUse.setDown(false)
        runCatching {
            val p = mc.player ?: return
            val conn = mc.connection ?: return
            conn.send(
                ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                    BlockPos.ZERO,
                    Direction.DOWN
                )
            )
            archerDebugLog("release_use_item_packet_sent")
            p.javaClass.methods.firstOrNull {
                it.name.equals("releaseUsingItem", true) && it.parameterCount == 0
            }?.invoke(p)
            archerDebugLog("releaseUsingItem_called")
            p.javaClass.methods.firstOrNull {
                it.name.equals("stopUsingItem", true) && it.parameterCount == 0
            }?.invoke(p)
            archerDebugLog("stopUsingItem_called")
        }
    }

    private fun pressDropKeyOnce() {
        val key = (mc.options.keyDrop as? KeyMappingAccessor)?.boundKey ?: return
        KeyMapping.set(key, true)
        KeyMapping.click(key)
        KeyMapping.set(key, false)
    }

    private fun beginSmoothRotate(targetYaw: Float, targetPitch: Float, durationMs: Long) {
        rotateThread?.interrupt()
        rotateThread = null
        val p = mc.player ?: return
        val startYaw = normalizeYaw(p.yRot)
        val startPitch = normalizePitch(p.xRot)
        val tYaw = normalizeYaw(targetYaw)
        val tPitch = normalizePitch(targetPitch)
        if (abs(wrapDegrees(tYaw - startYaw)) <= 1f && abs(tPitch - startPitch) <= 1f) return
        val startTime = System.currentTimeMillis()
        rotateThread = Thread {
            try {
                while (true) {
                    val progress = if (durationMs <= 0L) 1.0 else min((System.currentTimeMillis() - startTime).toDouble() / durationMs, 1.0)
                    val eased = easeInOutCubic(progress.toFloat())
                    val newYaw = interpolateYaw(startYaw, tYaw, eased)
                    val newPitch = lerp(startPitch, tPitch, eased).coerceIn(-90f, 90f)
                    mc.execute {
                        mc.player?.let {
                            it.yRotO = it.yRot; it.xRotO = it.xRot
                            it.yHeadRotO = it.yHeadRot; it.yBodyRotO = it.yBodyRot
                            it.yRot = newYaw; it.xRot = newPitch
                            it.yHeadRot = newYaw; it.yBodyRot = newYaw
                        }
                    }
                    if (progress >= 1.0) break
                    Thread.sleep(1)
                }
            } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.start() }
    }

    private fun findLeapHotbarSlot(): Int {
        val p = mc.player ?: return -1
        for (slot in 0..8) {
            val name = p.inventory.getItem(slot).takeIf { !it.isEmpty }?.hoverName?.string?.lowercase() ?: continue
            if ("infinileap" in name || "leap" in name) return slot
        }
        return -1
    }

    private fun findLastBreathHotbarSlot(): Int {
        val p = mc.player ?: return -1
        for (slot in 0..8) {
            val stack = p.inventory.getItem(slot).takeIf { !it.isEmpty } ?: continue
            val name = stack.hoverName?.string?.noControlCodes?.lowercase() ?: ""
            if ("last breath" in name || "lastbreath" in name) return slot
        }
        return -1
    }

    private fun findTerminatorHotbarSlot(): Int {
        val p = mc.player ?: return -1
        for (slot in 0..8) {
            val stack = p.inventory.getItem(slot).takeIf { !it.isEmpty } ?: continue
            val name = stack.hoverName?.string?.noControlCodes?.lowercase() ?: ""
            if ("terminator" in name) return slot
        }
        return -1
    }

    private fun isHeldBow(p: net.minecraft.client.player.LocalPlayer): Boolean {
        val stack = p.mainHandItem
        if (stack == null || stack.isEmpty) return false
        val itemName = stack.item.toString().lowercase()
        return "bow" in itemName
    }

    private fun setArcherSelectedSlot(p: net.minecraft.client.player.LocalPlayer, slot: Int) {
        if (slot !in 0..8) return
        if (p.inventory.selectedSlot != slot) {
            p.inventory.setSelectedSlot(slot)
        }
        archerExpectedSlot = slot
        archerSlotChangeGraceTicks = 2
        archerLastSelectedSlot = p.inventory.selectedSlot
    }

    private fun isCrusherBlockPresentInRange(): Boolean {
        val level = mc.level ?: return false
        return (crusherYTop downTo crusherYBottom).any {
            val b = level.getBlockState(BlockPos(crusherX, it, crusherZ)).block
            b != Blocks.AIR && b != Blocks.CAVE_AIR && b != Blocks.VOID_AIR
        }
    }

    private fun resetMovementState() {
        phase = Phase.IDLE
        ticksRemaining = 0
        startedCycle = false
        postStarted = false
        rotateThread?.interrupt()
        rotateThread = null
    }

    private fun resetAllState() {
        resetMovementState()
        pyTickTime = -1
        pyTriggered = false
        manualMode = false
        manualLeftTicks = 0
        manualPadMode = false
        manualPadFallback = 12
        postTicksRemaining = 0
        crusherSeenTicksVar = 0
        archerCharging = false
        archerReleasedThisCycle = false
        archerPostJumpDelayTicks = 0
        archerJumpsRemaining = 0
        archerJumpCooldownTicks = 0
        archerAimlockCancelled = false
        archerAimlockApplied = false
        archerTerminatorSlot = -1
        archerSwapToTerminatorPending = false
        archerReleasedThisTick = false
        archerDropDelayTicks = -1
        archerDropTriggered = false
        archerLastMouseX = Double.NaN
        archerLastMouseY = Double.NaN
        archerServerTicking = false
        archerServerTicks = -1
        archerServerWindowStarted = false
        archerMissingLbLogged = false
        archerLastSelectedSlot = -1
        archerExpectedSlot = -1
        archerSlotChangeGraceTicks = 0
        debugSimulationActive = false
    }

    private fun easeInOutCubic(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t else 1f - ((-2f * t + 2f).let { it * it * it } / 2f)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun interpolateYaw(from: Float, to: Float, t: Float): Float {
        var delta = (to - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return from + delta * t
    }

    private fun wrapDegrees(a: Float): Float {
        var r = a % 360f
        if (r >= 180f) r -= 360f
        if (r < -180f) r += 360f
        return r
    }

    private fun normalizeYaw(yaw: Float): Float = wrapDegrees(yaw)
    private fun normalizePitch(pitch: Float): Float = pitch.coerceIn(-90f, 90f)
}