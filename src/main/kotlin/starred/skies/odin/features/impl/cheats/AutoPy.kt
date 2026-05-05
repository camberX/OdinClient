package starred.skies.odin.features.impl.cheats

import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
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
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

object AutoPy : Module(
    name = "Auto PY",
    description = "Automatically handles PY in dungeons boss.",
    category = Skit.CHEATS
) {

    // ─── Settings ────────────────────────────────────────────────────────────

    private val classMode by SelectorSetting("Class", "Archer", arrayListOf("Archer", "Mage"),
        desc = "Choose PY behavior profile.")

    private var bossOnly by BooleanSetting("Boss Only", true,
        desc = "Only run while in dungeon boss.")

    // Mage-only settings
    private var startAtTick by NumberSetting("Start At Tick", 5.0, 0.0, 95.0, 1.0,
        desc = "Start walking when PY timer reaches this value.")
        .withDependency { !isArcherMode() }

    private var rightTicks by NumberSetting("Right Ticks", 3.0, 1.0, 30.0, 1.0,
        desc = "Ticks to walk right onto pad.")
        .withDependency { !isArcherMode() }

    private var leftTicks by NumberSetting("Left Ticks", 4.0, 1.0, 30.0, 1.0,
        desc = "Ticks to walk left off pad.")
        .withDependency { !isArcherMode() }

    private var postLeftTicks by NumberSetting("Post Left Ticks", 20.0, 1.0, 60.0, 1.0,
        desc = "Ticks to strafe left after post-rotate.")
        .withDependency { !isArcherMode() }

    private var useBlockStepOff by BooleanSetting("Use Block Step-Off", true,
        desc = "Step off when crusher block appears in target column.")
        .withDependency { !isArcherMode() }

    private var holdFallbackTicks by NumberSetting("Hold Fallback Ticks", 60.0, 1.0, 120.0, 1.0,
        desc = "Fallback hold duration if block check fails.")
        .withDependency { !isArcherMode() && useBlockStepOff }

    private var rotateAfterStepOff by BooleanSetting("Rotate After Step-Off", true,
        desc = "Rotate to 180/0 then strafe left.")
        .withDependency { !isArcherMode() }

    private var rotateDurationTicks by NumberSetting("Rotate Duration Ticks", 12.0, 1.0, 40.0, 1.0,
        desc = "How many ticks smooth rotation should take.")
        .withDependency { !isArcherMode() && rotateAfterStepOff }

    // Archer-only settings
    private var archerRotateDurationTicks by NumberSetting("Archer Rotate Ticks", 10.0, 1.0, 40.0, 1.0,
        desc = "How many ticks Archer aimlock smoothing should take.")
        .withDependency { isArcherMode() }

    private var archerReleaseOffsetTicks by NumberSetting("Archer Release Offset", 0.0, -10.0, 10.0, 1.0, unit = "t",
        desc = "Adjust Archer release timing by server ticks.")
        .withDependency { isArcherMode() }

    private var archerAimJitter by NumberSetting("Archer Aim Jitter", 0.45, 0.0, 2.5, 0.05, unit = "°",
        desc = "Random ±° on yaw and pitch per PY cycle (stable while aiming; 0 = perfect aim).")
        .withDependency { isArcherMode() }

    private var archerDebug by BooleanSetting("Archer Debug",
        desc = "Print Archer AutoPY stage trace in chat.")
        .withDependency { isArcherMode() }

    private var archerServerTickHud by BooleanSetting("Archer Server Tick HUD", true,
        desc = "Show server tick counter on crosshair/action bar after Storm P2 trigger.")
        .withDependency { isArcherMode() }

    private var archerWaypoints by BooleanSetting("Archer Waypoints", true,
        desc = "Render world waypoint for Archer aim position.")
        .withDependency { isArcherMode() }
    private var archerAimWaypointColor by ColorSetting("Archer Aim Waypoint Color", Colors.MINECRAFT_AQUA, true,
        desc = "Color of the Archer aim waypoint.")
        .withDependency { isArcherMode() && archerWaypoints }
    private var archerAimWaypointFilled by BooleanSetting("Archer Aim Waypoint Filled", false,
        desc = "Fill Archer aim waypoint box instead of outline only.")
        .withDependency { isArcherMode() }

    // ─── Constants ───────────────────────────────────────────────────────────

    private val STORM_PY_REGEX = Regex("^\\[BOSS] Storm: (ENERGY HEED MY CALL|THUNDER LET ME BE YOUR CATALYST)!\$")
    private val STORM_P2_START_REGEX = Regex("^\\[BOSS] Storm: Pathetic Maxor, just like expected\\.\$")

    // Crusher block scan range
    private const val CRUSHER_CONFIRM_TICKS = 2
    private const val CRUSHER_X = 101; private const val CRUSHER_Z = 68
    private const val CRUSHER_Y_TOP = 185; private const val CRUSHER_Y_BOTTOM = 181

    // Archer aim target marker
    private val ARCHER_AIM_POS = Vec3(100.5, 182.0, 64.0)

    // Archer timing
    private const val ARCHER_RELEASE_TICK = 74; private const val ARCHER_MIN_CHARGE_TICKS = 20
    private const val ARCHER_JUMP_DELAY_TICKS = 10; private const val ARCHER_JUMP_GAP_TICKS = 3
    private const val ARCHER_SERVER_START_TICK = 0; private const val ARCHER_SERVER_RELEASE_TICK = 142

    // ─── State ───────────────────────────────────────────────────────────────

    private enum class Phase { IDLE, RIGHT, HOLD, LEFT, POST_ROTATE }

    // Mage movement state
    private var phase = Phase.IDLE; private var ticksRemaining = 0; private var startedCycle = false
    private var pyTickTime = -1; private var manualMode = false; private var manualLeftTicks = 0
    private var manualPadMode = false; private var manualPadFallback = 12; private var postTicksRemaining = 0
    private var postStarted = false; private var rotateThread: Thread? = null; private var crusherSeenTicks = 0

    // Archer state
    private var archerCharging = false; private var archerReleasedThisCycle = false; private var archerReleasedThisTick = false
    private var archerPostJumpDelayTicks = 0; private var archerJumpsRemaining = 0; private var archerJumpCooldownTicks = 0
    private var archerAimlockCancelled = false; private var archerTerminatorSlot = -1; private var archerSwapToTerminatorPending = false
    private var archerServerTicking = false; private var archerServerTicks = -1; private var archerServerWindowStarted = false
    private var archerFullyChargedLastTick = false; private var archerLastAimNanos = 0L; private var archerLastAimUpdateMs = 0L
    private var archerLastSelectedSlot = -1; private var archerExpectedSlot = -1; private var archerSlotChangeGraceTicks = 0
    private var archerSpamLeftClick = false; private var archerNextLeftClickAt = 0L

    /** Resampled at the start of each Archer cycle; not re-rolled every frame. */
    private var archerAimJitterYaw = 0f
    private var archerAimJitterPitch = 0f

    private var debugSimulationActive = false

    // ─── Initialization / Event Handlers ─────────────────────────────────────

    init {
        on<ChatPacketEvent> { handleChat(value) }
        on<TickEvent.Server> { if (enabled && archerServerTicking) archerServerTicks++ }
        on<TickEvent.Start> { if (enabled) handleTick() }
        on<WorldEvent.Load> { releaseKeys(); resetAllState() }

        on<RenderEvent.Extract> {
            if (!enabled || !isArcherMode()) return@on
            if (archerWaypoints) {
                renderArcherWaypoint(ARCHER_AIM_POS.x, ARCHER_AIM_POS.y, ARCHER_AIM_POS.z, archerAimWaypointColor, archerAimWaypointFilled)
            }
            val p = mc.player ?: return@on
            if (!archerAimlockCancelled && shouldApplyArcherAimlock(p)) {
                val (yaw, pitch) = computeArcherAim(p)
                applyArcherAimlockSmooth(yaw, pitch)
                archerLastAimUpdateMs = System.currentTimeMillis()
            }
        }
    }

    override fun onDisable() {
        releaseKeys()
        releaseUse()
        resetAllState()
    }

    // ─── Event Handling ───────────────────────────────────────────────────────

    private fun handleChat(value: String) {
        if (!enabled) return

        // Hard-stop Archer cycle on phase transition (can fire before inBoss flips)
        if (isArcherMode() && isArcherCycleActive() &&
            (value.contains("Storm is enraged!") || value.contains("[BOSS] Goldor:"))) {
            archerSpamLeftClick = false
            stopArcherCycle("storm_phase_end")
            return
        }

        if (!DungeonUtils.inBoss) return

        when {
            isArcherMode() && value.matches(STORM_PY_REGEX) -> {
                archerServerTicking = true
                archerServerTicks = 0
                archerServerWindowStarted = true
                archerCharging = false
                archerReleasedThisCycle = false
                resampleArcherAimJitter()
                archerDebugLog("archer_lightning_trigger_matched serverTick=0")
            }
            !isArcherMode() && value.matches(STORM_P2_START_REGEX) -> {
                archerServerTicking = true
                archerServerTicks = 0
                archerServerWindowStarted = false
                archerCharging = false
                archerReleasedThisCycle = false
                archerDebugLog("storm_p2_start_matched serverTick=0")
            }
            !isArcherMode() && pyTickTime < 0 && value.matches(STORM_PY_REGEX) -> {
                pyTickTime = 95
                archerCharging = false
                archerReleasedThisCycle = false
                archerDebugLog("storm_trigger_matched tick=95")
            }
        }
    }

    private fun handleTick() {
        val outOfBoss = bossOnly && !DungeonUtils.inBoss && !debugSimulationActive
        if (outOfBoss) {
            val stateIsDirty = startedCycle || phase != Phase.IDLE ||
                    isArcherCycleActive() || pyTickTime >= 0 || manualMode || manualPadMode
            if (stateIsDirty) resetAllState()
            return
        }

        if (!isArcherMode() || debugSimulationActive) {
            if (pyTickTime >= 0) pyTickTime--
            if (pyTickTime < 0 && isArcherMode()) debugSimulationActive = false
        }

        if (isArcherMode()) {
            runArcherStep()
            return
        }

        when {
            manualPadMode -> runMovementStep(manual = false, padManual = true)
            manualMode    -> runMovementStep(manual = true,  padManual = false)
            !startedCycle && pyTickTime < 0 -> resetMovementState()
            !startedCycle && pyTickTime <= startAtTick.toInt() -> {
                startedCycle = true
                phase = Phase.RIGHT
                ticksRemaining = rightTicks.toInt()
                runMovementStep(manual = false, padManual = false)
            }
            phase != Phase.IDLE -> runMovementStep(manual = false, padManual = false)
        }
    }

    // ─── Movement (Mage) ─────────────────────────────────────────────────────

    private fun runMovementStep(manual: Boolean, padManual: Boolean) {
        when (phase) {
            Phase.IDLE -> Unit

            Phase.RIGHT -> {
                setKeys(right = true)
                if (--ticksRemaining <= 0) {
                    phase = if (padManual || (useBlockStepOff && !manual)) {
                        crusherSeenTicks = 0
                        ticksRemaining = if (padManual) manualPadFallback else holdFallbackTicks.toInt()
                        Phase.HOLD
                    } else {
                        ticksRemaining = if (manual) manualLeftTicks else leftTicks.toInt()
                        Phase.LEFT
                    }
                }
            }

            Phase.HOLD -> {
                releaseKeys()
                if ((padManual || useBlockStepOff) && isCrusherBlockPresent()) crusherSeenTicks++ else crusherSeenTicks = 0
                if (crusherSeenTicks >= CRUSHER_CONFIRM_TICKS || --ticksRemaining <= 0) {
                    crusherSeenTicks = 0
                    ticksRemaining = if (padManual || manual) manualLeftTicks else leftTicks.toInt()
                    phase = Phase.LEFT
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
                    if (isArcherMode()) findLeapHotbarSlot().takeIf { it >= 0 }?.let { p.inventory.setSelectedSlot(it) }
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

    // ─── Automation (Archer) ──────────────────────────────────────────────────

    private fun runArcherStep() {
        val p = mc.player ?: return

        trackHotbarChanges(p)
        updateServerTickHud(p)

        if (!isArcherCycleActive()) return

        handleManualInputGuard(p)

        if (debugSimulationActive && pyTickTime < 0) {
            cleanupArcherMidCycle()
            return
        }

        if (!debugSimulationActive) {
            if (!archerServerTicking) return
            if (!archerServerWindowStarted) {
                if (archerServerTicks < ARCHER_SERVER_START_TICK) return
                archerServerWindowStarted = true
                archerDebugLog("serverTick=$archerServerTicks lightning_window_armed")
            }
        } else {
            // Debug mode: gate on local tick
            val effectiveStart = (ARCHER_RELEASE_TICK + archerReleaseOffsetTicks.toInt()).coerceIn(0, 95) + ARCHER_MIN_CHARGE_TICKS
            if (pyTickTime > effectiveStart) {
                if (archerCharging) releaseUse()
                archerCharging = false
                return
            }
        }

        updateArcherAimlock(p)
        handleArcherRelease(p)

        if (archerReleasedThisCycle) runPostReleaseFlow(p)
    }

    private fun trackHotbarChanges(p: net.minecraft.client.player.LocalPlayer) {
        if (archerLastSelectedSlot < 0) archerLastSelectedSlot = p.inventory.selectedSlot
        if (archerSlotChangeGraceTicks > 0) archerSlotChangeGraceTicks--
        archerLastSelectedSlot = p.inventory.selectedSlot
    }

    private fun updateServerTickHud(p: net.minecraft.client.player.LocalPlayer) {
        if (!archerServerTickHud || !archerServerTicking) return
        val state = when {
            !archerServerWindowStarted && archerServerTicks < ARCHER_SERVER_START_TICK -> "waiting_start"
            !archerServerWindowStarted -> "waiting_arm"
            archerCharging            -> "charging"
            !archerReleasedThisCycle  -> "armed_wait"
            else                      -> "post_release"
        }
        p.displayClientMessage(Component.literal("PY Server Ticks: $archerServerTicks [$state]"), true)
    }

    private fun handleManualInputGuard(p: net.minecraft.client.player.LocalPlayer) {
        val executing = archerCharging || archerReleasedThisCycle

        val slotChanged = p.inventory.selectedSlot != archerLastSelectedSlot &&
                archerSlotChangeGraceTicks <= 0 &&
                p.inventory.selectedSlot != archerExpectedSlot
        val userInput = isAnyUserInputActive(ignoreUseInput = true)

        if (executing && (userInput || slotChanged)) {
            archerDebugLog(if (slotChanged) "manual_hotbar_change_detected -> cycle_end_reset" else "manual_input_detected -> cycle_end_reset")
            releaseKeys(); releaseUse(); resetAllState()
        }
    }

    private fun cleanupArcherMidCycle() {
        if (archerCharging) releaseUse()
        if (archerPostJumpDelayTicks > 0 || archerJumpsRemaining > 0) releaseKeys()
        archerCharging = false
        archerReleasedThisCycle = false
        archerPostJumpDelayTicks = 0
        archerJumpsRemaining = 0
        archerJumpCooldownTicks = 0
        archerSwapToTerminatorPending = false
        archerReleasedThisTick = false
        archerTerminatorSlot = -1
    }

    private fun updateArcherAimlock(p: net.minecraft.client.player.LocalPlayer) {
        val (targetYaw, targetPitch) = computeArcherAim(p)

        // Hard-lock: ignore mouse movement
        archerAimlockCancelled = false

        val fullyCharged = isLastBreathFullyCharged(p)
        if (fullyCharged && !archerFullyChargedLastTick) {
            applyArcherAimlock(targetYaw, targetPitch)
        }
        archerFullyChargedLastTick = fullyCharged

        if (!archerAimlockCancelled && shouldApplyArcherAimlock(p) &&
            System.currentTimeMillis() - archerLastAimUpdateMs > 100L) {
            applyArcherAimlockSmooth(targetYaw, targetPitch)
            archerLastAimUpdateMs = System.currentTimeMillis()
        }
    }

    private fun handleArcherRelease(p: net.minecraft.client.player.LocalPlayer) {
        val (targetYaw, targetPitch) = computeArcherAim(p)
        val shouldRelease = if (debugSimulationActive) {
            val effectiveReleaseTick = (ARCHER_RELEASE_TICK + archerReleaseOffsetTicks.toInt()).coerceIn(0, 95)
            pyTickTime <= effectiveReleaseTick
        } else {
            val liveReleaseTick = (ARCHER_SERVER_RELEASE_TICK + archerReleaseOffsetTicks.toInt()).coerceAtLeast(0)
            archerServerWindowStarted && archerServerTicks >= liveReleaseTick
        }

        if (!archerReleasedThisCycle && shouldRelease) {
            val heldName = p.mainHandItem.hoverName?.string?.noControlCodes?.lowercase() ?: ""
            val usingLb = p.isUsingItem && ("last breath" in heldName || "lastbreath" in heldName)
            if (!usingLb || !isLastBreathFullyCharged(p)) {
                archerDebugLog("release_waiting held='$heldName' using=${p.isUsingItem} full=${isLastBreathFullyCharged(p)} t=${if (debugSimulationActive) pyTickTime else archerServerTicks}")
                return
            }

            releaseUse()
            archerCharging = false
            archerReleasedThisCycle = true
            archerReleasedThisTick = true
            archerAimlockCancelled = false

            applyArcherAimlock(targetYaw, targetPitch)
            archerDebugLog("release_tick_reached t=${if (debugSimulationActive) pyTickTime else archerServerTicks}")

            archerTerminatorSlot = findTerminatorHotbarSlot()
            archerSwapToTerminatorPending = archerTerminatorSlot >= 0
            archerDebugLog("terminator_slot=$archerTerminatorSlot")

            archerPostJumpDelayTicks = ARCHER_JUMP_DELAY_TICKS
            archerJumpsRemaining = 3
            archerJumpCooldownTicks = 0
            archerSpamLeftClick = true
            archerNextLeftClickAt = System.currentTimeMillis()
        } else {
            archerCharging = false
        }
    }

    private fun runPostReleaseFlow(p: net.minecraft.client.player.LocalPlayer) {
        if (archerReleasedThisTick) {
            archerReleasedThisTick = false
            archerDebugLog("post_release_buffer_tick")
            return
        }

        // Swap to Terminator one tick after release
        if (archerSwapToTerminatorPending) {
            if (archerTerminatorSlot >= 0) setArcherSelectedSlot(p, archerTerminatorSlot)
            archerSwapToTerminatorPending = false
            archerDebugLog("swap_to_terminator slot=$archerTerminatorSlot")
        } else if (archerTerminatorSlot >= 0) {
            setArcherSelectedSlot(p, archerTerminatorSlot)
        }

        if (archerSpamLeftClick) tryArcherLeftClick15Cps()

        if (isUnderArcherTarget(p)) { releaseKeys(); return }

        setKeys(up = true)
        when {
            archerPostJumpDelayTicks > 0 -> archerPostJumpDelayTicks--
            archerJumpCooldownTicks > 0  -> archerJumpCooldownTicks--
            archerJumpsRemaining > 0     -> {
                setKeys(up = true, jump = true)
                archerDebugLog("jump_tap remaining_before=$archerJumpsRemaining")
                archerJumpsRemaining--
                archerJumpCooldownTicks = ARCHER_JUMP_GAP_TICKS
            }
        }
    }

    // ─── Aimlock ──────────────────────────────────────────────────────────────

    private fun computeArcherAim(p: net.minecraft.client.player.LocalPlayer): Pair<Float, Float> {
        val dx = ARCHER_AIM_POS.x - p.x
        val dz = ARCHER_AIM_POS.z - p.z
        val dy = ARCHER_AIM_POS.y - (p.y + p.eyeHeight.toDouble())
        val distXZ = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-6)
        val yaw   = normalizeYaw(Math.toDegrees(atan2(dz, dx)).toFloat() - 90f + archerAimJitterYaw)
        val pitch = (-Math.toDegrees(atan2(dy, distXZ))).toFloat() + archerAimJitterPitch
        return yaw to pitch.coerceIn(-90f, 90f)
    }

    private fun shouldApplyArcherAimlock(p: net.minecraft.client.player.LocalPlayer): Boolean {
        if (!isArcherActionActive()) return false
        if (archerReleasedThisCycle) return true
        val held = p.mainHandItem.hoverName?.string?.noControlCodes?.lowercase() ?: ""
        return p.isUsingItem && ("last breath" in held || "lastbreath" in held)
    }

    private fun applyArcherAimlock(yaw: Float, pitch: Float) {
        val p = mc.player ?: return
        applyRotation(p, yaw, pitch)
    }

    private fun applyArcherAimlockSmooth(yaw: Float, pitch: Float) {
        applyAimlockSmooth(yaw, pitch, archerRotateDurationTicks.toInt().coerceAtLeast(1))
    }

    /**
     * Exponential-decay smooth rotation toward [yaw]/[pitch].
     * Lower [rotateDurationTicks] = snappier. Runs every render frame.
     */
    private fun applyAimlockSmooth(yaw: Float, pitch: Float, rotateDurationTicks: Int) {
        val p = mc.player ?: return
        val now = System.nanoTime()
        val dt = if (archerLastAimNanos == 0L) 0.016
        else ((now - archerLastAimNanos).toDouble() / 1_000_000_000.0).coerceIn(0.001, 0.05)
        archerLastAimNanos = now

        val responsePerSec = (60.0 / rotateDurationTicks.coerceAtLeast(1).toDouble()).coerceIn(2.0, 60.0)
        val alpha = (1.0 - exp(-responsePerSec * dt)).toFloat().coerceIn(0.02f, 0.98f)

        val newYaw   = p.yRot + wrapDegrees(yaw - p.yRot) * alpha
        val newPitch = (p.xRot + (pitch - p.xRot) * alpha).coerceIn(-90f, 90f)
        applyRotation(p, newYaw, newPitch)
    }

    private fun applyRotation(p: net.minecraft.client.player.LocalPlayer, yaw: Float, pitch: Float) {
        p.yRotO = p.yRot;     p.xRotO   = p.xRot
        p.yHeadRotO = p.yHeadRot; p.yBodyRotO = p.yBodyRot
        p.yRot = yaw;  p.xRot = pitch
        p.yHeadRot = yaw; p.yBodyRot = yaw
    }

    // ─── Input Helpers ────────────────────────────────────────────────────────

    private fun setKeys(
        up: Boolean = false, down: Boolean = false,
        left: Boolean = false, right: Boolean = false,
        jump: Boolean = false, sneak: Boolean = false
    ) {
        mc.options.keyUp.setDown(up)
        mc.options.keyDown.setDown(down)
        mc.options.keyLeft.setDown(left)
        mc.options.keyRight.setDown(right)
        mc.options.keyJump.setDown(jump)
        mc.options.keyShift.setDown(sneak)
    }

    private fun releaseKeys() = setKeys()

    private fun releaseUse() {
        mc.options.keyUse.setDown(false)
        runCatching {
            val p = mc.player ?: return
            val conn = mc.connection ?: return
            conn.send(ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN))
            p.javaClass.methods.firstOrNull { it.name.equals("releaseUsingItem", true) && it.parameterCount == 0 }?.invoke(p)
            p.javaClass.methods.firstOrNull { it.name.equals("stopUsingItem",    true) && it.parameterCount == 0 }?.invoke(p)
        }
    }

    private fun isAnyUserInputActive(ignoreUseInput: Boolean = false): Boolean {
        val windowHandle = mc.window.handle()
        if (GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT)   == GLFW.GLFW_PRESS) return true
        if (GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS) return true
        if (!ignoreUseInput && GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS) return true

        val watched = listOf(
            mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
            mc.options.keyJump, mc.options.keyShift, mc.options.keySprint,
            mc.options.keyAttack, mc.options.keyUse
        )
        for (key in watched) {
            if (ignoreUseInput && key == mc.options.keyUse) continue
            val bound = (key as? KeyMappingAccessor)?.boundKey ?: continue
            val code = bound.value
            when {
                code > 7  -> if (InputConstants.isKeyDown(mc.window, code)) return true
                code >= 0 -> if (GLFW.glfwGetMouseButton(windowHandle, code) == GLFW.GLFW_PRESS) return true
            }
        }
        return false
    }

    private fun tryArcherLeftClick15Cps() {
        val now = System.currentTimeMillis()
        if (now < archerNextLeftClickAt) return
        archerNextLeftClickAt = now + ((1000.0 / 15.0) + (Math.random() - 0.5) * 60.0).toLong().coerceAtLeast(1L)
        leftClick()
    }

    // ─── Rotation Helpers ─────────────────────────────────────────────────────

    private fun beginSmoothRotate(targetYaw: Float, targetPitch: Float, durationMs: Long) {
        rotateThread?.interrupt()
        rotateThread = null
        val p = mc.player ?: return
        val startYaw   = normalizeYaw(p.yRot)
        val startPitch = normalizePitch(p.xRot)
        val tYaw       = normalizeYaw(targetYaw)
        val tPitch     = normalizePitch(targetPitch)
        if (abs(wrapDegrees(tYaw - startYaw)) <= 1f && abs(tPitch - startPitch) <= 1f) return

        val startTime = System.currentTimeMillis()
        rotateThread = Thread {
            try {
                while (true) {
                    val progress = if (durationMs <= 0L) 1.0
                    else ((System.currentTimeMillis() - startTime).toDouble() / durationMs).coerceAtMost(1.0)
                    val eased    = easeInOutCubic(progress.toFloat())
                    val newYaw   = interpolateYaw(startYaw, tYaw, eased)
                    val newPitch = lerp(startPitch, tPitch, eased).coerceIn(-90f, 90f)
                    mc.execute { mc.player?.let { applyRotation(it, newYaw, newPitch) } }
                    if (progress >= 1.0) break
                    Thread.sleep(1)
                }
            } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.start() }
    }

    private fun easeInOutCubic(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t
        else 1f - (-2f * t + 2f).let { it * it * it } / 2f

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

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

    private fun normalizeYaw(yaw: Float) = wrapDegrees(yaw)
    private fun normalizePitch(pitch: Float) = pitch.coerceIn(-90f, 90f)

    // ─── Inventory Helpers ────────────────────────────────────────────────────

    private fun findLeapHotbarSlot(): Int {
        val p = mc.player ?: return -1
        return (0..8).firstOrNull { slot ->
            val name = p.inventory.getItem(slot).takeIf { !it.isEmpty }?.hoverName?.string?.lowercase() ?: return@firstOrNull false
            "infinileap" in name || "leap" in name
        } ?: -1
    }

    private fun findTerminatorHotbarSlot(): Int {
        val p = mc.player ?: return -1
        return (0..8).firstOrNull { slot ->
            val name = p.inventory.getItem(slot).takeIf { !it.isEmpty }?.hoverName?.string?.noControlCodes?.lowercase() ?: return@firstOrNull false
            "terminator" in name
        } ?: -1
    }

    private fun setArcherSelectedSlot(p: net.minecraft.client.player.LocalPlayer, slot: Int) {
        if (slot !in 0..8) return
        if (p.inventory.selectedSlot != slot) p.inventory.setSelectedSlot(slot)
        archerExpectedSlot = slot
        archerSlotChangeGraceTicks = 2
        archerLastSelectedSlot = p.inventory.selectedSlot
    }

    // ─── World Queries ────────────────────────────────────────────────────────

    private fun isCrusherBlockPresent(): Boolean {
        val level = mc.level ?: return false
        return (CRUSHER_Y_TOP downTo CRUSHER_Y_BOTTOM).any { y ->
            val b = level.getBlockState(BlockPos(CRUSHER_X, y, CRUSHER_Z)).block
            b != Blocks.AIR && b != Blocks.CAVE_AIR && b != Blocks.VOID_AIR
        }
    }

    private fun isUnderArcherTarget(p: net.minecraft.client.player.LocalPlayer) =
        abs(p.x - ARCHER_AIM_POS.x) <= 5.0 && abs(p.z - ARCHER_AIM_POS.z) <= 5.0

    private fun isLastBreathFullyCharged(p: net.minecraft.client.player.LocalPlayer): Boolean {
        val held = p.mainHandItem.hoverName?.string?.noControlCodes?.lowercase() ?: ""
        if (!p.isUsingItem || "last breath" !in held && "lastbreath" !in held) return false
        val remaining = runCatching { p.useItemRemainingTicks }.getOrNull() ?: return false
        return (72000 - remaining) >= ARCHER_MIN_CHARGE_TICKS
    }

    private fun RenderEvent.Extract.renderArcherWaypoint(
        x: Double,
        y: Double,
        z: Double,
        color: com.odtheking.odin.utils.Color,
        filled: Boolean,
    ) {
        // drawStyledBox style codes: 0=Filled, 1=Outline, 2=Filled Outline
        val renderStyle = if (filled) 2 else 1
        drawStyledBox(AABB(x - 0.5, y, z - 0.5, x + 0.5, y + 1.8, z + 0.5), color, renderStyle, false)
    }

    // ─── State Management ─────────────────────────────────────────────────────

    private fun resetMovementState() {
        phase = Phase.IDLE
        ticksRemaining = 0
        startedCycle = false
        postStarted = false
        rotateThread?.interrupt()
        rotateThread = null
    }

    private fun resetAllState() {
        releaseKeys()
        resetMovementState()
        pyTickTime = -1
        manualMode = false
        manualLeftTicks = 0
        manualPadMode = false
        manualPadFallback = 12
        postTicksRemaining = 0
        crusherSeenTicks = 0
        archerCharging = false
        archerReleasedThisCycle = false
        archerPostJumpDelayTicks = 0
        archerJumpsRemaining = 0
        archerJumpCooldownTicks = 0
        archerAimlockCancelled = false
        archerTerminatorSlot = -1
        archerSwapToTerminatorPending = false
        archerReleasedThisTick = false
        archerServerTicking = false
        archerServerTicks = -1
        archerServerWindowStarted = false
        archerFullyChargedLastTick = false
        archerLastAimNanos = 0L
        archerLastAimUpdateMs = 0L
        archerLastSelectedSlot = -1
        archerExpectedSlot = -1
        archerSlotChangeGraceTicks = 0
        archerSpamLeftClick = false
        archerNextLeftClickAt = 0L
        debugSimulationActive = false
        archerAimJitterYaw = 0f
        archerAimJitterPitch = 0f
    }

    private fun stopArcherCycle(reason: String) {
        archerDebugLog("hard_stop reason=$reason")
        mc.options.keySprint.setDown(false)
        mc.options.keyAttack.setDown(false)
        releaseKeys()
        releaseUse()
        resetAllState()
    }

    private fun resampleArcherAimJitter() {
        val max = archerAimJitter.toFloat()
        archerAimJitterYaw   = if (max <= 0f) 0f else (Math.random().toFloat() * 2f - 1f) * max
        archerAimJitterPitch = if (max <= 0f) 0f else (Math.random().toFloat() * 2f - 1f) * max
    }

    // ─── Mode Helpers ─────────────────────────────────────────────────────────

    private fun classModeLabel(): String = when (val v = classMode) {
        is String -> v
        is Number -> if (v.toInt() == 1) "Mage" else "Archer"
        else -> v.toString()
    }

    private fun isArcherMode() = classModeLabel().equals("Archer", ignoreCase = true)
    private fun isArcherCycleActive() = pyTickTime >= 0 || archerCharging || archerReleasedThisCycle || archerServerTicking
    private fun isArcherActionActive() = archerServerWindowStarted || archerCharging || archerReleasedThisCycle || debugSimulationActive

    private fun archerDebugLog(msg: String) {
        if (archerDebug) modMessage("[AutoPY Archer Debug] $msg")
    }

    // ─── GUI Bridge ───────────────────────────────────────────────────────────

    fun guiGetClassMode() = classModeLabel()
    fun guiGetBossOnly() = bossOnly
    fun guiSetBossOnly(v: Boolean) { bossOnly = v }
    fun guiGetRightTicks() = rightTicks
    fun guiSetRightTicks(v: Double) { rightTicks = v.coerceIn(1.0, 30.0) }
    fun guiGetLeftTicks() = leftTicks
    fun guiSetLeftTicks(v: Double) { leftTicks = v.coerceIn(1.0, 30.0) }
    fun guiGetUseBlockStepOff() = useBlockStepOff
    fun guiSetUseBlockStepOff(v: Boolean) { useBlockStepOff = v }
    fun debugCurrentPyTick() = pyTickTime
    fun debugCurrentClassMode() = classModeLabel()

    /**
     * Mirrors [withDependency] checks for [ClickGuiScreen], which uses reflection and bypasses
     * Odin's dependency API.
     */
    fun guiIsSettingFieldVisible(fieldName: String): Boolean {
        val base = fieldName.removeSuffix("\$delegate")
        val archer = isArcherMode()
        return when (base) {
            "classMode", "bossOnly" -> true
            "startAtTick", "rightTicks", "leftTicks", "postLeftTicks",
            "useBlockStepOff", "rotateAfterStepOff" -> !archer
            "holdFallbackTicks"    -> !archer && useBlockStepOff
            "rotateDurationTicks"  -> !archer && rotateAfterStepOff
            "archerRotateDurationTicks", "archerReleaseOffsetTicks", "archerAimJitter",
            "archerDebug", "archerServerTickHud", "archerWaypoints", "archerAimWaypointColor", "archerAimWaypointFilled" -> archer
            else -> true
        }
    }

    // ─── Debug / Testing ──────────────────────────────────────────────────────

    fun debugStartSimulation(startTick: Int = 95) {
        releaseKeys(); releaseUse(); resetAllState()
        debugSimulationActive = true
        pyTickTime = startTick.coerceIn(0, 95)
        if (isArcherMode()) resampleArcherAimJitter()
        archerDebugLog("sim_start tick=$pyTickTime")
    }

    fun debugSetSimulationTick(tick: Int) {
        pyTickTime = tick.coerceIn(-1, 95)
        debugSimulationActive = pyTickTime >= 0
        archerDebugLog("sim_set tick=$pyTickTime")
    }

    fun debugStopSimulation() {
        releaseKeys(); releaseUse()
        debugSimulationActive = false
        resetAllState()
        archerDebugLog("sim_stop")
    }

    fun runManualStrafe(right: Int, left: Int) {
        releaseKeys(); resetMovementState()
        manualPadMode = false
        manualMode = true
        manualLeftTicks = left.coerceAtLeast(1)
        phase = Phase.RIGHT
        ticksRemaining = right.coerceAtLeast(1)
    }

    fun runManualPadStrafe(right: Int, left: Int, fallbackHold: Int = 12) {
        releaseKeys(); resetMovementState()
        manualMode = false
        manualPadMode = true
        manualLeftTicks = left.coerceAtLeast(1)
        manualPadFallback = fallbackHold.coerceAtLeast(1)
        phase = Phase.RIGHT
        ticksRemaining = right.coerceAtLeast(1)
    }
}
