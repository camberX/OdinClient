package starred.skies.odin.features.impl.cheats

import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.noControlCodes
import com.odtheking.odin.utils.render.textDim
import com.odtheking.odin.utils.sendCommand
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.world.inventory.ClickType
import org.lwjgl.glfw.GLFW
import starred.skies.odin.mixin.accessors.KeyMappingAccessor
import starred.skies.odin.utils.Skit

object CheaterWardrobe : Module(
    name = "Cheater Wardrobe",
    description = "Automatically swaps wardrobe slots without interrupting movement or showing the GUI.",
    category = Skit.CHEATS
) {
    private val slot1 = KeybindSetting("Slot 1", GLFW.GLFW_KEY_UNKNOWN)
    private val slot2 = KeybindSetting("Slot 2", GLFW.GLFW_KEY_UNKNOWN)
    private val slot3 = KeybindSetting("Slot 3", GLFW.GLFW_KEY_UNKNOWN)
    private val slot4 = KeybindSetting("Slot 4", GLFW.GLFW_KEY_UNKNOWN)
    private val slot5 = KeybindSetting("Slot 5", GLFW.GLFW_KEY_UNKNOWN)
    private val slot6 = KeybindSetting("Slot 6", GLFW.GLFW_KEY_UNKNOWN)
    private val slot7 = KeybindSetting("Slot 7", GLFW.GLFW_KEY_UNKNOWN)
    private val slot8 = KeybindSetting("Slot 8", GLFW.GLFW_KEY_UNKNOWN)
    private val slot9 = KeybindSetting("Slot 9", GLFW.GLFW_KEY_UNKNOWN)

    private val slots = listOf(slot1, slot2, slot3, slot4, slot5, slot6, slot7, slot8, slot9)
    private val wasPressed = BooleanArray(9)

    // Hypixel only checks for inventory walk on container clicks.
    // Therefore, by only moving when not clicking, we can bypass the check!
    // Enabling this bypasses that safety: the swap may fire while moving, which Hypixel CAN detect.
    private val enabledWhileMoving by BooleanSetting(
        "Enabled While Moving", false,
        desc = "Use at your own risk!"
    )

    private var pendingSlotIndex = -1
    private var currentSlot = -1
    private var isSwapping = false
    private var currentContainerId = -1
    private var startTime = 0L

    private val hud by HUD("Equip Message", "Shows the current wardrobe slot being equipped.", true) { example ->
        if (!example && !isSwapping) return@HUD 0 to 0
        textDim("§fEquipping wardrobe slot §b#${if (example) 1 else currentSlot}", 0, 0, Colors.WHITE)
    }

    init {
        slots.forEach { this.registerSetting(it) }

        on<TickEvent.Start> {
            val player = mc.player
            if (mc.screen != null || player == null) {
                for (i in slots.indices) wasPressed[i] = slots[i].value.isPressed()
                return@on
            }

            for (i in slots.indices) {
                val currentlyPressed = slots[i].value.isPressed()
                if (currentlyPressed && !wasPressed[i] && !isSwapping) {
                    // If "Enabled While Moving" is off, wait until the player is stationary.
                    // This mirrors the InventoryWalk bypass: Hypixel only flags inventory clicks
                    // that occur during movement, so we avoid triggering while moving.
                    val isMoving = isPlayerMoving()
                    if (!enabledWhileMoving && isMoving) {
                        wasPressed[i] = currentlyPressed
                        continue
                    }
                    pendingSlotIndex = 36 + i
                    currentSlot = i + 1
                    isSwapping = true
                    currentContainerId = -1
                    startTime = System.currentTimeMillis()
                    sendCommand("wd")
                }
                wasPressed[i] = currentlyPressed
            }
        }

        onReceive<ClientboundOpenScreenPacket> {
            if (isSwapping && title.string.noControlCodes.contains("Wardrobe")) {
                currentContainerId = containerId
                val player = mc.player ?: return@onReceive
                // Manually update the player's container menu to the new wardrobe menu
                // this ensures mc.gameMode.handleInventoryMouseClick works even without a visible screen
                mc.execute {
                    player.containerMenu = type.create(containerId, player.inventory)
                }
                it.cancel() // Block the GUI from ever being set in Minecraft's screen property
            }
        }

        on<TickEvent.Start> {
            if (!isSwapping) return@on

            if (System.currentTimeMillis() - startTime > 2000) {
                resetState()
                return@on
            }

            val player = mc.player ?: return@on
            val menu = player.containerMenu
            
            // Check if the current menu is the one we're waiting for
            if (menu != null && menu.containerId == currentContainerId) {
                val slot = menu.slots.getOrNull(pendingSlotIndex)
                // Wait for content to sync from server
                if (slot != null && !slot.item.isEmpty) {
                    // Equipment check
                    if (!slot.item.itemId.contains("lime_dye", true)) {
                        mc.gameMode?.handleInventoryMouseClick(menu.containerId, pendingSlotIndex, 0, ClickType.PICKUP, player)
                    }
                    
                    // Close the background menu on both client and server
                    player.closeContainer()
                    resetState()
                }
            }
        }
    }

    private fun resetState() {
        isSwapping = false
        currentContainerId = -1
        pendingSlotIndex = -1
        currentSlot = -1
    }

    private fun isPlayerMoving(): Boolean {
        val w = mc.window
        return listOf(
            mc.options.keyUp,
            mc.options.keyLeft,
            mc.options.keyRight,
            mc.options.keyDown,
            mc.options.keyJump,
            mc.options.keySprint,
            mc.options.keyShift
        ).any { key ->
            val v = (key as KeyMappingAccessor).boundKey.value
            if (v > 7) InputConstants.isKeyDown(w, v)
            else GLFW.glfwGetMouseButton(w.handle(), v) == GLFW.GLFW_PRESS
        }
    }

    private fun InputConstants.Key.isPressed(): Boolean {
        val value = this.value
        if (value == -1) return false
        return if (value > 7) InputConstants.isKeyDown(mc.window, value)
        else GLFW.glfwGetMouseButton(mc.window.handle(), value) == GLFW.GLFW_PRESS
    }
}
