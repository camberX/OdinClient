package starred.skies.odin.ui

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.NativeImage
import com.odtheking.odin.features.Module
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW
import starred.skies.odin.OdinClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.math.round

class SkitGuiScreen : Screen(Component.literal("Odin Client")) {
    companion object {
        private const val GUI_SETTINGS_FILE_NAME = "odinclient_gui.json"
        private const val DEFAULT_TEXT_SCALE = 0.58f
        private const val DEFAULT_ACCENT_COLOR = 0xFFD000FF.toInt()
        private const val DEFAULT_SHOW_RAINBOW = true
        private const val DEFAULT_ACCENT_BAR_SKEET_FADE = true
        private const val DEFAULT_DENSE_LAYOUT = false
        private const val DEFAULT_CATEGORY_SIZE = 45
        private const val DEFAULT_MENU_OPEN_KEY_CODE = GLFW.GLFW_KEY_UNKNOWN
        @Volatile
        private var sharedMenuOpenKeyCode: Int = DEFAULT_MENU_OPEN_KEY_CODE

        fun menuOpenKeyCode(): Int = sharedMenuOpenKeyCode

        fun loadSharedMenuOpenKeyCode() {
            runCatching {
                val path = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(GUI_SETTINGS_FILE_NAME)
                if (!Files.exists(path)) {
                    sharedMenuOpenKeyCode = DEFAULT_MENU_OPEN_KEY_CODE
                    return
                }
                val raw = Files.readString(path)
                val match = Regex("\"menuOpenKeyCode\"\\s*:\\s*(-?\\d+)").find(raw)
                sharedMenuOpenKeyCode = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: DEFAULT_MENU_OPEN_KEY_CODE
            }.onFailure {
                sharedMenuOpenKeyCode = DEFAULT_MENU_OPEN_KEY_CODE
            }
        }
    }
    private data class SettingHit(
        val module: Module,
        val fieldName: String,
        val settingObj: Any,
        val kind: String,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int
    )
    private data class DropdownOptionHit(
        val module: Module,
        val fieldName: String,
        val settingObj: Any,
        val option: String,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int
    )
    private data class DropdownPanelHit(
        val key: String,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val totalOptions: Int,
        val visibleOptions: Int
    )
    private data class SettingsPanelScrollHit(
        val key: String,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val maxScrollPx: Int
    )
    private data class GuiSettingHit(
        val kind: String,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int
    )
    private data class KeybindListenTarget(
        val module: Module,
        val fieldName: String,
        val settingObj: Any
    )
    private var frameX = 0
    private var frameY = 0
    private val frameW = 320
    private val frameH = 270

    private val railW = 42
    private val contentPad = 5
    private var scrollOffset = 0
    private val rowHeight = 12
    private var moduleRowGap = 7
    private val moduleColGap = 8
    private var categoryGap = 4
    private var categorySize = 45

    private val mainContentTopInset = 9
    private val mainContentBottomInset = 8
    private val mainContentRowTopInset = 6

    private var selectedCategory = "General"
    private val modules = OdinClient.getGuiModules()
    private val categories get() = buildCategories()
    private var settingsModule: Module? = null
    private var prevRightMouseDown = false
    private var editingColorSetting: Any? = null
    private var editingColorModule: Module? = null
    private var editingColorFieldName: String? = null
    private var editHue = 0f
    private var editSat = 1f
    private var editVal = 1f
    private var editAlpha = 1f
    private var skeetTextScale = DEFAULT_TEXT_SCALE
    private var guiAccentColor = DEFAULT_ACCENT_COLOR
    private var guiShowRainbowBar = DEFAULT_SHOW_RAINBOW
    private var guiAccentBarSkeetFade = DEFAULT_ACCENT_BAR_SKEET_FADE
    private var guiDenseLayout = DEFAULT_DENSE_LAYOUT
    private var editingGuiAccent = false
    private var prevLeftMouseDown = false
    private var activeSliderHit: SettingHit? = null
    private var activeSliderLastTarget: Double? = null
    private val settingHits = mutableListOf<SettingHit>()
    private val dropdownOptionHits = mutableListOf<DropdownOptionHit>()
    private val dropdownPanelHits = mutableListOf<DropdownPanelHit>()
    private val dropdownScrollOffsets = mutableMapOf<String, Int>()
    private val settingsPanelScrollOffsets = mutableMapOf<String, Int>()
    private var settingsPanelScrollHit: SettingsPanelScrollHit? = null
    private val guiSettingHits = mutableListOf<GuiSettingHit>()
    private val numberBoundsCache = mutableMapOf<String, Pair<Double, Double>>()
    private var keybindListenTarget: KeybindListenTarget? = null
    private var keybindMouseBindSkipFrames = 0
    private val keybindMousePrev = BooleanArray(9) { false }
    private var keybindKeyboardBindSkipFrames = 0
    private val keybindKeyboardPrev = BooleanArray(GLFW.GLFW_KEY_LAST + 1) { false }
    private var guiMenuOpenKeyCode = DEFAULT_MENU_OPEN_KEY_CODE
    private var listeningMenuOpenBind = false
    private var menuOpenBindSkipFrames = 0
    private var guiSettingsLoaded = false
    private val categoryTextureSizeCache = mutableMapOf<Identifier, Pair<Int, Int>>()

    private fun mainContentY() = frameY + mainContentTopInset
    private fun mainContentH() = frameH - mainContentTopInset - mainContentBottomInset
    private fun moduleListBaseY() = mainContentY() + mainContentRowTopInset
    private fun moduleListBottomY() = mainContentY() + mainContentH()
    private fun frameInnerBottomY() = frameY + frameH - mainContentBottomInset

    private fun mainContentPanelX() = frameX + railW + contentPad
    private fun mainContentPanelW() = frameW - railW - contentPad - 8
    private fun contentPanelInnerX() = frameX + railW + contentPad + 3
    private fun contentPanelInnerW() = frameW - railW - contentPad * 2 - 6

    private fun railLabelCenterX() = frameX + railW / 2
    private fun railTitleBaselineY() = frameY + 8
    private fun categoryStackTopY() = frameY + 15
    private fun categoryCellX(): Int {
        // Center tiles in the actual rail panel interior (x from frameX+6 to frameX+railW).
        val railLeft = frameX + 6
        val railRight = frameX + railW
        val railInnerW = (railRight - railLeft).coerceAtLeast(categorySize)
        return railLeft + ((railInnerW - categorySize) / 2).coerceAtLeast(0)
    }

    override fun init() {
        super.init()
        ensureGuiSettingsLoaded()
        frameX = (width - frameW) / 2
        frameY = (height - frameH) / 2
        clampScroll()
        clearWidgets()

        var catY = categoryStackTopY()
        for (category in categories) {
            val currentCategory = category
            val catX = categoryCellX()
            addWidget(
                Button.builder(Component.empty()) {
                    selectedCategory = currentCategory
                    scrollOffset = 0
                    settingsModule = null
                    init()
                }.bounds(catX, catY, categorySize, categorySize).build()
            )
            catY += categorySize + categoryGap
        }

        val contentInnerX = contentPanelInnerX()
        val contentInnerW = contentPanelInnerW()
        if (settingsModule == null) {
            val colW = ((contentInnerW - moduleColGap) / 2).coerceAtLeast(80)
            val rightX = contentInnerX + colW + moduleColGap
            val baseY = moduleListBaseY()
            for ((idx, module) in visibleModules().withIndex()) {
                val currentModule = module
                val isRight = idx % 2 == 1
                val rowY = baseY + (idx / 2) * (rowHeight + moduleRowGap)
                val cellX = if (isRight) rightX else contentInnerX
                addWidget(
                    Button.builder(Component.empty()) {
                        toggleModule(currentModule)
                        init()
                    }.bounds(cellX, rowY, colW, rowHeight).build()
                )
                addWidget(
                    Button.builder(Component.empty()) {
                        settingsModule = if (settingsModule == currentModule) null else currentModule
                        init()
                    }.bounds(cellX + colW - 18, rowY, 18, rowHeight).build()
                )
            }

        }

        if (editingColorSetting != null || editingGuiAccent) {
            val obj = editingColorSetting
            val pw = 260
            val ph = 190
            val px = (width - pw) / 2
            val py = (height - ph) / 2

            addWidget(
                Button.builder(Component.empty()) {
                    val rgb = (java.awt.Color.HSBtoRGB(editHue, editSat, editVal) and 0x00FFFFFF)
                    val argb = ((editAlpha * 255f).toInt().coerceIn(0, 255) shl 24) or rgb
                    if (editingGuiAccent) {
                        guiAccentColor = argb
                        saveGuiSettings()
                    } else if (obj != null) {
                        val mod = editingColorModule
                        val field = editingColorFieldName
                        if (mod != null && field != null) {
                            setColorInt(mod, field, obj, argb)
                        } else {
                            setColorInt(obj, argb)
                        }
                        OdinClient.moduleConfig.save()
                    }
                    editingColorSetting = null
                    editingColorModule = null
                    editingColorFieldName = null
                    editingGuiAccent = false
                    init()
                }.bounds(px + 152, py + ph - 18, 48, 14).build()
            )
            addWidget(
                Button.builder(Component.empty()) {
                    editingColorSetting = null
                    editingColorModule = null
                    editingColorFieldName = null
                    editingGuiAccent = false
                    init()
                }.bounds(px + 206, py + ph - 18, 48, 14).build()
            )
        }
    }

    override fun render(gui: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        gui.fill(0, 0, width, height, 0x88000000.toInt())

        gui.fill(frameX, frameY, frameX + frameW, frameY + frameH, 0xFF090909.toInt())
        gui.fill(frameX + 1, frameY + 1, frameX + frameW - 1, frameY + frameH - 1, 0xFF121212.toInt())
        gui.fill(frameX + 2, frameY + 2, frameX + frameW - 2, frameY + frameH - 2, 0xFF0C0C0C.toInt())

        drawTopAccentBar(gui, frameX + 3, frameY + 3, frameW - 6)

        gui.fill(frameX + 6, frameY + mainContentTopInset, frameX + railW, frameInnerBottomY(), 0xFF050505.toInt())
        gui.fill(frameX + railW + 1, frameY + mainContentTopInset, frameX + railW + 2, frameInnerBottomY(), 0xFF2A2A2A.toInt())

        val contentX = mainContentPanelX()
        val contentY = mainContentY()
        val contentW = mainContentPanelW()
        val contentH = mainContentH()
        gui.fill(contentX, contentY, contentX + contentW, contentY + contentH, 0xFF0B0B0B.toInt())

        drawScaledCenteredText(gui, "Odin Client", railLabelCenterX(), railTitleBaselineY(), 0xFFE7ECF4.toInt(), skeetTextScale)

        var catY = categoryStackTopY()
        for (category in categories) {
            val catX = categoryCellX()
            drawCategoryButton(gui, catX, catY, categorySize, categorySize, category, mouseX, mouseY)
            catY += categorySize + categoryGap
        }

        val contentInnerX = contentPanelInnerX()
        val contentInnerW = contentPanelInnerW()
        val modulesForPage = visibleModules()
        val colW = ((contentInnerW - moduleColGap) / 2).coerceAtLeast(80)
        val rightX = contentInnerX + colW + moduleColGap
        val baseY = moduleListBaseY()

        var hoveredModule: Module? = null
        var settingsAnchorX = 0
        var settingsAnchorY = 0
        var settingsAnchorW = 0
        var settingsAnchorFound = false
        val selectedSettingsIndex = settingsModule?.let { modulesForPage.indexOf(it) } ?: -1
        val selectedSettingsRowGroup = if (selectedSettingsIndex >= 0) selectedSettingsIndex / 2 else -1
        val selectedSettingsColumn = if (selectedSettingsIndex >= 0) selectedSettingsIndex % 2 else -1
        val settingsShiftPx = if (settingsModule != null && selectedSettingsIndex >= 0) settingsPanelHeight(settingsModule!!) + 6 else 0
        if (selectedCategory != "GUI") {
            val sectionTop = contentY + 1
            val sectionBottom = contentY + contentH - 1
            gui.fill(contentInnerX - 1, sectionTop, contentInnerX + colW + 1, sectionBottom, 0xFF0C0C0C.toInt())
            gui.fill(contentInnerX, sectionTop + 1, contentInnerX + colW, sectionBottom - 1, 0xFF121212.toInt())
            gui.fill(rightX - 1, sectionTop, rightX + colW + 1, sectionBottom, 0xFF0C0C0C.toInt())
            gui.fill(rightX, sectionTop + 1, rightX + colW, sectionBottom - 1, 0xFF121212.toInt())

            for ((idx, module) in modulesForPage.withIndex()) {
                val isRight = idx % 2 == 1
                val rowGroup = idx / 2
                var rowY = baseY + rowGroup * (rowHeight + moduleRowGap)
                if (selectedSettingsRowGroup >= 0 && rowGroup > selectedSettingsRowGroup && (idx % 2) == selectedSettingsColumn) {
                    rowY += settingsShiftPx
                }
                val cellX = if (isRight) rightX else contentInnerX
                if (rowY < sectionTop + 1 || rowY > sectionBottom - rowHeight - 1) continue
                drawModuleRow(gui, cellX, rowY, colW, rowHeight, module, mouseX, mouseY)
                if (settingsModule == module) {
                    settingsAnchorX = cellX + 2
                    settingsAnchorY = rowY + rowHeight + 4
                    settingsAnchorW = colW
                    settingsAnchorFound = true
                }
                if (inside(mouseX.toDouble(), mouseY.toDouble(), cellX, rowY, colW, rowHeight)) {
                    hoveredModule = module
                }
            }
        }

        if (selectedCategory == "GUI") {
            val panelW = (contentInnerW - 20).coerceAtLeast(280)
            val panelX = contentInnerX + (contentInnerW - panelW) / 2
            drawGuiSettingsPanel(gui, panelX, contentY + 12, panelW, mouseX, mouseY)
            handleGuiSettingsInput(mouseX, mouseY)
        } else {
            settingsModule?.let { mod ->
                if (settingsAnchorFound) {
                    drawSettingsPanel(gui, mod, settingsAnchorX, settingsAnchorY, settingsAnchorW, mouseX, mouseY)
                    handleSettingsInput(mouseX, mouseY)
                } else {
                    settingsModule = null
                }
            }
        }

        if (editingColorSetting != null || editingGuiAccent) {
            drawColorPicker(gui, mouseX, mouseY)
            handleColorPickerDrag(mouseX, mouseY)
        }

        val rightDown = GLFW.glfwGetMouseButton(minecraft!!.window.handle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS
        if (selectedCategory != "GUI" && rightDown && !prevRightMouseDown && hoveredModule != null) {
            settingsModule = if (settingsModule == hoveredModule) null else hoveredModule
            init()
        }
        prevRightMouseDown = rightDown

        super.render(gui, mouseX, mouseY, partialTick)
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        if (keybindListenTarget != null) {
            clearListeningKeybind()
            return
        }
        if (listeningMenuOpenBind) {
            guiMenuOpenKeyCode = GLFW.GLFW_KEY_UNKNOWN
            sharedMenuOpenKeyCode = guiMenuOpenKeyCode
            saveGuiSettings()
            listeningMenuOpenBind = false
            return
        }
        super.onClose()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (verticalAmount == 0.0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        val hoveredDropdownPanel = dropdownPanelHits.firstOrNull {
            inside(mouseX, mouseY, it.x, it.y, it.w, it.h)
        }
        if (hoveredDropdownPanel != null) {
            val maxOffset = (hoveredDropdownPanel.totalOptions - hoveredDropdownPanel.visibleOptions).coerceAtLeast(0)
            val current = dropdownScrollOffsets[hoveredDropdownPanel.key] ?: 0
            val next = (current - verticalAmount.toInt()).coerceIn(0, maxOffset)
            dropdownScrollOffsets[hoveredDropdownPanel.key] = next
            return true
        }
        val panelHit = settingsPanelScrollHit
        if (panelHit != null && inside(mouseX, mouseY, panelHit.x, panelHit.y, panelHit.w, panelHit.h)) {
            val current = settingsPanelScrollOffsets[panelHit.key] ?: 0
            val next = (current - verticalAmount.toInt() * 12).coerceIn(0, panelHit.maxScrollPx)
            settingsPanelScrollOffsets[panelHit.key] = next
            return true
        }
        val contentInnerX = contentPanelInnerX()
        val contentInnerW = contentPanelInnerW()
        val listTop = moduleListBaseY()
        val listBottom = moduleListBottomY()
        if (!inside(mouseX, mouseY, contentInnerX, listTop, contentInnerW, listBottom - listTop)) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        }
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxScrollOffset())
        init()
        return true
    }

    private fun buildCategories() = listOf("General", "Floor 7", "Dungeons", "Rendering", "GUI")

    private fun filteredModules(): List<Module> {
        if (selectedCategory == "GUI") return emptyList()
        return modules.filter { getModuleCategory(it) == selectedCategory }
    }

    private fun visibleModules(): List<Module> {
        val list = filteredModules()
        val visibleCount = visibleRowCount() * 2
        val start = (scrollOffset.coerceIn(0, maxScrollOffset())) * 2
        val end = (start + visibleCount).coerceAtMost(list.size)
        if (start >= end) return emptyList()
        return list.subList(start, end)
    }

    private fun visibleRowCount(): Int {
        val listTop = moduleListBaseY()
        val listBottom = moduleListBottomY()
        val usable = (listBottom - listTop).coerceAtLeast(rowHeight)
        return (usable / (rowHeight + moduleRowGap)).coerceAtLeast(1)
    }

    private fun maxScrollOffset(): Int {
        var rowsNeeded = (filteredModules().size + 1) / 2
        if (settingsModule != null) {
            val panelRows = kotlin.math.ceil((settingsPanelHeight(settingsModule!!) + 6).toDouble() / (rowHeight + moduleRowGap).toDouble()).toInt()
            rowsNeeded += panelRows.coerceAtLeast(0)
        }
        val max = rowsNeeded - visibleRowCount()
        return max.coerceAtLeast(0)
    }

    private fun clampScroll() {
        scrollOffset = scrollOffset.coerceIn(0, maxScrollOffset())
    }

    private fun getModuleCategory(module: Module): String {
        val key = (module.javaClass.simpleName + " " + module.name).lowercase()

        // Rendering / visual modules first.
        if (key.contains("highlight") || key.contains("trajectory") || key.contains("noglow") || key.contains("worldscanner")) {
            return "Rendering"
        }

        // Floor 7 specific helpers.
        if (key.contains("autoterms") || key.contains("queueterms") || key.contains("simon") ||
            key.contains("autosuperboom") || key.contains("autopy")
        ) {
            return "Floor 7"
        }

        // General dungeon utility modules.
        if (key.contains("dungeon") || key.contains("secret") || key.contains("livid") || key.contains("spiritbear") ||
            key.contains("sentry") || key.contains("door") || key.contains("etherwarp") || key.contains("farmkeys") ||
            key.contains("autodojo") || key.contains("autogfs") || key.contains("autosell") || key.contains("simon") ||
            key.contains("breakerhelper") || key.contains("keyhighlight") || key.contains("ghostblock") || key.contains("cancelinteract")
        ) {
            return "Dungeons"
        }

        return "General"
    }

    private fun moduleButtonText(module: Module): Component {
        val on = if (isModuleEnabled(module)) "ON" else "OFF"
        return Component.literal("${displayModuleName(module)}: $on")
    }

    private fun displayModuleName(module: Module): String {
        var name = module.name
        // Remove decorative suffix tags like "(C)", "(II)", etc.
        name = name.replace(Regex("\\s*\\([^)]*\\)"), "")
        // Remove long separator labels that are used as pseudo section rows.
        name = name.replace(Regex("^\\s*[-_—]{2,}\\s*"), "")
            .replace(Regex("\\s*[-_—]{2,}\\s*$"), "")
        // Normalize extra spaces after cleanup.
        name = name.replace(Regex("\\s{2,}"), " ").trim()
        return if (name.isBlank()) module.name else name
    }

    private fun categoryIconStack(category: String): ItemStack = when (category.uppercase()) {
        "GENERAL" -> ItemStack(Items.COMPASS)
        "FLOOR 7" -> ItemStack(Items.NETHER_STAR)
        "DUNGEONS" -> ItemStack(Items.SKELETON_SKULL)
        "RENDERING" -> ItemStack(Items.PAINTING)
        "GUI" -> ItemStack(Items.OAK_SIGN)
        else -> ItemStack(Items.PAPER)
    }

    private fun categoryIconTextureId(category: String): Identifier {
        val name = when (category.uppercase()) {
            "GENERAL" -> "general"
            "FLOOR 7" -> "floor7"
            "DUNGEONS" -> "dungeons"
            "RENDERING" -> "rendering"
            "GUI" -> "gui"
            else -> "general"
        }
        return Identifier.fromNamespaceAndPath("odinclient", "textures/gui/category/$name.png")
    }

    private fun hasCategoryIconTexture(id: Identifier): Boolean {
        val rm = minecraft?.resourceManager ?: return false
        return runCatching { rm.getResource(id).isPresent }.getOrDefault(false)
    }

    /** Full ARGB with given alpha (0–255) and RGB from [accentsRgb] (0x00RRGGBB or full int). */
    private fun colorWithAlpha(accentsRgb: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (accentsRgb and 0x00FFFFFF)

    /**
     * White PNG icons, tinted per pass. Selected: accent halo. Hover (not selected): white halo (no tile fill).
     */
    private fun drawCategoryIconTexture(
        gui: GuiGraphics,
        tid: Identifier,
        baseX: Int,
        baseY: Int,
        d: Int,
        tw: Int,
        th: Int,
        selected: Boolean,
        hovered: Boolean,
    ) {
        val whiteRgb = 0x00FFFFFF
        if (selected) {
            val accRgb = guiAccentColor and 0x00FFFFFF
            for ((ox, oy, a) in listOf(
                Triple(-2, 0, 0x30),
                Triple(2, 0, 0x30),
                Triple(0, -2, 0x30),
                Triple(0, 2, 0x30),
            )) {
                gui.blit(
                    RenderPipelines.GUI_TEXTURED, tid,
                    baseX + ox, baseY + oy, 0f, 0f, d, d, tw, th, tw, th,
                    colorWithAlpha(accRgb, a),
                )
            }
            for ((ox, oy) in listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)) {
                gui.blit(
                    RenderPipelines.GUI_TEXTURED, tid,
                    baseX + ox, baseY + oy, 0f, 0f, d, d, tw, th, tw, th,
                    colorWithAlpha(accRgb, 0x3A),
                )
            }
            gui.blit(
                RenderPipelines.GUI_TEXTURED, tid,
                baseX - 1, baseY, 0f, 0f, d, d, tw, th, tw, th,
                colorWithAlpha(accRgb, 0x58),
            )
            gui.blit(
                RenderPipelines.GUI_TEXTURED, tid,
                baseX + 1, baseY, 0f, 0f, d, d, tw, th, tw, th,
                colorWithAlpha(accRgb, 0x58),
            )
            gui.blit(
                RenderPipelines.GUI_TEXTURED, tid,
                baseX, baseY - 1, 0f, 0f, d, d, tw, th, tw, th,
                colorWithAlpha(accRgb, 0x42),
            )
            gui.blit(
                RenderPipelines.GUI_TEXTURED, tid,
                baseX, baseY + 1, 0f, 0f, d, d, tw, th, tw, th,
                colorWithAlpha(accRgb, 0x42),
            )
        } else if (hovered) {
            // Match [drawModuleRow] off-white text glow, but for the icon
            gui.blit(
                RenderPipelines.GUI_TEXTURED, tid,
                baseX - 1, baseY, 0f, 0f, d, d, tw, th, tw, th,
                colorWithAlpha(whiteRgb, 0x50),
            )
            gui.blit(
                RenderPipelines.GUI_TEXTURED, tid,
                baseX + 1, baseY, 0f, 0f, d, d, tw, th, tw, th,
                colorWithAlpha(whiteRgb, 0x50),
            )
            gui.blit(
                RenderPipelines.GUI_TEXTURED, tid,
                baseX, baseY - 1, 0f, 0f, d, d, tw, th, tw, th,
                colorWithAlpha(whiteRgb, 0x38),
            )
            gui.blit(
                RenderPipelines.GUI_TEXTURED, tid,
                baseX, baseY + 1, 0f, 0f, d, d, tw, th, tw, th,
                colorWithAlpha(whiteRgb, 0x38),
            )
        }
        gui.blit(
            RenderPipelines.GUI_TEXTURED, tid,
            baseX, baseY, 0f, 0f, d, d, tw, th, tw, th,
            0xFFFFFFFF.toInt(),
        )
    }

    private fun getCategoryTextureSize(id: Identifier): Pair<Int, Int> {
        categoryTextureSizeCache[id]?.let { return it }
        val stream = runCatching { minecraft?.resourceManager?.getResource(id)?.orElse(null)?.open() }.getOrNull() ?: return 32 to 32
        return stream.use { s ->
            runCatching {
                NativeImage.read(s).use { img ->
                    val p: Pair<Int, Int> = img.width to img.height
                    categoryTextureSizeCache[id] = p
                    p
                }
            }.getOrDefault(32 to 32)
        }
    }

    private fun isModuleEnabled(module: Module): Boolean {
        runCatching {
            val m = module.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE &&
                    (it.name.equals("isEnabled", true) || it.name.equals("getEnabled", true))
            }
            if (m != null) return m.invoke(module) as Boolean
        }

        runCatching {
            val f = module.javaClass.declaredFields.firstOrNull {
                it.type == java.lang.Boolean.TYPE && it.name.equals("enabled", true)
            }
            if (f != null) {
                f.isAccessible = true
                return f.getBoolean(module)
            }
        }

        return false
    }

    private fun toggleModule(module: Module) {
        val current = isModuleEnabled(module)

        runCatching {
            val toggle = module.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.name.equals("toggle", true)
            }
            if (toggle != null) {
                toggle.invoke(module)
                return
            }
        }

        runCatching {
            val setEnabled = module.javaClass.methods.firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0] == java.lang.Boolean.TYPE &&
                    (it.name.equals("setEnabled", true) || it.name.equals("setState", true))
            }
            if (setEnabled != null) {
                setEnabled.invoke(module, !current)
                return
            }
        }

        runCatching {
            val f = module.javaClass.declaredFields.firstOrNull {
                it.type == java.lang.Boolean.TYPE && it.name.equals("enabled", true)
            }
            if (f != null) {
                f.isAccessible = true
                f.setBoolean(module, !current)
            }
        }
    }

    private fun drawCategoryButton(gui: GuiGraphics, x: Int, y: Int, w: Int, h: Int, category: String, mx: Int, my: Int) {
        val hovered = inside(mx.toDouble(), my.toDouble(), x, y, w, h)
        val selected = selectedCategory == category
        // Older visual state: flat tile so selection is read from icon glow, not square shading.
        gui.fill(x, y, x + w, y + h, 0xFF101010.toInt())

        val tid = categoryIconTextureId(category)
        if (hasCategoryIconTexture(tid)) {
            // Use the 12-arg blit: dest (width,height) and texture (regionW,regionH) are separate.
            // The 9-arg overload uses the same w,h for both, so d > tw/th yields invalid UVs and draws nothing.
            val d = (minOf(w, h) - 8).coerceAtLeast(8)
            val ix = x + (w - d) / 2
            val iy = y + (h - d) / 2
            val (tw, th) = getCategoryTextureSize(tid)
            drawCategoryIconTexture(gui, tid, ix, iy, d, tw, th, selected, hovered)
        } else {
            val stack = categoryIconStack(category)
            val icon = 16
            val ix = x + (w - icon) / 2
            val iy = y + (h - icon) / 2
            gui.renderItem(stack, ix, iy)
        }
    }

    private fun drawModuleRow(gui: GuiGraphics, x: Int, y: Int, w: Int, h: Int, module: Module, mx: Int, my: Int) {
        val hovered = inside(mx.toDouble(), my.toDouble(), x, y, w, h)
        val enabled = isModuleEnabled(module)
        // Keep rows flat; hover feedback is handled by subtle text glow only.

        // Skeet-like tiny checkbox on left
        val cb = 7
        val cbx = x + 6
        val cby = y + (h - cb) / 2
        gui.fill(cbx, cby, cbx + cb, cby + cb, 0xFF444444.toInt())
        gui.fill(cbx + 1, cby + 1, cbx + cb - 1, cby + cb - 1, 0xFF111111.toInt())
        if (enabled) {
            gui.fill(cbx + 2, cby + 2, cbx + cb - 2, cby + cb - 2, accentArgb())
        }

        // Small text like skeet rows, with a subtle glow when hovered.
        val nameX = cbx + cb + 7
        val nameY = y + (h - 8) / 2 + 1
        val baseTextColor = if (enabled) 0xFFEAF0FF.toInt() else 0xFFC0C6D2.toInt()
        if (hovered) {
            drawScaledText(gui, displayModuleName(module), nameX - 1, nameY, 0x50FFFFFF, skeetTextScale)
            drawScaledText(gui, displayModuleName(module), nameX + 1, nameY, 0x50FFFFFF, skeetTextScale)
            drawScaledText(gui, displayModuleName(module), nameX, nameY - 1, 0x38FFFFFF, skeetTextScale)
            drawScaledText(gui, displayModuleName(module), nameX, nameY + 1, 0x38FFFFFF, skeetTextScale)
        }
        drawScaledText(gui, displayModuleName(module), nameX, nameY, baseTextColor, skeetTextScale)
        drawScaledText(gui, "...", x + w - 12, y + (h - 8) / 2 + 1, 0xFF7F8796.toInt(), skeetTextScale)
    }

    private fun drawMiniButton(gui: GuiGraphics, x: Int, y: Int, w: Int, h: Int, text: String, mx: Int, my: Int) {
        val hovered = inside(mx.toDouble(), my.toDouble(), x, y, w, h)
        gui.fill(x, y, x + w, y + h, 0xFF2A2A2A.toInt())
        gui.fill(x + 1, y + 1, x + w - 1, y + h - 1, if (hovered) 0xFF1D1D1D.toInt() else 0xFF121212.toInt())
        drawScaledCenteredText(gui, text, x + w / 2, y + (h - 8) / 2 + 1, 0xFFCBD3E0.toInt(), skeetTextScale)
    }

    private fun drawGuiSettingsPanel(gui: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        guiSettingHits.clear()
        val panelH = 176
        gui.fill(x, y, x + w, y + panelH, 0xFF0E0E0E.toInt())
        gui.fill(x + 1, y + 1, x + w - 1, y + panelH - 1, 0xFF151515.toInt())
        drawTopAccentBar(gui, x + 2, y + 2, w - 4)
        drawScaledText(gui, "GUI Settings", x + 8, y + 8, 0xFFE7ECF4.toInt(), skeetTextScale)

        var rowY = y + 24
        val rowH = 14

        // Accent color
        gui.fill(x + 6, rowY, x + w - 6, rowY + rowH, 0xFF141414.toInt())
        drawScaledText(gui, "Accent Color", x + 10, rowY + 2, 0xFFC8D2E3.toInt(), skeetTextScale)
        val swX = x + w - 24
        gui.fill(swX, rowY + 3, swX + 10, rowY + 11, 0xFF2A2A2A.toInt())
        gui.fill(swX + 1, rowY + 4, swX + 9, rowY + 10, accentArgb())
        guiSettingHits += GuiSettingHit("accent_pick", swX, rowY + 2, 12, 10)
        rowY += rowH + 2

        // Text scale
        gui.fill(x + 6, rowY, x + w - 6, rowY + rowH, 0xFF141414.toInt())
        drawScaledText(gui, "Text Size: %.2f".format(skeetTextScale), x + 10, rowY + 2, 0xFFC8D2E3.toInt(), skeetTextScale)
        drawScaledText(gui, "-", x + w - 30, rowY + 2, 0xFFBFC8D8.toInt(), skeetTextScale)
        drawScaledText(gui, "+", x + w - 16, rowY + 2, 0xFFBFC8D8.toInt(), skeetTextScale)
        guiSettingHits += GuiSettingHit("text_dec", x + w - 32, rowY, 10, rowH)
        guiSettingHits += GuiSettingHit("text_inc", x + w - 18, rowY, 10, rowH)
        rowY += rowH + 2

        // Dense layout
        gui.fill(x + 6, rowY, x + w - 6, rowY + rowH, 0xFF141414.toInt())
        drawScaledText(gui, "Dense Layout", x + 10, rowY + 2, 0xFFC8D2E3.toInt(), skeetTextScale)
        drawCheck(gui, x + w - 18, rowY + 3, guiDenseLayout)
        guiSettingHits += GuiSettingHit("dense_toggle", x + 6, rowY, w - 12, rowH)
        rowY += rowH + 2

        gui.fill(x + 6, rowY, x + w - 6, rowY + rowH, 0xFF141414.toInt())
        drawScaledText(gui, "Top Accent Bar", x + 10, rowY + 2, 0xFFC8D2E3.toInt(), skeetTextScale)
        drawCheck(gui, x + w - 18, rowY + 3, guiShowRainbowBar)
        guiSettingHits += GuiSettingHit("rainbow_toggle", x + 6, rowY, w - 12, rowH)
        rowY += rowH + 2

        gui.fill(x + 6, rowY, x + w - 6, rowY + rowH, 0xFF141414.toInt())
        drawScaledText(gui, "Skeet color fade", x + 10, rowY + 2, 0xFFC8D2E3.toInt(), skeetTextScale)
        drawCheck(gui, x + w - 18, rowY + 3, guiAccentBarSkeetFade)
        guiSettingHits += GuiSettingHit("skeet_fade_toggle", x + 6, rowY, w - 12, rowH)
        rowY += rowH + 2

        // Category square size
        gui.fill(x + 6, rowY, x + w - 6, rowY + rowH, 0xFF141414.toInt())
        drawScaledText(gui, "Category Size: $categorySize", x + 10, rowY + 2, 0xFFC8D2E3.toInt(), skeetTextScale)
        drawScaledText(gui, "-", x + w - 30, rowY + 2, 0xFFBFC8D8.toInt(), skeetTextScale)
        drawScaledText(gui, "+", x + w - 16, rowY + 2, 0xFFBFC8D8.toInt(), skeetTextScale)
        guiSettingHits += GuiSettingHit("size_dec", x + w - 32, rowY, 10, rowH)
        guiSettingHits += GuiSettingHit("size_inc", x + w - 18, rowY, 10, rowH)
        rowY += rowH + 2

        // Open menu keybind
        gui.fill(x + 6, rowY, x + w - 6, rowY + rowH, 0xFF141414.toInt())
        drawScaledText(gui, "Open Menu Key", x + 10, rowY + 2, 0xFFC8D2E3.toInt(), skeetTextScale)
        val menuBindLabel = if (listeningMenuOpenBind) "[...]" else {
            if (guiMenuOpenKeyCode == GLFW.GLFW_KEY_UNKNOWN) "[-]"
            else "[${shortKeybindLabel(InputConstants.Type.KEYSYM.getOrCreate(guiMenuOpenKeyCode))}]"
        }
        drawScaledText(gui, menuBindLabel, x + w - 46, rowY + 2, 0xFFBFC8D8.toInt(), skeetTextScale)
        guiSettingHits += GuiSettingHit("menu_open_bind", x + 6, rowY, w - 12, rowH)
        rowY += rowH + 2

        gui.fill(x + 6, rowY, x + w - 6, rowY + rowH, if (inside(mouseX.toDouble(), mouseY.toDouble(), x + 6, rowY, w - 12, rowH)) 0xFF1E1E1E.toInt() else 0xFF141414.toInt())
        drawScaledText(gui, "Reset GUI Theme", x + 10, rowY + 2, 0xFFC8D2E3.toInt(), skeetTextScale)
        guiSettingHits += GuiSettingHit("reset", x + 6, rowY, w - 12, rowH)
    }

    private fun handleGuiSettingsInput(mouseX: Int, mouseY: Int) {
        if (editingColorSetting != null || editingGuiAccent) return
        val handle = minecraft!!.window.handle()
        val leftDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
        val justPressed = leftDown && !prevLeftMouseDown
        if (listeningMenuOpenBind) {
            if (menuOpenBindSkipFrames > 0) menuOpenBindSkipFrames--
            if (menuOpenBindSkipFrames == 0) {
                for (code in GLFW.GLFW_KEY_SPACE..GLFW.GLFW_KEY_LAST) {
                    val down = GLFW.glfwGetKey(handle, code) == GLFW.GLFW_PRESS
                    if (!down) continue
                    if (code == GLFW.GLFW_KEY_ESCAPE) {
                        guiMenuOpenKeyCode = GLFW.GLFW_KEY_UNKNOWN
                    } else {
                        guiMenuOpenKeyCode = code
                    }
                    sharedMenuOpenKeyCode = guiMenuOpenKeyCode
                    listeningMenuOpenBind = false
                    saveGuiSettings()
                    init()
                    prevLeftMouseDown = leftDown
                    return
                }
            }
        }
        if (!justPressed) {
            prevLeftMouseDown = leftDown
            return
        }
        val hit = guiSettingHits.firstOrNull { inside(mouseX.toDouble(), mouseY.toDouble(), it.x, it.y, it.w, it.h) }
        if (hit != null) {
            when (hit.kind) {
                "accent_pick" -> {
                    editingGuiAccent = true
                    val r = (guiAccentColor shr 16) and 0xFF
                    val g = (guiAccentColor shr 8) and 0xFF
                    val b = guiAccentColor and 0xFF
                    val hsb = java.awt.Color.RGBtoHSB(r, g, b, null)
                    editHue = hsb[0].coerceIn(0f, 1f)
                    editSat = hsb[1].coerceIn(0f, 1f)
                    editVal = hsb[2].coerceIn(0f, 1f)
                    editAlpha = 1f
                }
                "text_dec" -> skeetTextScale = (skeetTextScale - 0.02f).coerceIn(0.58f, 0.95f)
                "text_inc" -> skeetTextScale = (skeetTextScale + 0.02f).coerceIn(0.58f, 0.95f)
                "dense_toggle" -> {
                    guiDenseLayout = !guiDenseLayout
                    applyDensityPreset()
                }
                "rainbow_toggle" -> guiShowRainbowBar = !guiShowRainbowBar
                "skeet_fade_toggle" -> guiAccentBarSkeetFade = !guiAccentBarSkeetFade
                "size_dec" -> categorySize = (categorySize - 2).coerceIn(28, 56)
                "size_inc" -> categorySize = (categorySize + 2).coerceIn(28, 56)
                "menu_open_bind" -> {
                    listeningMenuOpenBind = true
                    menuOpenBindSkipFrames = 2
                }
                "reset" -> {
                    guiAccentColor = DEFAULT_ACCENT_COLOR
                    guiShowRainbowBar = DEFAULT_SHOW_RAINBOW
                    guiAccentBarSkeetFade = DEFAULT_ACCENT_BAR_SKEET_FADE
                    guiDenseLayout = DEFAULT_DENSE_LAYOUT
                    skeetTextScale = DEFAULT_TEXT_SCALE
                    categorySize = DEFAULT_CATEGORY_SIZE
                    guiMenuOpenKeyCode = DEFAULT_MENU_OPEN_KEY_CODE
                    sharedMenuOpenKeyCode = guiMenuOpenKeyCode
                    applyDensityPreset()
                }
            }
            saveGuiSettings()
            init()
        }
        prevLeftMouseDown = leftDown
    }

    private fun guiSettingsPath(): Path? {
        val mc = minecraft ?: return null
        return mc.gameDirectory.toPath().resolve("config").resolve(GUI_SETTINGS_FILE_NAME)
    }

    private fun ensureGuiSettingsLoaded() {
        if (guiSettingsLoaded) return
        loadGuiSettings()
        guiSettingsLoaded = true
    }

    private fun loadGuiSettings() {
        val path = guiSettingsPath() ?: return
        runCatching {
            Files.createDirectories(path.parent)
            if (!Files.exists(path)) {
                saveGuiSettings(path)
                return
            }
            val raw = Files.readString(path)
            if (raw.isBlank()) {
                saveGuiSettings(path)
                return
            }
            fun findInt(key: String, fallback: Int): Int {
                val m = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(raw) ?: return fallback
                return m.groupValues[1].toIntOrNull() ?: fallback
            }
            fun findFloat(key: String, fallback: Float): Float {
                val m = Regex("\"$key\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").find(raw) ?: return fallback
                return m.groupValues[1].toFloatOrNull() ?: fallback
            }
            fun findBool(key: String, fallback: Boolean): Boolean {
                val m = Regex("\"$key\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE).find(raw) ?: return fallback
                return m.groupValues[1].equals("true", ignoreCase = true)
            }

            guiAccentColor = findInt("accentColor", DEFAULT_ACCENT_COLOR)
            guiShowRainbowBar = findBool("showRainbowBar", DEFAULT_SHOW_RAINBOW)
            guiAccentBarSkeetFade = findBool("accentBarSkeetFade", DEFAULT_ACCENT_BAR_SKEET_FADE)
            guiDenseLayout = findBool("denseLayout", DEFAULT_DENSE_LAYOUT)
            skeetTextScale = findFloat("textScale", DEFAULT_TEXT_SCALE).coerceIn(0.58f, 0.95f)
            categorySize = findInt("categorySize", DEFAULT_CATEGORY_SIZE).coerceIn(28, 56)
            guiMenuOpenKeyCode = findInt("menuOpenKeyCode", DEFAULT_MENU_OPEN_KEY_CODE)
            sharedMenuOpenKeyCode = guiMenuOpenKeyCode
            applyDensityPreset()
        }.onFailure {
            // If parse fails, rewrite a clean config and continue with current/default values.
            saveGuiSettings(path)
        }
    }

    private fun saveGuiSettings(pathOverride: Path? = null) {
        val path = pathOverride ?: guiSettingsPath() ?: return
        runCatching {
            Files.createDirectories(path.parent)
            val json = buildString {
                append("{\n")
                append("  \"accentColor\": ").append(guiAccentColor).append(",\n")
                append("  \"showRainbowBar\": ").append(guiShowRainbowBar).append(",\n")
                append("  \"accentBarSkeetFade\": ").append(guiAccentBarSkeetFade).append(",\n")
                append("  \"denseLayout\": ").append(guiDenseLayout).append(",\n")
                append("  \"textScale\": ").append(String.format(java.util.Locale.ROOT, "%.3f", skeetTextScale)).append(",\n")
                append("  \"categorySize\": ").append(categorySize).append(",\n")
                append("  \"menuOpenKeyCode\": ").append(guiMenuOpenKeyCode).append("\n")
                append("}\n")
            }
            Files.writeString(
                path,
                json,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
        }
    }

    private fun applyDensityPreset() {
        if (guiDenseLayout) {
            moduleRowGap = 4
            categoryGap = 4
        } else {
            moduleRowGap = 7
            categoryGap = 5
        }
    }

    private fun drawCheck(gui: GuiGraphics, x: Int, y: Int, checked: Boolean) {
        gui.fill(x, y, x + 8, y + 8, 0xFF383838.toInt())
        gui.fill(x + 1, y + 1, x + 7, y + 7, 0xFF111111.toInt())
        if (checked) gui.fill(x + 2, y + 2, x + 6, y + 6, accentArgb())
    }

    private fun accentArgb(): Int = 0xFF000000.toInt() or (guiAccentColor and 0x00FFFFFF)

    private fun drawSettingsPanel(gui: GuiGraphics, module: Module, anchorX: Int, anchorY: Int, anchorW: Int, mouseX: Int, mouseY: Int) {
        settingHits.clear()
        dropdownOptionHits.clear()
        dropdownPanelHits.clear()
        settingsPanelScrollHit = null
        if (keybindListenTarget != null && keybindListenTarget!!.module != module) {
            keybindListenTarget = null
        }
        val x = anchorX
        val y = anchorY
        val w = anchorW
        val settings = getVisibleInlineSettings(module)
        val rowH = 14
        val headerH = 16
        val fullH = settingsPanelHeight(module)
        val maxBottom = frameY + frameH - 9
        val h = (maxBottom - y).coerceAtMost(fullH).coerceAtLeast(42)
        val contentTop = y + headerH
        val contentBottom = y + h - 6
        val fullContentH = (fullH - headerH - 6).coerceAtLeast(0)
        val visibleContentH = (contentBottom - contentTop).coerceAtLeast(0)
        val maxScrollPx = (fullContentH - visibleContentH).coerceAtLeast(0)
        val scrollKey = "${module.javaClass.name}#panel"
        val scrollPx = (settingsPanelScrollOffsets[scrollKey] ?: 0).coerceIn(0, maxScrollPx)
        settingsPanelScrollHit = SettingsPanelScrollHit(scrollKey, x, y, w, h, maxScrollPx)
        if (maxScrollPx == 0) settingsPanelScrollOffsets.remove(scrollKey)
        gui.fill(x, y, x + w, y + h, 0xFF090909.toInt())
        gui.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF121212.toInt())
        drawTopAccentBar(gui, x + 2, y + 2, w - 4)
        drawScaledText(gui, "${displayModuleName(module)} settings", x + 6, y + 6, 0xFFE7ECF4.toInt(), skeetTextScale)

        val cellX = x + 4
        val colW = w - 8
        var yCursor = y + headerH - scrollPx
        for (pair in settings) {
            val (fieldName, settingObj) = pair
            val sy = yCursor
            val rowVisible = sy + rowH > contentTop && sy < contentBottom
            val hovered = rowVisible && inside(mouseX.toDouble(), mouseY.toDouble(), cellX, sy, colW, rowH)
            if (rowVisible) {
                gui.fill(cellX, sy, cellX + colW, sy + rowH, if (hovered) 0xFF1D1D1D.toInt() else 0xFF141414.toInt())
            }

            val cleanName = settingDisplayName(settingObj, fieldName)
            if (isKeybindSetting(settingObj)) {
                val k = getKeyFromKeybind(settingObj)
                val listening = keybindListenTarget?.settingObj === settingObj
                val bracket = when {
                    listening -> "[...]"
                    k == null || isKeybindUnset(k) -> "[-]"
                    else -> "[${shortKeybindLabel(k)}]"
                }
                val rowText = "$cleanName: $bracket"
                if (rowVisible) drawScaledText(gui, rowText, cellX + 3, sy + 2, 0xFFC8D2E3.toInt(), skeetTextScale)
                yCursor += rowH + 2
                if (rowVisible) settingHits += SettingHit(module, fieldName, settingObj, "keybind", cellX, sy, colW, rowH)
                continue
            }
            val boolVal = readBooleanSetting(settingObj)
            val selectorOptions = resolveSelectorOptions(settingObj, boolVal, cleanName, fieldName)
            val hasSelectorOptions = selectorOptions.size > 1
            val isBinarySelector = isBinarySelectorOptions(selectorOptions)
            val renderSelectorOptions = hasSelectorOptions && !isBinarySelector
            val numVal = readEffectiveNumberValue(module, fieldName, settingObj)
            val colorVal = readColorInt(settingObj)
            val selectorVal = readSelectorValue(settingObj)
            val selectorCurrentLabel = resolveSelectorCurrentLabel(selectorVal, selectorOptions)
            val valueText = if (boolVal != null) {
                ""
            } else if (numVal != null) {
                "%.2f".format(numVal)
            } else if (colorVal != null) {
                "#%06X".format(colorVal and 0xFFFFFF)
            } else if (selectorVal != null) {
                selectorCurrentLabel
            } else {
                readNumberSetting(settingObj)?.let {
                    if (it.contains("->")) "Action" else it
                } ?: "..."
            }
            val shownValueText = if (hasSelectorOptions) (selectorCurrentLabel.ifBlank { selectorOptions.firstOrNull() ?: valueText }) else valueText
            val compactName = cleanName
            val rowText = if (boolVal != null || isBinarySelector) compactName else "$compactName: $shownValueText"
            val selectorHeaderText = compactName
            if (rowVisible) drawScaledText(gui, if (renderSelectorOptions) selectorHeaderText else rowText, cellX + 3, sy + 2, 0xFFC8D2E3.toInt(), skeetTextScale)
            yCursor += rowH + 2

            if (renderSelectorOptions) {
                val optionIndent = 10
                val optionX = cellX + optionIndent
                val optionW = (colW - optionIndent - 2).coerceAtLeast(40)
                val visibleOptCount = 5
                val dropKey = "${module.javaClass.name}#$fieldName"
                val maxDropOffset = (selectorOptions.size - visibleOptCount).coerceAtLeast(0)
                val dropOffset = (dropdownScrollOffsets[dropKey] ?: 0).coerceIn(0, maxDropOffset)
                if (dropOffset == 0 && maxDropOffset == 0) dropdownScrollOffsets.remove(dropKey)
                val opts = selectorOptions.drop(dropOffset).take(visibleOptCount)
                val optionRowH = 11
                val panelTop = yCursor
                val panelBottom = panelTop + opts.size * optionRowH + 4
                val panelVisible = panelBottom > contentTop && panelTop < contentBottom

                // Boxed subgroup so selector choices are visually separate.
                if (panelVisible) {
                    val visibleTop = panelTop.coerceAtLeast(contentTop)
                    val visibleBottom = panelBottom.coerceAtMost(contentBottom)
                    gui.fill(optionX - 2, visibleTop - 1, optionX + optionW + 2, visibleBottom + 1, 0xFF242424.toInt())
                    gui.fill(optionX - 1, visibleTop, optionX + optionW + 1, visibleBottom, 0xFF121212.toInt())
                    dropdownPanelHits += DropdownPanelHit(
                        dropKey,
                        optionX - 2,
                        visibleTop - 1,
                        optionW + 4,
                        (visibleBottom - visibleTop + 2).coerceAtLeast(1),
                        selectorOptions.size,
                        visibleOptCount
                    )
                }

                var oy = panelTop + 1
                for (opt in opts) {
                    val optVisible = oy + optionRowH > contentTop && oy < contentBottom
                    if (optVisible) {
                        val optHovered = inside(mouseX.toDouble(), mouseY.toDouble(), optionX, oy, optionW, optionRowH)
                        gui.fill(optionX, oy, optionX + optionW, oy + optionRowH, if (optHovered) 0xFF202020.toInt() else 0xFF161616.toInt())
                        drawScaledText(gui, opt, optionX + 4, oy + 3, 0xFFBFC8D8.toInt(), skeetTextScale)
                        val cbx = optionX + optionW - 10
                        val cby = oy + 2
                        gui.fill(cbx, cby, cbx + 8, cby + 8, 0xFF383838.toInt())
                        gui.fill(cbx + 1, cby + 1, cbx + 7, cby + 7, 0xFF111111.toInt())
                        if (selectorOptionMatches(opt, selectorCurrentLabel)) gui.fill(cbx + 2, cby + 2, cbx + 6, cby + 6, accentArgb())
                        dropdownOptionHits += DropdownOptionHit(module, fieldName, settingObj, opt, optionX, oy, optionW, optionRowH)
                    }
                    oy += optionRowH
                }
                yCursor = panelBottom + 6
            } else if (boolVal != null) {
                val cbx = cellX + colW - 10
                val cby = sy + 3
                if (rowVisible) {
                    gui.fill(cbx, cby, cbx + 8, cby + 8, 0xFF383838.toInt())
                    gui.fill(cbx + 1, cby + 1, cbx + 7, cby + 7, 0xFF111111.toInt())
                    if (boolVal) gui.fill(cbx + 2, cby + 2, cbx + 6, cby + 6, accentArgb())
                    settingHits += SettingHit(module, fieldName, settingObj, "bool", cellX, sy, colW, rowH)
                }
            } else if (numVal != null) {
                val (min, max) = readNumberBounds(module, fieldName, settingObj)
                val range = (max - min).takeIf { it > 0.0 } ?: 1.0
                val t = ((numVal - min) / range).toFloat().coerceIn(0f, 1f)
                val sx = cellX + 4
                val sw = colW - 8
                val syBar = sy + rowH - 4
                if (rowVisible) {
                    gui.fill(sx, syBar, sx + sw, syBar + 2, 0xFF2C2C2C.toInt())
                    gui.fill(sx, syBar, sx + (sw * t).toInt(), syBar + 2, accentArgb())
                    val knobX = (sx + (sw * t).toInt()).coerceIn(sx, sx + sw)
                    gui.fill(knobX - 1, syBar - 1, knobX + 1, syBar + 3, 0xFFECECEC.toInt())
                    settingHits += SettingHit(module, fieldName, settingObj, "number", sx, sy, sw, rowH)
                }
            } else if (colorVal != null) {
                val swx = cellX + colW - 26
                val swy = sy + 3
                val argb = 0xFF000000.toInt() or (colorVal and 0xFFFFFF)
                if (rowVisible) {
                    gui.fill(swx, swy, swx + 16, swy + 8, 0xFF2A2A2A.toInt())
                    gui.fill(swx + 1, swy + 1, swx + 15, swy + 7, argb)
                    settingHits += SettingHit(module, fieldName, settingObj, "color", cellX, sy, colW, rowH)
                }
            } 
        }
    }

    private fun settingsPanelHeight(module: Module): Int {
        val settings = getVisibleInlineSettings(module)
        val rowH = 14
        val headerH = 16
        val expandedRows = settings.sumOf { (_, settingObj) ->
            if (isKeybindSetting(settingObj)) return@sumOf 1
            val name = settingDisplayName(settingObj, "")
            val boolVal = readBooleanSetting(settingObj)
            val options = resolveSelectorOptions(settingObj, boolVal, name, "")
            if (options.size > 1 && !isBinarySelectorOptions(options)) 1 + options.take(5).size else 1
        }
        return headerH + expandedRows * (rowH + 2) + 6
    }

    private fun getVisibleInlineSettings(module: Module): List<Pair<String, Any>> {
        val raw = getModuleSettings(module)
        val out = mutableListOf<Pair<String, Any>>()

        var i = 0
        var hideSectionChildren = false
        while (i < raw.size) {
            val current = raw[i]
            val currentName = settingDisplayName(current.second, current.first)
            val isDropdownHeader = currentName.contains("dropdown", ignoreCase = true)

            if (isDropdownHeader) {
                // Skip the synthetic "... Dropdown" row entirely.
                hideSectionChildren = false

                // Convention in these modules: the immediate next boolean is the section toggle.
                if (i + 1 < raw.size) {
                    val togglePair = raw[i + 1]
                    out += togglePair
                    val enabled = readBooleanSetting(togglePair.second)
                    hideSectionChildren = enabled == false
                    i += 2
                    continue
                }

                i++
                continue
            }

            if (!hideSectionChildren) {
                out += current
            }

            i++
        }

        return out
    }

    private fun isBinarySelectorOptions(options: List<String>): Boolean {
        if (options.size != 2) return false
        val normalized = options.map { it.trim().uppercase() }.toSet()
        return normalized == setOf("ON", "OFF") || normalized == setOf("TRUE", "FALSE")
    }

    private fun selectorOptionMatches(option: String, current: String): Boolean {
        fun norm(s: String): String = s.lowercase()
            .replace("_", " ")
            .replace("-", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return norm(option) == norm(current)
    }

    private fun resolveSelectorCurrentLabel(selectorVal: String?, options: List<String>): String {
        if (selectorVal.isNullOrBlank()) return ""
        val idx = selectorVal.toIntOrNull()
        if (idx != null && idx >= 0 && idx < options.size) return options[idx]
        return selectorVal
    }

    private fun handleSettingsInput(mouseX: Int, mouseY: Int) {
        if (editingColorSetting != null || editingGuiAccent) return
        if (keybindMouseBindSkipFrames > 0) keybindMouseBindSkipFrames--
        if (keybindKeyboardBindSkipFrames > 0) keybindKeyboardBindSkipFrames--
        val w = minecraft!!.window.handle()
        val leftDown = GLFW.glfwGetMouseButton(w, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
        val justPressed = leftDown && !prevLeftMouseDown

        if (leftDown && activeSliderHit != null) {
            applySliderValue(activeSliderHit!!, mouseX)
            prevLeftMouseDown = leftDown
            updateKeybindMousePrev(w)
            updateKeybindKeyboardPrev(w)
            return
        }

        if (leftDown) {
            val hitOpt = dropdownOptionHits.firstOrNull {
                inside(mouseX.toDouble(), mouseY.toDouble(), it.x, it.y, it.w, it.h)
            }
            if (hitOpt != null && justPressed) {
                setSelectorValue(hitOpt.module, hitOpt.fieldName, hitOpt.settingObj, hitOpt.option)
                init()
                prevLeftMouseDown = leftDown
                updateKeybindMousePrev(w)
                updateKeybindKeyboardPrev(w)
                return
            }
        }

        if (keybindMouseBindSkipFrames == 0) {
            val mx = mouseX.toDouble()
            val my = mouseY.toDouble()
            val overNumber = settingHits.firstOrNull {
                it.kind == "number" && inside(mx, my, it.x, it.y, it.w, it.h)
            }
            for (b in 0..7) {
                val now = GLFW.glfwGetMouseButton(w, b) == GLFW.GLFW_PRESS
                if (!now || keybindMousePrev[b]) continue
                if (b == 0) {
                    val hitKb = settingHits.filter { it.kind == "keybind" }.firstOrNull { inside(mx, my, it.x, it.y, it.w, it.h) }
                    if (hitKb != null) {
                        if (keybindListenTarget == null) {
                            keybindListenTarget = KeybindListenTarget(hitKb.module, hitKb.fieldName, hitKb.settingObj)
                            keybindMouseBindSkipFrames = 2
                            keybindKeyboardBindSkipFrames = 2
                        } else {
                            if (keybindListenTarget?.settingObj === hitKb.settingObj) {
                                keybindListenTarget = null
                            } else {
                                keybindListenTarget = KeybindListenTarget(hitKb.module, hitKb.fieldName, hitKb.settingObj)
                                keybindMouseBindSkipFrames = 2
                                keybindKeyboardBindSkipFrames = 2
                            }
                        }
                        prevLeftMouseDown = leftDown
                        updateKeybindMousePrev(w)
                        updateKeybindKeyboardPrev(w)
                        return
                    }
                }
                if (keybindListenTarget != null) {
                    if (b == 0 && overNumber != null) {
                        keybindListenTarget = null
                        break
                    }
                    val mKey = runCatching { InputConstants.Type.MOUSE.getOrCreate(b) }.getOrNull() ?: continue
                    if (applyKeybindWithKey(mKey)) {
                        prevLeftMouseDown = leftDown
                        updateKeybindMousePrev(w)
                        updateKeybindKeyboardPrev(w)
                        return
                    }
                }
            }
        }

        if (keybindListenTarget != null && keybindKeyboardBindSkipFrames == 0) {
            // GLFW keyboard key queries are only valid for [GLFW_KEY_SPACE..GLFW_KEY_LAST].
            // Polling 0..31 spams "Invalid key" on some runtimes.
            for (code in GLFW.GLFW_KEY_SPACE..GLFW.GLFW_KEY_LAST) {
                val now = GLFW.glfwGetKey(w, code) == GLFW.GLFW_PRESS
                if (!now || keybindKeyboardPrev[code]) continue
                if (code == GLFW.GLFW_KEY_ESCAPE) {
                    if (clearListeningKeybind()) {
                        prevLeftMouseDown = leftDown
                        updateKeybindMousePrev(w)
                        updateKeybindKeyboardPrev(w)
                        return
                    }
                }
                val k = runCatching { InputConstants.Type.KEYSYM.getOrCreate(code) }.getOrNull() ?: continue
                if (applyKeybindWithKey(k)) {
                    prevLeftMouseDown = leftDown
                    updateKeybindMousePrev(w)
                    updateKeybindKeyboardPrev(w)
                    return
                }
            }
        }

        for (hit in settingHits) {
            if (!inside(mouseX.toDouble(), mouseY.toDouble(), hit.x, hit.y, hit.w, hit.h)) continue
            when (hit.kind) {
                "keybind" -> {}
                "bool" -> {
                    if (justPressed) {
                        val cur = readBooleanSetting(hit.settingObj) ?: false
                        toggleBooleanSetting(hit.module, hit.fieldName, hit.settingObj, !cur)
                        init()
                    }
                }
                "color" -> {
                    if (justPressed) {
                        val colorVal = readColorInt(hit.settingObj) ?: 0xFFFFFFFF.toInt()
                        editingColorSetting = hit.settingObj
                        editingColorModule = hit.module
                        editingColorFieldName = hit.fieldName
                        val r = (colorVal shr 16) and 0xFF
                        val g = (colorVal shr 8) and 0xFF
                        val b = colorVal and 0xFF
                        val hsb = java.awt.Color.RGBtoHSB(r, g, b, null)
                        editHue = hsb[0].coerceIn(0f, 1f)
                        editSat = hsb[1].coerceIn(0f, 1f)
                        editVal = hsb[2].coerceIn(0f, 1f)
                        editAlpha = (readColorAlpha(hit.settingObj) / 255f).coerceIn(0f, 1f)
                        init()
                    }
                }
                "number" -> {
                    if (leftDown) {
                        activeSliderHit = hit
                        if (activeSliderLastTarget == null || activeSliderHit !== hit) {
                            activeSliderLastTarget = null
                        }
                        applySliderValue(hit, mouseX)
                    }
                }
            }
            prevLeftMouseDown = leftDown
            updateKeybindMousePrev(w)
            updateKeybindKeyboardPrev(w)
            return
        }

        if (!leftDown) {
            activeSliderHit = null
            activeSliderLastTarget = null
        }
        prevLeftMouseDown = leftDown
        updateKeybindMousePrev(w)
        updateKeybindKeyboardPrev(w)
    }

    private fun updateKeybindMousePrev(w: Long) {
        for (b in 0..7) {
            keybindMousePrev[b] = GLFW.glfwGetMouseButton(w, b) == GLFW.GLFW_PRESS
        }
    }

    private fun updateKeybindKeyboardPrev(w: Long) {
        for (code in GLFW.GLFW_KEY_SPACE..GLFW.GLFW_KEY_LAST) {
            keybindKeyboardPrev[code] = GLFW.glfwGetKey(w, code) == GLFW.GLFW_PRESS
        }
    }

    private fun applyKeybindWithKey(k: InputConstants.Key): Boolean {
        val t = keybindListenTarget ?: return false
        if (!setKeybindSetting(t.module, t.fieldName, t.settingObj, k)) return false
        keybindListenTarget = null
        OdinClient.moduleConfig.save()
        init()
        return true
    }

    private fun clearListeningKeybind(): Boolean {
        val unknown = runCatching { InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_UNKNOWN) }.getOrNull() ?: return false
        return applyKeybindWithKey(unknown)
    }

    private fun applySliderValue(hit: SettingHit, mouseX: Int) {
        val (min, max) = readNumberBounds(hit.module, hit.fieldName, hit.settingObj)
        val t = ((mouseX - hit.x).toDouble() / hit.w.toDouble()).coerceIn(0.0, 1.0)
        val rawTarget = min + (max - min) * t
        val target = stabilizeSliderTarget(hit, min, max, rawTarget)
        val last = activeSliderLastTarget
        if (last != null && kotlin.math.abs(last - target) < 1e-9) return
        activeSliderLastTarget = target
        setNumberSetting(hit.module, hit.fieldName, hit.settingObj, target)
    }

    private fun stabilizeSliderTarget(hit: SettingHit, min: Double, max: Double, raw: Double): Double {
        val clamped = raw.coerceIn(min, max)
        val current = readEffectiveNumberValue(hit.module, hit.fieldName, hit.settingObj)
        val intLikeCurrent = current != null && kotlin.math.abs(current - current.toInt().toDouble()) < 1e-6
        val intLikeBounds = kotlin.math.abs(min - min.toInt().toDouble()) < 1e-6 &&
            kotlin.math.abs(max - max.toInt().toDouble()) < 1e-6

        if (intLikeCurrent && intLikeBounds) {
            // Deterministic monotonic snapping for integer sliders to avoid boundary flip jitter.
            val snapped = min + kotlin.math.floor((clamped - min) + 1e-9)
            return snapped.coerceIn(min, max)
        }

        // For decimal sliders, quantize lightly to reduce visual write jitter.
        val q = kotlin.math.round(clamped * 100.0) / 100.0
        return q.coerceIn(min, max)
    }

    private fun drawColorPicker(gui: GuiGraphics, mouseX: Int, mouseY: Int) {
        val w = 260
        val h = 190
        val x = (width - w) / 2
        val y = (height - h) / 2
        gui.fill(x, y, x + w, y + h, 0xFF090909.toInt())
        gui.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF121212.toInt())
        drawTopAccentBar(gui, x + 2, y + 2, w - 4)
        gui.drawString(font, "Color Picker", x + 8, y + 8, 0xFFE7ECF4.toInt(), false)

        // SV square (saturation/value at current hue)
        val svX = x + 10
        val svY = y + 26
        val svW = 180
        val svH = 110
        drawSVSquare(gui, svX, svY, svW, svH)

        // Hue and alpha sliders (horizontal, like skeet pickers)
        val hueX = x + 10
        val hueY = y + 142
        val hueW = 180
        val hueH = 10
        drawHueBar(gui, hueX, hueY, hueW, hueH)

        val alphaX = x + 10
        val alphaY = y + 156
        val alphaW = 180
        val alphaH = 10
        drawAlphaBar(gui, alphaX, alphaY, alphaW, alphaH)

        val rgb = java.awt.Color.HSBtoRGB(editHue, editSat, editVal) and 0x00FFFFFF
        val preview = (((editAlpha * 255f).toInt().coerceIn(0, 255) shl 24) or rgb)
        gui.fill(x + 198, y + 26, x + 250, y + 62, 0xFF2A2A2A.toInt())
        gui.fill(x + 199, y + 27, x + 249, y + 61, preview)
        gui.drawString(font, "#%08X".format(preview), x + 198, y + 66, 0xFFC8D2E3.toInt(), false)
        gui.drawString(font, "H ${(editHue * 360f).toInt()}", x + 198, y + 78, 0xFFC8D2E3.toInt(), false)
        gui.drawString(font, "S ${(editSat * 100f).toInt()}%", x + 198, y + 88, 0xFFC8D2E3.toInt(), false)
        gui.drawString(font, "V ${(editVal * 100f).toInt()}%", x + 198, y + 98, 0xFFC8D2E3.toInt(), false)
        gui.drawString(font, "A ${(editAlpha * 100f).toInt()}%", x + 198, y + 108, 0xFFC8D2E3.toInt(), false)

        drawMiniButton(gui, x + 152, y + h - 18, 48, 14, "Apply", mouseX, mouseY)
        drawMiniButton(gui, x + 206, y + h - 18, 48, 14, "Close", mouseX, mouseY)
    }

    private fun drawSVSquare(gui: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
        // Balanced quality/perf: smoother than coarse blocks, still lightweight.
        val step = 3
        var yy = 0
        while (yy < h) {
            var xx = 0
            val v = (1f - yy.toFloat() / (h - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
            while (xx < w) {
                val s = (xx.toFloat() / (w - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
                val rgb = java.awt.Color.HSBtoRGB(editHue, s, v) and 0x00FFFFFF
                gui.fill(x + xx, y + yy, x + (xx + step).coerceAtMost(w), y + (yy + step).coerceAtMost(h), 0xFF000000.toInt() or rgb)
                xx += step
            }
            yy += step
        }
        val sx = x + (editSat * w).toInt().coerceIn(0, w - 1)
        val sy = y + ((1f - editVal) * h).toInt().coerceIn(0, h - 1)
        gui.fill(sx - 2, sy - 2, sx + 2, sy + 2, 0xFFFFFFFF.toInt())
    }

    private fun drawHueBar(gui: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
        val step = 2
        var i = 0
        while (i < w) {
            val hue = i.toFloat() / (w - 1).coerceAtLeast(1)
            val rgb = java.awt.Color.HSBtoRGB(hue, 1f, 1f) and 0x00FFFFFF
            gui.fill(x + i, y, x + (i + step).coerceAtMost(w), y + h, 0xFF000000.toInt() or rgb)
            i += step
        }
        val markerX = x + (editHue * w).toInt().coerceIn(0, w - 1)
        gui.fill(markerX - 1, y - 2, markerX + 1, y + h + 2, 0xFFFFFFFF.toInt())
    }

    private fun drawAlphaBar(gui: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
        val rgb = java.awt.Color.HSBtoRGB(editHue, editSat, editVal) and 0x00FFFFFF
        val step = 2
        var i = 0
        while (i < w) {
            val a = (i.toFloat() / (w - 1).coerceAtLeast(1) * 255f).toInt().coerceIn(0, 255)
            gui.fill(x + i, y, x + (i + step).coerceAtMost(w), y + h, (a shl 24) or rgb)
            i += step
        }
        val markerX = x + (editAlpha * w).toInt().coerceIn(0, w - 1)
        gui.fill(markerX - 1, y - 2, markerX + 1, y + h + 2, 0xFFFFFFFF.toInt())
    }

    private fun handleColorPickerDrag(mouseX: Int, mouseY: Int) {
        if (editingColorSetting == null && !editingGuiAccent) return
        val leftDown = GLFW.glfwGetMouseButton(minecraft!!.window.handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
        if (!leftDown) return

        val w = 260
        val h = 190
        val x = (width - w) / 2
        val y = (height - h) / 2

        val svX = x + 10
        val svY = y + 26
        val svW = 180
        val svH = 110
        if (inside(mouseX.toDouble(), mouseY.toDouble(), svX, svY, svW, svH)) {
            editSat = ((mouseX - svX).toFloat() / svW.toFloat()).coerceIn(0f, 1f)
            editVal = (1f - ((mouseY - svY).toFloat() / svH.toFloat())).coerceIn(0f, 1f)
            return
        }

        val hueX = x + 10
        val hueY = y + 142
        val hueW = 180
        val hueH = 10
        if (inside(mouseX.toDouble(), mouseY.toDouble(), hueX, hueY, hueW, hueH)) {
            editHue = ((mouseX - hueX).toFloat() / hueW.toFloat()).coerceIn(0f, 1f)
            return
        }

        val alphaX = x + 10
        val alphaY = y + 156
        val alphaW = 180
        val alphaH = 10
        if (inside(mouseX.toDouble(), mouseY.toDouble(), alphaX, alphaY, alphaW, alphaH)) {
            editAlpha = ((mouseX - alphaX).toFloat() / alphaW.toFloat()).coerceIn(0f, 1f)
        }
    }

    private fun getModuleSettings(module: Module): List<Pair<String, Any>> {
        return module.javaClass.declaredFields.mapNotNull { f ->
            f.isAccessible = true
            val v = runCatching { f.get(module) }.getOrNull() ?: return@mapNotNull null
            val typeName = v.javaClass.name.lowercase()
            if (!typeName.contains("clickgui.settings")) return@mapNotNull null
            // Hide noisy function-backed internal entries.
            if (f.name.contains("default", true) || f.name.contains("action", true)) return@mapNotNull null
            f.name to v
        }
    }

    private fun isKeybindSetting(obj: Any): Boolean = obj.javaClass.name.contains("KeybindSetting", ignoreCase = true)

    private fun getKeyFromKeybind(setting: Any): InputConstants.Key? {
        for (m in setting.javaClass.methods) {
            if (m.parameterCount != 0) continue
            if (m.name !in setOf("get", "getValue", "getKey", "getBoundKey", "asKey", "asInputKey")) continue
            val v = runCatching { m.invoke(setting) }.getOrNull() ?: continue
            if (v is InputConstants.Key) return v
        }
        for (f in setting.javaClass.declaredFields) {
            if (!f.name.equals("value", true) && !f.name.equals("key", true)) continue
            f.isAccessible = true
            val v = runCatching { f.get(setting) }.getOrNull() ?: continue
            if (v is InputConstants.Key) return v
        }
        return null
    }

    private fun isKeybindUnset(key: InputConstants.Key): Boolean = key.value == GLFW.GLFW_KEY_UNKNOWN

    private fun shortKeybindLabel(key: InputConstants.Key): String {
        if (key.value == GLFW.GLFW_KEY_UNKNOWN) return "-"
        if (key.value <= 7) {
            return when (key.value) {
                0 -> "L"
                1 -> "R"
                2 -> "M"
                else -> "B${key.value}"
            }
        }
        val fromDisplay = runCatching {
            val m = key.javaClass.methods.firstOrNull { it.name == "getDisplayName" && it.parameterCount == 0 }
            (m?.invoke(key) as? Component)?.string?.trim()
        }.getOrNull()
        if (!fromDisplay.isNullOrBlank() && fromDisplay.length <= 4) return fromDisplay
        var tail = key.toString().lowercase().substringAfterLast('.')
        if (tail == "unknown" || tail.isEmpty()) return "-"
        if (tail.length in 1..2) return tail.replace("_", "").uppercase()
        if (tail.length <= 3) return tail.replace("_", "").uppercase()
        if (tail.startsWith("f") && tail.length <= 3) return tail.uppercase()
        return tail.split("_", ".").lastOrNull()?.uppercase()?.take(3) ?: "?"
    }

    private fun setKeybindSetting(module: Module, fieldName: String, settingObj: Any, k: InputConstants.Key): Boolean {
        if (setModulePropertyKeybind(module, fieldName, k)) {
            return true
        }
        val keyCls = InputConstants.Key::class.java
        for (m in settingObj.javaClass.methods) {
            if (m.parameterCount != 1) continue
            if (m.name !in setOf("set", "setValue", "setKey", "setBoundKey", "setInputKey", "setFromCode")) continue
            when {
                m.parameterTypes[0] == keyCls ->
                    if (runCatching { m.invoke(settingObj, k); true }.getOrDefault(false)) return true
                m.parameterTypes[0] == Int::class.javaPrimitiveType || m.parameterTypes[0] == Int::class.javaObjectType ->
                    if (runCatching { m.invoke(settingObj, k.value); true }.getOrDefault(false)) return true
            }
        }
        for (f in settingObj.javaClass.declaredFields) {
            f.isAccessible = true
            if (f.name.equals("value", true) || f.name.equals("key", true)) {
                if (f.type == keyCls && runCatching { f.set(settingObj, k); true }.getOrDefault(false)) return true
            }
        }
        if (setModulePropertyGlfwCode(module, fieldName, k.value)) return true
        return false
    }

    private fun setModulePropertyKeybind(module: Module, fieldName: String, key: InputConstants.Key): Boolean {
        val base = fieldName.removeSuffix("\$delegate")
        val methodName = "set" + base.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val m = (module.javaClass.methods + module.javaClass.declaredMethods).firstOrNull {
            it.name == methodName && it.parameterCount == 1 && it.parameterTypes[0] == InputConstants.Key::class.java
        } ?: return false
        return runCatching { m.isAccessible = true; m.invoke(module, key); true }.getOrDefault(false)
    }

    private fun setModulePropertyGlfwCode(module: Module, fieldName: String, code: Int): Boolean {
        val base = fieldName.removeSuffix("\$delegate")
        val methodName = "set" + base.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val methods = (module.javaClass.methods + module.javaClass.declaredMethods).filter { it.name == methodName && it.parameterCount == 1 }
        for (m in methods) {
            val p = m.parameterTypes[0]
            if (p == Int::class.javaPrimitiveType || p == java.lang.Integer::class.java) {
                if (runCatching { m.isAccessible = true; m.invoke(module, code); true }.getOrDefault(false)) return true
            }
        }
        return false
    }

    private fun settingDisplayName(settingObj: Any, fallbackFieldName: String): String {
        val methods = settingObj.javaClass.methods
        val nameMethod = methods.firstOrNull {
            it.parameterCount == 0 && it.returnType == String::class.java &&
                (it.name.equals("getName", true) || it.name.equals("name", true) || it.name.equals("getDisplayName", true))
        }
        val fromMethod = nameMethod?.let { runCatching { it.invoke(settingObj) as? String }.getOrNull() }
        if (!fromMethod.isNullOrBlank()) return fromMethod

        val field = settingObj.javaClass.declaredFields.firstOrNull {
            it.type == String::class.java && (it.name.equals("name", true) || it.name.equals("displayName", true))
        }
        if (field != null) {
            field.isAccessible = true
            val fromField = runCatching { field.get(settingObj) as? String }.getOrNull()
            if (!fromField.isNullOrBlank()) return fromField
        }

        return fallbackFieldName.removeSuffix("\$delegate")
    }

    private fun readBooleanSetting(settingObj: Any): Boolean? {
        val methods = settingObj.javaClass.methods
        methods.firstOrNull {
            it.parameterCount == 0 &&
                (it.returnType == java.lang.Boolean.TYPE || it.returnType == java.lang.Boolean::class.java) &&
                it.name.equals("get", true)
        }?.let {
            return runCatching { it.invoke(settingObj) as Boolean }.getOrNull()
        }
        methods.firstOrNull {
            it.parameterCount == 0 &&
                (it.returnType == java.lang.Boolean.TYPE || it.returnType == java.lang.Boolean::class.java) &&
                it.name.equals("getValue", true)
        }?.let {
            return runCatching { it.invoke(settingObj) as Boolean }.getOrNull()
        }
        methods.firstOrNull {
            it.parameterCount == 0 &&
                (it.returnType == java.lang.Boolean.TYPE || it.returnType == java.lang.Boolean::class.java) &&
                it.name.equals("isEnabled", true)
        }?.let {
            return runCatching { it.invoke(settingObj) as Boolean }.getOrNull()
        }
        return null
    }

    private fun readNumberSetting(settingObj: Any): String? {
        if (isKeybindSetting(settingObj)) return null
        val methods = settingObj.javaClass.methods
        methods.firstOrNull { it.parameterCount == 0 && it.name.equals("get", true) }?.let {
            val v = runCatching { it.invoke(settingObj) }.getOrNull() ?: return@let
            return v.toString()
        }
        methods.firstOrNull { it.parameterCount == 0 && it.name.equals("getValue", true) }?.let {
            val v = runCatching { it.invoke(settingObj) }.getOrNull() ?: return@let
            return v.toString()
        }
        return null
    }

    private fun readNumberValue(settingObj: Any): Double? {
        val methods = settingObj.javaClass.methods
        val getter = methods.firstOrNull {
            it.parameterCount == 0 && (it.name.equals("get", true) || it.name.equals("getValue", true))
        } ?: return null
        val v = runCatching { getter.invoke(settingObj) }.getOrNull() ?: return null
        return (v as? Number)?.toDouble()
    }

    private fun readEffectiveNumberValue(module: Module, fieldName: String, settingObj: Any): Double? {
        val fromSetting = readNumberValue(settingObj)
        if (fromSetting != null) return fromSetting
        val base = fieldName.removeSuffix("\$delegate")
        val getterName = "get" + base.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val gm = module.javaClass.methods.firstOrNull { it.name == getterName && it.parameterCount == 0 }
        val fromModule = runCatching { (gm?.invoke(module) as? Number)?.toDouble() }.getOrNull()
        return fromModule
    }

    private fun readNumberBounds(module: Module, fieldName: String, settingObj: Any): Pair<Double, Double> {
        val cacheKey = "${module.javaClass.name}#$fieldName"
        numberBoundsCache[cacheKey]?.let { return it }

        val methods = settingObj.javaClass.methods + settingObj.javaClass.declaredMethods
        val minMethod = methods.firstOrNull {
            it.parameterCount == 0 &&
                (it.name.equals("getMin", true) || it.name.equals("min", true) ||
                    it.name.equals("getMinimum", true) || it.name.equals("minimum", true) ||
                    it.name.equals("getLowerBound", true))
        }
        val maxMethod = methods.firstOrNull {
            it.parameterCount == 0 &&
                (it.name.equals("getMax", true) || it.name.equals("max", true) ||
                    it.name.equals("getMaximum", true) || it.name.equals("maximum", true) ||
                    it.name.equals("getUpperBound", true))
        }
        val minM = runCatching { (minMethod?.invoke(settingObj) as? Number)?.toDouble() }.getOrNull()
        val maxM = runCatching { (maxMethod?.invoke(settingObj) as? Number)?.toDouble() }.getOrNull()
        if (minM != null && maxM != null && maxM > minM) {
            val out = minM to maxM
            numberBoundsCache[cacheKey] = out
            return out
        }

        // Range object fallback: getRange()/range with min/max fields.
        val rangeObj = runCatching {
            methods.firstOrNull { it.parameterCount == 0 && (it.name.equals("getRange", true) || it.name.equals("range", true)) }
                ?.invoke(settingObj)
        }.getOrNull()
        if (rangeObj != null) {
            val rf = rangeObj.javaClass.declaredFields
            val rMin = rf.firstOrNull { it.name.contains("min", true) || it.name.contains("lower", true) }
            val rMax = rf.firstOrNull { it.name.contains("max", true) || it.name.contains("upper", true) }
            val minR = runCatching { rMin?.apply { isAccessible = true }?.get(rangeObj) as? Number }.getOrNull()?.toDouble()
            val maxR = runCatching { rMax?.apply { isAccessible = true }?.get(rangeObj) as? Number }.getOrNull()?.toDouble()
            if (minR != null && maxR != null && maxR > minR) {
                val out = minR to maxR
                numberBoundsCache[cacheKey] = out
                return out
            }
        }

        // Superclass field scan fallback.
        var cls: Class<*>? = settingObj.javaClass
        while (cls != null) {
            val fields = cls.declaredFields
            val minF = fields.firstOrNull {
                it.name.equals("min", true) || it.name.equals("minimum", true) || it.name.equals("lowerBound", true)
            }
            val maxF = fields.firstOrNull {
                it.name.equals("max", true) || it.name.equals("maximum", true) || it.name.equals("upperBound", true)
            }
            val minV = runCatching { minF?.apply { isAccessible = true }?.get(settingObj) as? Number }.getOrNull()?.toDouble()
            val maxV = runCatching { maxF?.apply { isAccessible = true }?.get(settingObj) as? Number }.getOrNull()?.toDouble()
            if (minV != null && maxV != null && maxV > minV) {
                val out = minV to maxV
                numberBoundsCache[cacheKey] = out
                return out
            }
            // Broad name scan for number-like min/max fields.
            val broadMin = fields.firstOrNull { f ->
                val n = f.name.lowercase()
                (n.contains("min") || n.contains("lower")) &&
                    (f.type == java.lang.Integer.TYPE || f.type == java.lang.Integer::class.java ||
                        f.type == java.lang.Long.TYPE || f.type == java.lang.Long::class.java ||
                        f.type == java.lang.Float.TYPE || f.type == java.lang.Float::class.java ||
                        f.type == java.lang.Double.TYPE || f.type == java.lang.Double::class.java)
            }
            val broadMax = fields.firstOrNull { f ->
                val n = f.name.lowercase()
                (n.contains("max") || n.contains("upper")) &&
                    (f.type == java.lang.Integer.TYPE || f.type == java.lang.Integer::class.java ||
                        f.type == java.lang.Long.TYPE || f.type == java.lang.Long::class.java ||
                        f.type == java.lang.Float.TYPE || f.type == java.lang.Float::class.java ||
                        f.type == java.lang.Double.TYPE || f.type == java.lang.Double::class.java)
            }
            val bMin = runCatching { broadMin?.apply { isAccessible = true }?.get(settingObj) as? Number }.getOrNull()?.toDouble()
            val bMax = runCatching { broadMax?.apply { isAccessible = true }?.get(settingObj) as? Number }.getOrNull()?.toDouble()
            if (bMin != null && bMax != null && bMax > bMin) {
                val out = bMin to bMax
                numberBoundsCache[cacheKey] = out
                return out
            }
            cls = cls.superclass
        }

        // toString fallback: "...min=35..., max=41..."
        runCatching {
            val s = settingObj.toString()
            val minR = Regex("min(?:imum)?\\s*[=:]\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(s)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            val maxR = Regex("max(?:imum)?\\s*[=:]\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(s)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            if (minR != null && maxR != null && maxR > minR) {
                val out = minR to maxR
                numberBoundsCache[cacheKey] = out
                return out
            }
        }

        val fallback = 0.0 to 100.0
        numberBoundsCache[cacheKey] = fallback
        return fallback
    }

    private fun setNumberSetting(module: Module, fieldName: String, settingObj: Any, value: Double) {
        val (minB, maxB) = readNumberBounds(module, fieldName, settingObj)
        val boundedValue = value.coerceIn(minB, maxB)
        val beforeSetting = readNumberValue(settingObj)
        val beforeModule = readModuleNumberValue(module, fieldName)

        fun changed(): Boolean {
            val afterSetting = readNumberValue(settingObj)
            val afterModule = readModuleNumberValue(module, fieldName)
            val settingChanged = beforeSetting != null && afterSetting != null && kotlin.math.abs(afterSetting - beforeSetting) > 1e-6
            val moduleChanged = beforeModule != null && afterModule != null && kotlin.math.abs(afterModule - beforeModule) > 1e-6
            val becameVisible = (beforeSetting == null && afterSetting != null) || (beforeModule == null && afterModule != null)
            return settingChanged || moduleChanged || becameVisible
        }

        // 1) Direct setting object mutators first (most reliable visual update path).
        if (writeNumberUnchecked(module, fieldName, settingObj, boundedValue) && changed()) {
            OdinClient.moduleConfig.save()
            return
        }

        // 2) Standard module numeric setter fallback.
        if (setModulePropertyNumber(module, fieldName, boundedValue) && changed()) {
            OdinClient.moduleConfig.save()
            return
        }

        // 3) Text-backed number variants (some Odin builds require specific string formats).
        for (txt in numberTextVariants(boundedValue)) {
            val wroteText = invokeSyntheticTextInputNumberSetter(settingObj, txt) || setModulePropertyString(module, fieldName, txt)
            if (wroteText && changed()) {
                OdinClient.moduleConfig.save()
                return
            }
        }

        // 4) Last resort: try direct module field write by property base name.
        if (setModuleNumberField(module, fieldName, boundedValue) && changed()) {
            OdinClient.moduleConfig.save()
            return
        }

        // 5) Exhaustive verified fallback: keep trying plausible mutators until value actually changes.
        val methods = (settingObj.javaClass.methods + settingObj.javaClass.declaredMethods)
            .distinctBy { it.name + "#" + it.parameterTypes.joinToString(",") { p -> p.name } }
        val textValue = numberTextVariants(boundedValue).first()
        for (m in methods) {
            if (m.parameterCount != 1) continue
            val n = m.name.lowercase()
            if (!(n.contains("set") || n.contains("value") || n.contains("update") || n.contains("input") || n.contains("process"))) continue
            val arg: Any = when (m.parameterTypes[0]) {
                java.lang.Integer.TYPE, java.lang.Integer::class.java -> boundedValue.toInt()
                java.lang.Long.TYPE, java.lang.Long::class.java -> boundedValue.toLong()
                java.lang.Float.TYPE, java.lang.Float::class.java -> boundedValue.toFloat()
                java.lang.Double.TYPE, java.lang.Double::class.java -> boundedValue
                String::class.java -> textValue
                else -> continue
            }
            runCatching {
                m.isAccessible = true
                m.invoke(settingObj, arg)
            }
            if (changed()) {
                OdinClient.moduleConfig.save()
                return
            }
        }
    }

    private fun readModuleNumberValue(module: Module, fieldName: String): Double? {
        val base = fieldName.removeSuffix("\$delegate")
        val getterName = "get" + base.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val gm = module.javaClass.methods.firstOrNull { it.name == getterName && it.parameterCount == 0 }
        return runCatching { (gm?.invoke(module) as? Number)?.toDouble() }.getOrNull()
    }

    private fun writeNumberUnchecked(module: Module, fieldName: String, settingObj: Any, value: Double): Boolean {
        val methods = settingObj.javaClass.methods + settingObj.javaClass.declaredMethods
        fun isSupportedNumberSetterType(t: Class<*>): Boolean {
            return t == java.lang.Integer.TYPE || t == java.lang.Integer::class.java ||
                t == java.lang.Long.TYPE || t == java.lang.Long::class.java ||
                t == java.lang.Float.TYPE || t == java.lang.Float::class.java ||
                t == java.lang.Double.TYPE || t == java.lang.Double::class.java ||
                t == String::class.java
        }
        val setter = methods.firstOrNull {
            it.parameterCount == 1 &&
                (it.name.equals("set", true) || it.name.equals("setValue", true)) &&
                isSupportedNumberSetterType(it.parameterTypes[0])
        }
        var wrote = false
        if (setter != null) {
            runCatching {
                setter.isAccessible = true
                when (setter.parameterTypes[0]) {
                    java.lang.Integer.TYPE, java.lang.Integer::class.java -> { setter.invoke(settingObj, value.toInt()); wrote = true }
                    java.lang.Long.TYPE, java.lang.Long::class.java -> { setter.invoke(settingObj, value.toLong()); wrote = true }
                    java.lang.Float.TYPE, java.lang.Float::class.java -> { setter.invoke(settingObj, value.toFloat()); wrote = true }
                    java.lang.Double.TYPE, java.lang.Double::class.java -> { setter.invoke(settingObj, value); wrote = true }
                    String::class.java -> { setter.invoke(settingObj, "%.2f".format(value)); wrote = true }
                }
            }
        }
        if (wrote) return true

        // Odin synthetic text-input mutators (common for number settings).
        if (invokeSyntheticTextInputNumberSetter(settingObj, "%.2f".format(value))) return true

        // Broad mutator scan for odd NumberSetting APIs.
        val mutators = methods.distinctBy { it.name + "#" + it.parameterTypes.joinToString(",") { p -> p.name } }
            .filter { m ->
                m.parameterCount in 1..2 &&
                    (m.name.contains("set", true) || m.name.contains("update", true) || m.name.contains("input", true))
            }
        for (m in mutators) {
            runCatching {
                m.isAccessible = true
                when (m.parameterCount) {
                    1 -> when (m.parameterTypes[0]) {
                        java.lang.Integer.TYPE, java.lang.Integer::class.java -> m.invoke(settingObj, value.toInt())
                        java.lang.Long.TYPE, java.lang.Long::class.java -> m.invoke(settingObj, value.toLong())
                        java.lang.Float.TYPE, java.lang.Float::class.java -> m.invoke(settingObj, value.toFloat())
                        java.lang.Double.TYPE, java.lang.Double::class.java -> m.invoke(settingObj, value)
                        String::class.java -> m.invoke(settingObj, "%.2f".format(value))
                        else -> return@runCatching
                    }
                    2 -> {
                        // Kotlin synthetic access$setTextInputValue(target, text)
                        val p0 = m.parameterTypes[0]
                        val p1 = m.parameterTypes[1]
                        if (p0.isAssignableFrom(settingObj.javaClass) && p1 == String::class.java) {
                            m.invoke(null, settingObj, "%.2f".format(value))
                        } else return@runCatching
                    }
                }
                wrote = true
            }
            if (wrote) return true
        }

        val textSetter = methods.firstOrNull { m ->
            m.parameterCount == 1 &&
                m.parameterTypes[0] == String::class.java &&
                (m.name.contains("setTextInputValue", true) || m.name.equals("setValue", true) || m.name.equals("set", true))
        }
        if (textSetter != null) {
            runCatching {
                textSetter.isAccessible = true
                textSetter.invoke(settingObj, "%.2f".format(value))
                wrote = true
            }
        }
        if (wrote) return true

        val numberField = settingObj.javaClass.declaredFields.firstOrNull {
            val n = it.name.lowercase()
            n == "value" || n == "current" || n == "val"
        }
        if (numberField != null) {
            runCatching {
                numberField.isAccessible = true
                when (numberField.type) {
                    java.lang.Integer.TYPE, java.lang.Integer::class.java -> numberField.set(settingObj, value.toInt())
                    java.lang.Long.TYPE, java.lang.Long::class.java -> numberField.set(settingObj, value.toLong())
                    java.lang.Float.TYPE, java.lang.Float::class.java -> numberField.set(settingObj, value.toFloat())
                    java.lang.Double.TYPE, java.lang.Double::class.java -> numberField.set(settingObj, value)
                }
                wrote = true
            }
        }
        return wrote
    }

    private fun invokeSyntheticTextInputNumberSetter(settingObj: Any, text: String): Boolean {
        var cls: Class<*>? = settingObj.javaClass
        while (cls != null) {
            val declared = cls.declaredMethods

            // Pattern: static access$setTextInputValue(SettingClass, String)
            val accessLike = declared.firstOrNull { m ->
                java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.parameterCount == 2 &&
                    m.name.contains("setTextInputValue", true) &&
                    m.parameterTypes[0].isAssignableFrom(settingObj.javaClass) &&
                    m.parameterTypes[1] == String::class.java
            }
            if (accessLike != null) {
                val ok = runCatching {
                    accessLike.isAccessible = true
                    accessLike.invoke(null, settingObj, text)
                    true
                }.getOrDefault(false)
                if (ok) return true
            }

            // Pattern: processSetTextInputValue(SettingClass, String)
            val processLike = declared.firstOrNull { m ->
                java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.parameterCount == 2 &&
                    m.name.contains("processSetTextInputValue", true) &&
                    m.parameterTypes[0].isAssignableFrom(settingObj.javaClass) &&
                    m.parameterTypes[1] == String::class.java
            }
            if (processLike != null) {
                val ok = runCatching {
                    processLike.isAccessible = true
                    processLike.invoke(null, settingObj, text)
                    true
                }.getOrDefault(false)
                if (ok) return true
            }

            // Instance style: setTextInputValue(String)
            val instanceSetter = declared.firstOrNull { m ->
                m.parameterCount == 1 &&
                    m.parameterTypes[0] == String::class.java &&
                    m.name.contains("setTextInputValue", true)
            }
            if (instanceSetter != null) {
                val ok = runCatching {
                    instanceSetter.isAccessible = true
                    instanceSetter.invoke(settingObj, text)
                    true
                }.getOrDefault(false)
                if (ok) return true
            }

            // Very broad synthetic/static pattern support:
            // any static method name containing TextInputValue with a compatible setting arg and String arg.
            for (m in declared) {
                if (!java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
                if (!m.name.contains("TextInputValue", true)) continue
                if (m.parameterCount < 2) continue
                val params = m.parameterTypes
                val settingIdx = params.indexOfFirst { it.isAssignableFrom(settingObj.javaClass) }
                val stringIdx = params.indexOfFirst { it == String::class.java }
                if (settingIdx < 0 || stringIdx < 0) continue
                val args = Array<Any?>(params.size) { idx ->
                    when {
                        idx == settingIdx -> settingObj
                        idx == stringIdx -> text
                        params[idx] == java.lang.Boolean.TYPE || params[idx] == java.lang.Boolean::class.java -> false
                        params[idx] == java.lang.Integer.TYPE || params[idx] == java.lang.Integer::class.java -> 0
                        params[idx] == java.lang.Long.TYPE || params[idx] == java.lang.Long::class.java -> 0L
                        params[idx] == java.lang.Float.TYPE || params[idx] == java.lang.Float::class.java -> 0f
                        params[idx] == java.lang.Double.TYPE || params[idx] == java.lang.Double::class.java -> 0.0
                        else -> null
                    }
                }
                val ok = runCatching {
                    m.isAccessible = true
                    m.invoke(null, *args)
                    true
                }.getOrDefault(false)
                if (ok) return true
            }

            cls = cls.superclass
        }
        return false
    }

    private fun numberTextVariants(value: Double): List<String> {
        val roundedInt = value.toInt()
        val isIntish = kotlin.math.abs(value - roundedInt.toDouble()) < 1e-6
        val list = mutableListOf<String>()
        if (isIntish) list += roundedInt.toString()
        list += "%.2f".format(value)
        list += "%.1f".format(value)
        list += value.toString()
        return list.distinct()
    }

    private fun setModuleNumberField(module: Module, fieldName: String, value: Double): Boolean {
        val base = fieldName.removeSuffix("\$delegate")
        var cls: Class<*>? = module.javaClass
        while (cls != null) {
            val f = cls.declaredFields.firstOrNull { it.name == base } ?: run { cls = cls.superclass; continue }
            return runCatching {
                f.isAccessible = true
                when (f.type) {
                    java.lang.Integer.TYPE, java.lang.Integer::class.java -> f.set(module, value.toInt())
                    java.lang.Long.TYPE, java.lang.Long::class.java -> f.set(module, value.toLong())
                    java.lang.Float.TYPE, java.lang.Float::class.java -> f.set(module, value.toFloat())
                    java.lang.Double.TYPE, java.lang.Double::class.java -> f.set(module, value)
                    else -> return false
                }
                true
            }.getOrDefault(false)
        }
        return false
    }

    private fun adjustNumberSetting(module: Module, fieldName: String, settingObj: Any, delta: Double) {
        val current = readNumberValue(settingObj) ?: return
        val target = current + delta

        // Prefer module property setter if available (works for delegated vars).
        if (setModulePropertyNumber(module, fieldName, target)) return

        val methods = settingObj.javaClass.methods
        val setter = methods.firstOrNull {
            it.parameterCount == 1 && (it.name.equals("set", true) || it.name.equals("setValue", true))
        } ?: return

        val pt = setter.parameterTypes[0]
        runCatching {
            when (pt) {
                java.lang.Integer.TYPE, java.lang.Integer::class.java -> setter.invoke(settingObj, target.toInt())
                java.lang.Long.TYPE, java.lang.Long::class.java -> setter.invoke(settingObj, target.toLong())
                java.lang.Float.TYPE, java.lang.Float::class.java -> setter.invoke(settingObj, target.toFloat())
                java.lang.Double.TYPE, java.lang.Double::class.java -> setter.invoke(settingObj, target)
            }
        }
    }

    private fun readColorInt(settingObj: Any): Int? {
        val methods = settingObj.javaClass.methods
        val getter = methods.firstOrNull {
            it.parameterCount == 0 && (it.name.equals("get", true) || it.name.equals("getValue", true))
        } ?: return null
        val v = runCatching { getter.invoke(settingObj) }.getOrNull() ?: return null
        return when (v) {
            is Number -> v.toInt() and 0xFFFFFF
            else -> {
                // Method-based color objects (getRed/getGreen/getBlue)
                val rM = v.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals("getRed", true) }
                val gM = v.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals("getGreen", true) }
                val bM = v.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals("getBlue", true) }
                if (rM != null && gM != null && bM != null) {
                    val r = (rM.invoke(v) as Number).toInt().coerceIn(0, 255)
                    val g = (gM.invoke(v) as Number).toInt().coerceIn(0, 255)
                    val b = (bM.invoke(v) as Number).toInt().coerceIn(0, 255)
                    return (r shl 16) or (g shl 8) or b
                }

                // Field-based color objects
                val fields = v.javaClass.declaredFields
                val rf = fields.firstOrNull { it.name.equals("red", true) || it.name.equals("r", true) }
                val gf = fields.firstOrNull { it.name.equals("green", true) || it.name.equals("g", true) }
                val bf = fields.firstOrNull { it.name.equals("blue", true) || it.name.equals("b", true) }
                if (rf != null && gf != null && bf != null) {
                    rf.isAccessible = true; gf.isAccessible = true; bf.isAccessible = true
                    val r = (rf.get(v) as Number).toInt().coerceIn(0, 255)
                    val g = (gf.get(v) as Number).toInt().coerceIn(0, 255)
                    val b = (bf.get(v) as Number).toInt().coerceIn(0, 255)
                    return (r shl 16) or (g shl 8) or b
                }

                // Parse toString fallback: Color(red=85,green=255,blue=255,alpha=255)
                val s = v.toString()
                val regex = Regex("red=(\\d+),green=(\\d+),blue=(\\d+)")
                val m = regex.find(s)
                if (m != null) {
                    val r = m.groupValues[1].toInt().coerceIn(0, 255)
                    val g = m.groupValues[2].toInt().coerceIn(0, 255)
                    val b = m.groupValues[3].toInt().coerceIn(0, 255)
                    return (r shl 16) or (g shl 8) or b
                }

                null
            }
        }
    }

    private fun setColorInt(module: Module, fieldName: String, settingObj: Any, rgb: Int): Boolean {
        if (setModulePropertyColor(module, fieldName, rgb)) return true
        if (setModulePropertyNumber(module, fieldName, rgb.toDouble())) return true
        return setColorInt(settingObj, rgb)
    }

    private fun setColorInt(settingObj: Any, rgb: Int): Boolean {
        val before = readColorInt(settingObj)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val a = (rgb ushr 24) and 0xFF

        // 1) Direct color-style setters on the setting object itself.
        val direct = settingObj.javaClass.methods
        val getter = (settingObj.javaClass.methods + settingObj.javaClass.declaredMethods).firstOrNull {
            it.parameterCount == 0 && (it.name.equals("getValue", true) || it.name.equals("get", true) || it.name.equals("getDefault", true))
        }
        val baseColorObj = runCatching { getter?.invoke(settingObj) }.getOrNull()

        // Prefer explicit Color overloads (e.g. setValue(Color)) over setValue(Object).
        val colorSetter = direct.firstOrNull {
            it.parameterCount == 1 &&
                (it.name.equals("setValue", true) || it.name.equals("set", true)) &&
                it.parameterTypes[0].simpleName.contains("Color", ignoreCase = true)
        }
        if (colorSetter != null) {
            val targetType = colorSetter.parameterTypes[0]
            val obj = buildColorObject(targetType, baseColorObj, r, g, b, a, rgb)
            if (obj != null) {
                val ok = runCatching { colorSetter.invoke(settingObj, obj); true }.getOrDefault(false)
                if (ok && readColorInt(settingObj) != before) return true
            }
        }

        direct.firstOrNull {
            it.parameterCount == 4 &&
                it.parameterTypes.all { p -> p == Int::class.javaPrimitiveType || p == Int::class.java } &&
                (it.name.equals("setColor", true) || it.name.equals("setRgb", true) || it.name.equals("setRgba", true))
        }?.let {
            val ok = runCatching { it.invoke(settingObj, r, g, b, a); true }.getOrDefault(false)
            if (ok && readColorInt(settingObj) != before) return true
        }
        direct.firstOrNull {
            it.parameterCount == 3 &&
                it.parameterTypes.all { p -> p == Int::class.javaPrimitiveType || p == Int::class.java } &&
                (it.name.equals("setColor", true) || it.name.equals("setRgb", true))
        }?.let {
            val ok = runCatching { it.invoke(settingObj, r, g, b); true }.getOrDefault(false)
            if (ok && readColorInt(settingObj) != before) return true
        }

        val methods = settingObj.javaClass.methods + settingObj.javaClass.declaredMethods
        val setter = methods.firstOrNull {
            it.parameterCount == 1 &&
                (it.name.equals("set", true) || it.name.equals("setValue", true)) &&
                it.parameterTypes[0] != Any::class.java
        } ?: return false

        val pt = setter.parameterTypes[0]
        var success = false
        runCatching {
            setter.isAccessible = true
            when (pt) {
                java.lang.Integer.TYPE, java.lang.Integer::class.java -> {
                    setter.invoke(settingObj, rgb)
                    success = true
                }
                java.lang.Long.TYPE, java.lang.Long::class.java -> {
                    setter.invoke(settingObj, rgb.toLong())
                    success = true
                }
                else -> {
                    // Try object-based color setters (e.g. custom Color(r,g,b,a))
                    val ctor = pt.constructors.firstOrNull { c ->
                        c.parameterCount == 4 &&
                            c.parameterTypes.all { p ->
                                p == java.lang.Integer.TYPE || p == java.lang.Integer::class.java
                            }
                    } ?: pt.constructors.firstOrNull { c ->
                        c.parameterCount == 3 &&
                            c.parameterTypes.all { p ->
                                p == java.lang.Integer.TYPE || p == java.lang.Integer::class.java
                            }
                    }

                    if (ctor != null) {
                        val colorObj = if (ctor.parameterCount == 4) {
                            ctor.newInstance(r, g, b, a)
                        } else {
                            ctor.newInstance(r, g, b)
                        }
                        setter.invoke(settingObj, colorObj)
                        success = true
                        return@runCatching
                    }

                    // Try Kotlin data-class copy(...) on existing color object.
                    val getter = methods.firstOrNull {
                        it.parameterCount == 0 && (it.name.equals("get", true) || it.name.equals("getValue", true))
                    }
                    val cur = getter?.invoke(settingObj)
                    if (cur != null) {
                        val copy4 = cur.javaClass.methods.firstOrNull { m ->
                            m.name == "copy" &&
                                m.parameterCount == 4 &&
                                m.parameterTypes.all { p ->
                                    p == java.lang.Integer.TYPE || p == java.lang.Integer::class.java
                                }
                        }
                        if (copy4 != null) {
                            val copied = copy4.invoke(cur, r, g, b, a)
                            setter.invoke(settingObj, copied)
                            success = true
                            return@runCatching
                        }

                        val copy3 = cur.javaClass.methods.firstOrNull { m ->
                            m.name == "copy" &&
                                m.parameterCount == 3 &&
                                m.parameterTypes.all { p ->
                                    p == java.lang.Integer.TYPE || p == java.lang.Integer::class.java
                                }
                        }
                        if (copy3 != null) {
                            val copied = copy3.invoke(cur, r, g, b)
                            setter.invoke(settingObj, copied)
                            success = true
                            return@runCatching
                        }

                        // Last resort: mutate fields then set value.
                        val fields = cur.javaClass.declaredFields
                        fields.firstOrNull { it.name.equals("red", true) || it.name.equals("r", true) }?.let { f -> f.isAccessible = true; f.set(cur, r) }
                        fields.firstOrNull { it.name.equals("green", true) || it.name.equals("g", true) }?.let { f -> f.isAccessible = true; f.set(cur, g) }
                        fields.firstOrNull { it.name.equals("blue", true) || it.name.equals("b", true) }?.let { f -> f.isAccessible = true; f.set(cur, b) }
                        fields.firstOrNull { it.name.equals("alpha", true) || it.name.equals("a", true) }?.let { f -> f.isAccessible = true; f.set(cur, a) }
                        setter.invoke(settingObj, cur)
                        success = true
                    }
                }
            }
        }
        if (success && readColorInt(settingObj) != before) return true

        // 2) Direct field mutation fallback on setting object itself.
        runCatching {
            val fields = settingObj.javaClass.declaredFields
            fields.firstOrNull { it.name.equals("value", true) || it.name.equals("color", true) || it.name.equals("current", true) }?.let { f ->
                f.isAccessible = true
                when (f.type) {
                    java.lang.Integer.TYPE, java.lang.Integer::class.java -> {
                        f.set(settingObj, rgb)
                        return true
                    }
                    java.lang.Long.TYPE, java.lang.Long::class.java -> {
                        f.set(settingObj, rgb.toLong())
                        return true
                    }
                    else -> {
                        val cur = f.get(settingObj) ?: return@let
                        val cFields = cur.javaClass.declaredFields
                        cFields.firstOrNull { it.name.equals("red", true) || it.name.equals("r", true) }?.let { cf -> cf.isAccessible = true; cf.set(cur, r) }
                        cFields.firstOrNull { it.name.equals("green", true) || it.name.equals("g", true) }?.let { cf -> cf.isAccessible = true; cf.set(cur, g) }
                        cFields.firstOrNull { it.name.equals("blue", true) || it.name.equals("b", true) }?.let { cf -> cf.isAccessible = true; cf.set(cur, b) }
                        cFields.firstOrNull { it.name.equals("alpha", true) || it.name.equals("a", true) }?.let { cf -> cf.isAccessible = true; cf.set(cur, a) }
                        f.set(settingObj, cur)
                        return true
                    }
                }
            }
        }

        // 2.5) Aggressive ColorSetting hierarchy field write fallback.
        runCatching {
            var cls: Class<*>? = settingObj.javaClass
            var wroteAny = false
            while (cls != null) {
                for (f in cls.declaredFields) {
                    f.isAccessible = true
                    val n = f.name.lowercase()
                    val t = f.type

                    fun setIfInt(v: Int) {
                        when (t) {
                            java.lang.Integer.TYPE, java.lang.Integer::class.java -> {
                                f.set(settingObj, v)
                                wroteAny = true
                            }
                            java.lang.Long.TYPE, java.lang.Long::class.java -> {
                                f.set(settingObj, v.toLong())
                                wroteAny = true
                            }
                            java.lang.Float.TYPE, java.lang.Float::class.java -> {
                                f.set(settingObj, v.toFloat())
                                wroteAny = true
                            }
                            java.lang.Double.TYPE, java.lang.Double::class.java -> {
                                f.set(settingObj, v.toDouble())
                                wroteAny = true
                            }
                        }
                    }

                    when {
                        n == "red" || n == "r" -> setIfInt(r)
                        n == "green" || n == "g" -> setIfInt(g)
                        n == "blue" || n == "b" -> setIfInt(b)
                        n == "alpha" || n == "a" -> setIfInt(a)
                        n == "rgb" || n == "argb" || n == "color" || n == "value" || n == "current" -> setIfInt(rgb)
                    }
                }
                cls = cls.superclass
            }
            if (wroteAny && readColorInt(settingObj) != before) return true
        }

        // 3) Broad fallback: any one-arg setter with custom type.
        val genericSetter = methods.firstOrNull { it.parameterCount == 1 }
        if (genericSetter != null) {
            val p = genericSetter.parameterTypes[0]
            val ctor1 = p.constructors.firstOrNull { c ->
                c.parameterCount == 1 && (c.parameterTypes[0] == Int::class.javaPrimitiveType || c.parameterTypes[0] == Int::class.java)
            }
            if (ctor1 != null) {
                return runCatching {
                    val obj = ctor1.newInstance(rgb)
                    genericSetter.invoke(settingObj, obj)
                    readColorInt(settingObj) != before
                }.getOrDefault(false)
            }
        }

        // 4) Brute-force likely setter methods for ColorSetting-style classes.
        val allMethods = (settingObj.javaClass.methods + settingObj.javaClass.declaredMethods)
            .distinctBy { it.name + "#" + it.parameterTypes.joinToString(",") { p -> p.name } }
        for (m in allMethods) {
            if (m.parameterCount !in 1..4) continue
            val n = m.name.lowercase()
            if (!(n.contains("set") || n.contains("color") || n.contains("update") || n.contains("value"))) continue
            runCatching { m.isAccessible = true }
            try {
                when (m.parameterCount) {
                    4 -> {
                        if (m.parameterTypes.all { it == Int::class.javaPrimitiveType || it == Int::class.java }) {
                            m.invoke(settingObj, r, g, b, a)
                        } else continue
                    }
                    3 -> {
                        if (m.parameterTypes.all { it == Int::class.javaPrimitiveType || it == Int::class.java }) {
                            m.invoke(settingObj, r, g, b)
                        } else continue
                    }
                    1 -> {
                        val p = m.parameterTypes[0]
                        when (p) {
                            Int::class.javaPrimitiveType, Int::class.java -> m.invoke(settingObj, rgb)
                            Long::class.javaPrimitiveType, Long::class.java -> m.invoke(settingObj, rgb.toLong())
                            Float::class.javaPrimitiveType, Float::class.java -> m.invoke(settingObj, rgb.toFloat())
                            Double::class.javaPrimitiveType, Double::class.java -> m.invoke(settingObj, rgb.toDouble())
                            java.awt.Color::class.java -> m.invoke(settingObj, java.awt.Color(r, g, b, a))
                            else -> {
                                val ctor4 = p.constructors.firstOrNull { c ->
                                    c.parameterCount == 4 && c.parameterTypes.all { t ->
                                        t == Int::class.javaPrimitiveType || t == Int::class.java
                                    }
                                }
                                val ctor3 = p.constructors.firstOrNull { c ->
                                    c.parameterCount == 3 && c.parameterTypes.all { t ->
                                        t == Int::class.javaPrimitiveType || t == Int::class.java
                                    }
                                }
                                val ctor1 = p.constructors.firstOrNull { c ->
                                    c.parameterCount == 1 && (c.parameterTypes[0] == Int::class.javaPrimitiveType || c.parameterTypes[0] == Int::class.java)
                                }
                                when {
                                    ctor4 != null -> m.invoke(settingObj, ctor4.newInstance(r, g, b, a))
                                    ctor3 != null -> m.invoke(settingObj, ctor3.newInstance(r, g, b))
                                    ctor1 != null -> m.invoke(settingObj, ctor1.newInstance(rgb))
                                    else -> continue
                                }
                            }
                        }
                    }
                }
                if (readColorInt(settingObj) != before) return true
            } catch (_: Throwable) {
                // continue probing
            }
        }

        return false
    }

    private fun buildColorObject(
        targetType: Class<*>,
        baseColorObj: Any?,
        r: Int,
        g: Int,
        b: Int,
        a: Int,
        rgb: Int
    ): Any? {
        if (targetType == java.awt.Color::class.java) return java.awt.Color(r, g, b, a)

        val base = baseColorObj?.takeIf { targetType.isInstance(it) }
        if (base != null) {
            // Kotlin data class copy(red, green, blue, alpha) / copy(red, green, blue)
            val copy4 = base.javaClass.methods.firstOrNull { m ->
                m.name == "copy" && m.parameterCount == 4 &&
                    m.parameterTypes.all { it == Int::class.javaPrimitiveType || it == Int::class.java }
            }
            if (copy4 != null) {
                val c = runCatching { copy4.invoke(base, r, g, b, a) }.getOrNull()
                if (c != null) return c
            }
            val copy3 = base.javaClass.methods.firstOrNull { m ->
                m.name == "copy" && m.parameterCount == 3 &&
                    m.parameterTypes.all { it == Int::class.javaPrimitiveType || it == Int::class.java }
            }
            if (copy3 != null) {
                val c = runCatching { copy3.invoke(base, r, g, b) }.getOrNull()
                if (c != null) return c
            }
        }

        val ctor4 = targetType.constructors.firstOrNull { c ->
            c.parameterCount == 4 && c.parameterTypes.all { it == Int::class.javaPrimitiveType || it == Int::class.java }
        }
        if (ctor4 != null) {
            val c = runCatching { ctor4.newInstance(r, g, b, a) }.getOrNull()
            if (c != null) return c
        }
        val ctor3 = targetType.constructors.firstOrNull { c ->
            c.parameterCount == 3 && c.parameterTypes.all { it == Int::class.javaPrimitiveType || it == Int::class.java }
        }
        if (ctor3 != null) {
            val c = runCatching { ctor3.newInstance(r, g, b) }.getOrNull()
            if (c != null) return c
        }
        val ctor1 = targetType.constructors.firstOrNull { c ->
            c.parameterCount == 1 && (c.parameterTypes[0] == Int::class.javaPrimitiveType || c.parameterTypes[0] == Int::class.java)
        }
        if (ctor1 != null) {
            val c = runCatching { ctor1.newInstance(rgb) }.getOrNull()
            if (c != null) return c
        }

        val staticFactory = (targetType.methods + targetType.declaredMethods).firstOrNull { m ->
            java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                m.parameterCount == 1 &&
                (m.parameterTypes[0] == Int::class.javaPrimitiveType || m.parameterTypes[0] == Int::class.java) &&
                (m.name.equals("fromInt", true) || m.name.equals("fromRGB", true) || m.name.equals("valueOf", true) || m.name.equals("of", true))
        }
        if (staticFactory != null) {
            val c = runCatching {
                staticFactory.isAccessible = true
                staticFactory.invoke(null, rgb)
            }.getOrNull()
            if (c != null) return c
        }

        return null
    }

    private fun setModulePropertyColor(module: Module, fieldName: String, rgb: Int): Boolean {
        val base = fieldName.removeSuffix("\$delegate")
        val methodName = "set" + base.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val methods = module.javaClass.methods + module.javaClass.declaredMethods
        val setter = methods.firstOrNull { it.name == methodName && it.parameterCount == 1 } ?: return false

        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val a = (rgb ushr 24) and 0xFF
        val p = setter.parameterTypes[0]

        return runCatching {
            setter.isAccessible = true
            when (p) {
                java.lang.Integer.TYPE, java.lang.Integer::class.java -> setter.invoke(module, rgb)
                java.lang.Long.TYPE, java.lang.Long::class.java -> setter.invoke(module, rgb.toLong())
                else -> {
                    // Try static constructors first.
                    val staticFactory = (p.methods + p.declaredMethods).firstOrNull { m ->
                        java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                            m.parameterCount == 1 &&
                            (m.parameterTypes[0] == java.lang.Integer.TYPE || m.parameterTypes[0] == java.lang.Integer::class.java) &&
                            (m.name.equals("fromInt", true) || m.name.equals("fromRGB", true) ||
                                m.name.equals("valueOf", true) || m.name.equals("of", true))
                    }
                    if (staticFactory != null) {
                        staticFactory.isAccessible = true
                        val colorObj = staticFactory.invoke(null, rgb)
                        setter.invoke(module, colorObj)
                        return@runCatching true
                    }

                    // Then normal constructors.
                    val ctor = p.constructors.firstOrNull { c ->
                        c.parameterCount == 4 && c.parameterTypes.all { t ->
                            t == java.lang.Integer.TYPE || t == java.lang.Integer::class.java
                        }
                    } ?: p.constructors.firstOrNull { c ->
                        c.parameterCount == 3 && c.parameterTypes.all { t ->
                            t == java.lang.Integer.TYPE || t == java.lang.Integer::class.java
                        }
                    } ?: p.constructors.firstOrNull { c ->
                        c.parameterCount == 1 && (c.parameterTypes[0] == java.lang.Integer.TYPE || c.parameterTypes[0] == java.lang.Integer::class.java)
                    }

                    if (ctor != null) {
                        val colorObj = when (ctor.parameterCount) {
                            4 -> ctor.newInstance(r, g, b, a)
                            3 -> ctor.newInstance(r, g, b)
                            else -> ctor.newInstance(rgb)
                        }
                        setter.invoke(module, colorObj)
                    } else {
                        return@runCatching false
                    }
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun readColorAlpha(settingObj: Any): Int {
        val methods = settingObj.javaClass.methods
        val getter = methods.firstOrNull {
            it.parameterCount == 0 && (it.name.equals("get", true) || it.name.equals("getValue", true))
        } ?: return 255
        val v = runCatching { getter.invoke(settingObj) }.getOrNull() ?: return 255
        return when (v) {
            is Number -> ((v.toInt() ushr 24) and 0xFF).takeIf { it != 0 } ?: 255
            else -> {
                val alphaMethod = v.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals("getAlpha", true) }
                if (alphaMethod != null) {
                    (runCatching { alphaMethod.invoke(v) as Number }.getOrNull()?.toInt() ?: 255).coerceIn(0, 255)
                } else {
                    val s = v.toString()
                    val m = Regex("alpha=(\\d+)").find(s)
                    m?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 255) ?: 255
                }
            }
        }
    }

    private fun toggleBooleanSetting(module: Module, fieldName: String, settingObj: Any, value: Boolean) {
        // Prefer module property setter if available (works for delegated vars).
        if (setModulePropertyBoolean(module, fieldName, value)) return

        val methods = settingObj.javaClass.methods
        methods.firstOrNull {
            it.parameterCount == 1 &&
                (it.parameterTypes[0] == java.lang.Boolean.TYPE || it.parameterTypes[0] == java.lang.Boolean::class.java) &&
                it.name.equals("set", true)
        }?.let {
            runCatching { it.invoke(settingObj, value) }
            return
        }
        methods.firstOrNull {
            it.parameterCount == 1 &&
                (it.parameterTypes[0] == java.lang.Boolean.TYPE || it.parameterTypes[0] == java.lang.Boolean::class.java) &&
                it.name.equals("setValue", true)
        }?.let {
            runCatching { it.invoke(settingObj, value) }
            return
        }
        methods.firstOrNull {
            it.parameterCount == 1 &&
                (it.parameterTypes[0] == java.lang.Boolean.TYPE || it.parameterTypes[0] == java.lang.Boolean::class.java) &&
                it.name.equals("setEnabled", true)
        }?.let {
            runCatching { it.invoke(settingObj, value) }
            return
        }
        methods.firstOrNull { it.parameterCount == 0 && it.name.equals("toggle", true) }?.let {
            runCatching { it.invoke(settingObj) }
        }
    }

    private fun readSelectorValue(settingObj: Any): String? {
        if (isKeybindSetting(settingObj)) return null
        val methods = settingObj.javaClass.methods
        val getter = methods.firstOrNull {
            it.parameterCount == 0 && (it.name.equals("getSelected", true) || it.name.equals("get", true) || it.name.equals("getValue", true))
        } ?: return null
        val value = runCatching { getter.invoke(settingObj) }.getOrNull() ?: return null
        if (value is InputConstants.Key) return null
        val s = value.toString()
        if (s.startsWith("Color(")) return null
        if (s == "true" || s == "false") return null
        if (s.contains("key.keyboard", ignoreCase = true) || s.contains("key.mouse", ignoreCase = true)) return null
        return s
    }

    private fun getSelectorOptions(settingObj: Any): List<String> {
        val methods = settingObj.javaClass.methods
        val optionsMethod = methods.firstOrNull {
            it.parameterCount == 0 &&
                (
                    it.name.equals("getOptions", true) ||
                        it.name.equals("getValues", true) ||
                        it.name.equals("getSelections", true) ||
                        it.name.equals("getModes", true) ||
                        it.name.equals("getItems", true) ||
                        it.name.equals("getChoices", true)
                    )
        }
        val fromMethod = runCatching { optionsMethod?.invoke(settingObj) }.getOrNull()
        val methodList = when (fromMethod) {
            is Collection<*> -> fromMethod.map { it.toString() }
            is Array<*> -> fromMethod.map { it.toString() }
            else -> emptyList()
        }.filter { it.isNotBlank() }
        if (methodList.isNotEmpty()) return methodList.distinct()

        // Fallback: inspect likely options fields directly.
        for (f in settingObj.javaClass.declaredFields) {
            val n = f.name.lowercase()
            if (!(n.contains("option") || n.contains("value") || n.contains("mode") || n.contains("selection") || n.contains("choice"))) continue
            f.isAccessible = true
            val v = runCatching { f.get(settingObj) }.getOrNull() ?: continue
            val list = when (v) {
                is Collection<*> -> v.map { it.toString() }
                is Array<*> -> v.map { it.toString() }
                else -> emptyList()
            }.filter { it.isNotBlank() }
            if (list.isNotEmpty()) return list.distinct()
        }
        return emptyList()
    }

    private fun getSelectorRawOptions(settingObj: Any): List<Any> {
        val methods = settingObj.javaClass.methods
        val optionsMethod = methods.firstOrNull {
            it.parameterCount == 0 &&
                (
                    it.name.equals("getOptions", true) ||
                        it.name.equals("getValues", true) ||
                        it.name.equals("getSelections", true) ||
                        it.name.equals("getModes", true) ||
                        it.name.equals("getItems", true) ||
                        it.name.equals("getChoices", true)
                    )
        } ?: return emptyList()
        val raw = runCatching { optionsMethod.invoke(settingObj) }.getOrNull() ?: return emptyList()
        val fromMethod = when (raw) {
            is Collection<*> -> raw.filterNotNull()
            is Array<*> -> raw.filterNotNull()
            else -> emptyList()
        }
        if (fromMethod.isNotEmpty()) return fromMethod

        // Field fallback (some selector settings store option arrays/lists in fields).
        for (f in settingObj.javaClass.declaredFields) {
            val n = f.name.lowercase()
            if (!(n.contains("option") || n.contains("value") || n.contains("mode") || n.contains("selection") || n.contains("choice"))) continue
            f.isAccessible = true
            val v = runCatching { f.get(settingObj) }.getOrNull() ?: continue
            val list = when (v) {
                is Collection<*> -> v.filterNotNull()
                is Array<*> -> v.filterNotNull()
                else -> emptyList()
            }
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    private fun resolveSelectorOptions(settingObj: Any, boolVal: Boolean?, displayName: String, fieldName: String): List<String> {
        val explicit = getSelectorOptions(settingObj)
        if (explicit.size > 1) return explicit

        val cls = settingObj.javaClass.name.lowercase()
        val name = "$displayName $fieldName".lowercase()
        val looksLikeSelector =
            cls.contains("selector") || cls.contains("dropdown") || cls.contains("mode") ||
                name.contains("dropdown") || name.contains("selector") || name.contains("mode")
        if (looksLikeSelector && boolVal != null) {
            // Some Odin dropdown-like settings expose as boolean; still render as dropdown.
            return listOf("OFF", "ON")
        }
        return explicit
    }

    private fun setSelectorValue(module: Module, fieldName: String, settingObj: Any, value: String) {
        val boolVal = readBooleanSetting(settingObj)
        if (boolVal != null && (value.equals("ON", true) || value.equals("OFF", true) || value.equals("TRUE", true) || value.equals("FALSE", true))) {
            val target = value.equals("ON", true) || value.equals("TRUE", true)
            toggleBooleanSetting(module, fieldName, settingObj, target)
            return
        }

        // Try setting the typed raw option object first (important for enum/object selectors).
        val rawOptions = getSelectorRawOptions(settingObj)
        val selectedRaw = rawOptions.firstOrNull { it.toString() == value }
        if (selectedRaw != null && setModulePropertySelectorObject(module, fieldName, selectedRaw)) return
        if (selectedRaw != null) {
            val methods = settingObj.javaClass.methods
            val typedSetter = methods.firstOrNull {
                it.parameterCount == 1 &&
                    (it.name.equals("set", true) || it.name.equals("setValue", true) || it.name.equals("setSelected", true)) &&
                    it.parameterTypes[0].isAssignableFrom(selectedRaw.javaClass)
            }
            if (typedSetter != null) {
                val ok = runCatching { typedSetter.invoke(settingObj, selectedRaw); true }.getOrDefault(false)
                if (ok) return
            }
        }

        // Enum setter fallback: map label ("Filled Outline") -> enum constant ("FILLED_OUTLINE").
        val enumText = value.uppercase().replace(' ', '_')
        val enumSettingSetter = settingObj.javaClass.methods.firstOrNull {
            it.parameterCount == 1 &&
                (it.name.equals("set", true) || it.name.equals("setValue", true) || it.name.equals("setSelected", true)) &&
                it.parameterTypes[0].isEnum
        }
        if (enumSettingSetter != null) {
            val enumClass = enumSettingSetter.parameterTypes[0]
            val enumConst = runCatching {
                enumClass.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == enumText }
            }.getOrNull()
            if (enumConst != null) {
                val ok = runCatching { enumSettingSetter.invoke(settingObj, enumConst); true }.getOrDefault(false)
                if (ok) return
            }
        }
        if (setModulePropertySelectorEnum(module, fieldName, enumText)) return

        if (setModulePropertyString(module, fieldName, value)) return
        val methods = settingObj.javaClass.methods
        methods.firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0] == String::class.java &&
                (it.name.equals("set", true) || it.name.equals("setValue", true) || it.name.equals("setSelected", true))
        }?.let {
            runCatching { it.invoke(settingObj, value) }
            return
        }
        // Fallback: attempt index-based setter.
        val options = getSelectorOptions(settingObj)
        val idx = options.indexOf(value)
        if (idx >= 0) {
            methods.firstOrNull {
                it.parameterCount == 1 &&
                    (it.parameterTypes[0] == Int::class.javaPrimitiveType || it.parameterTypes[0] == Int::class.java) &&
                    (it.name.equals("setIndex", true) || it.name.equals("setSelectedIndex", true) || it.name.equals("set", true))
            }?.let { runCatching { it.invoke(settingObj, idx) } }
            // Module-side index setter fallback.
            val base = fieldName.removeSuffix("\$delegate")
            val methodName = "set" + base.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            module.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 1 &&
                    (it.parameterTypes[0] == Int::class.javaPrimitiveType || it.parameterTypes[0] == Int::class.java)
            }?.let { runCatching { it.invoke(module, idx) } }

            // Broad numeric setter fallback (idx and idx+1 for 1-based APIs).
            val all = (methods + settingObj.javaClass.declaredMethods)
                .distinctBy { m -> m.name + "#" + m.parameterTypes.joinToString(",") { p -> p.name } }
            for (candidate in listOf(idx, idx + 1)) {
                all.firstOrNull { m ->
                    m.parameterCount == 1 &&
                        (m.parameterTypes[0] == Int::class.javaPrimitiveType || m.parameterTypes[0] == Int::class.java) &&
                        (m.name.contains("index", true) || m.name.contains("selected", true) || m.name.contains("mode", true) || m.name.contains("value", true))
                }?.let { m ->
                    runCatching {
                        m.isAccessible = true
                        m.invoke(settingObj, candidate)
                    }
                    val cur = resolveSelectorCurrentLabel(readSelectorValue(settingObj), options)
                    if (selectorOptionMatches(cur, value)) return
                }
            }
            return
        }

        // Last resort: cycle/next until target reached.
        val current = readSelectorValue(settingObj)
        if (current != null && current != value) {
            val cycle = methods.firstOrNull {
                it.parameterCount == 0 &&
                    (it.name.equals("cycle", true) || it.name.equals("next", true) || it.name.equals("toggle", true))
            } ?: return
            val attempts = options.size.coerceAtLeast(3) + 2
            repeat(attempts) {
                runCatching { cycle.invoke(settingObj) }
                val curLabel = resolveSelectorCurrentLabel(readSelectorValue(settingObj), options)
                if (selectorOptionMatches(curLabel, value)) return
            }
        }
    }

    private fun setModulePropertySelectorObject(module: Module, fieldName: String, selectedRaw: Any): Boolean {
        val base = fieldName.removeSuffix("\$delegate")
        val methodName = "set" + base.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val methods = module.javaClass.methods + module.javaClass.declaredMethods
        val setter = methods.firstOrNull {
            it.name == methodName && it.parameterCount == 1 &&
                it.parameterTypes[0].isAssignableFrom(selectedRaw.javaClass)
        } ?: return false
        return runCatching {
            setter.isAccessible = true
            setter.invoke(module, selectedRaw)
            true
        }.getOrDefault(false)
    }

    private fun setModulePropertySelectorEnum(module: Module, fieldName: String, enumText: String): Boolean {
        val base = fieldName.removeSuffix("\$delegate")
        val methodName = "set" + base.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val methods = module.javaClass.methods + module.javaClass.declaredMethods
        val setter = methods.firstOrNull {
            it.name == methodName && it.parameterCount == 1 && it.parameterTypes[0].isEnum
        } ?: return false
        val enumClass = setter.parameterTypes[0]
        val enumConst = runCatching {
            enumClass.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == enumText }
        }.getOrNull() ?: return false
        return runCatching {
            setter.isAccessible = true
            setter.invoke(module, enumConst)
            true
        }.getOrDefault(false)
    }

    private fun setModulePropertyBoolean(module: Module, fieldName: String, value: Boolean): Boolean {
        val base = fieldName.removeSuffix("\$delegate")
        val methodName = "set" + base.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val m = module.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterCount == 1 &&
                (it.parameterTypes[0] == java.lang.Boolean.TYPE || it.parameterTypes[0] == java.lang.Boolean::class.java)
        } ?: return false
        return runCatching { m.invoke(module, value); true }.getOrDefault(false)
    }

    private fun setModulePropertyNumber(module: Module, fieldName: String, value: Double): Boolean {
        val base = fieldName.removeSuffix("\$delegate")
        val methodName = "set" + base.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val m = module.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 1 } ?: return false
        return runCatching {
            when (m.parameterTypes[0]) {
                java.lang.Integer.TYPE, java.lang.Integer::class.java -> m.invoke(module, value.toInt())
                java.lang.Long.TYPE, java.lang.Long::class.java -> m.invoke(module, value.toLong())
                java.lang.Float.TYPE, java.lang.Float::class.java -> m.invoke(module, value.toFloat())
                java.lang.Double.TYPE, java.lang.Double::class.java -> m.invoke(module, value)
                else -> return false
            }
            true
        }.getOrDefault(false)
    }

    private fun setModulePropertyString(module: Module, fieldName: String, value: String): Boolean {
        val base = fieldName.removeSuffix("\$delegate")
        val methodName = "set" + base.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val m = module.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        } ?: return false
        return runCatching { m.invoke(module, value); true }.getOrDefault(false)
    }

    private fun inside(mx: Double, my: Double, x: Int, y: Int, w: Int, h: Int): Boolean {
        return mx >= x && mx <= x + w && my >= y && my <= y + h
    }

    private inline fun withScaledFont(gui: GuiGraphics, scale: Float, draw: () -> Unit) {
        val pose = gui.pose()
        pose.pushMatrix()
        pose.scale(scale, scale)
        try {
            draw()
        } finally {
            pose.popMatrix()
        }
    }

    private fun drawScaledText(gui: GuiGraphics, text: String, x: Int, y: Int, color: Int, scale: Float) {
        withScaledFont(gui, scale) {
            gui.drawString(font, text, (x / scale).toInt(), (y / scale).toInt(), color, false)
        }
    }

    private fun drawScaledCenteredText(gui: GuiGraphics, text: String, centerX: Int, y: Int, color: Int, scale: Float) {
        withScaledFont(gui, scale) {
            val unscaledX = (centerX / scale) - (font.width(text) / 2.0f)
            val unscaledY = y / scale
            gui.drawString(font, text, round(unscaledX).toInt(), round(unscaledY).toInt(), color, false)
        }
    }

    private fun drawTopAccentBar(gui: GuiGraphics, x: Int, y: Int, width: Int) {
        if (!guiShowRainbowBar || width <= 0) return
        if (guiAccentBarSkeetFade) drawRainbowBar(gui, x, y, width, 1)
        else drawAccentThemedBar(gui, x, y, width, 1)
    }

    private fun drawAccentThemedBar(gui: GuiGraphics, x: Int, y: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val r0 = (guiAccentColor shr 16) and 0xFF
        val g0 = (guiAccentColor shr 8) and 0xFF
        val b0 = guiAccentColor and 0xFF
        val hsb = java.awt.Color.RGBtoHSB(r0, g0, b0, null)
        for (i in 0 until width) {
            val t = (i.toFloat() / (width - 1).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            val s = t * t * (3f - 2f * t)
            val br = 0.38f + 0.62f * s
            val sat = 0.78f + 0.22f * s
            val c = java.awt.Color.getHSBColor(
                hsb[0],
                (hsb[1] * sat).coerceIn(0f, 1f),
                (br * hsb[2]).coerceIn(0.08f, 1f)
            )
            val argb = 0xFF000000.toInt() or (c.red shl 16) or (c.green shl 8) or c.blue
            gui.fill(x + i, y, x + i + 1, y + height, argb)
        }
    }

    private fun drawRainbowBar(gui: GuiGraphics, x: Int, y: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        for (i in 0 until width) {
            val t = (i.toFloat() / (width - 1).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            val rgb = skeetAccentRgbAt(t)
            val argb = 0xFF000000.toInt() or rgb
            gui.fill(x + i, y, x + i + 1, y + height, argb)
        }
    }

    private fun skeetAccentRgbAt(t: Float): Int {
        // Hand-tuned control points to mimic classic skeet top-bar fade:
        // mostly cool -> warm, with only a small violet tail.
        val stops = arrayOf(
            0.00f to intArrayOf(86, 188, 255),   // cyan
            0.22f to intArrayOf(88, 220, 170),   // aqua-green
            0.46f to intArrayOf(168, 226, 102),  // yellow-green
            0.64f to intArrayOf(236, 198, 92),   // warm yellow
            0.80f to intArrayOf(229, 106, 108),  // red
            0.92f to intArrayOf(211, 103, 178),  // soft magenta
            1.00f to intArrayOf(140, 122, 224)   // subtle violet end
        )
        val clamped = t.coerceIn(0f, 1f)
        var idx = 0
        while (idx < stops.size - 1 && clamped > stops[idx + 1].first) idx++
        val (t0, c0) = stops[idx]
        val (t1, c1) = stops[(idx + 1).coerceAtMost(stops.lastIndex)]
        val localT = if (t1 <= t0) 0f else ((clamped - t0) / (t1 - t0)).coerceIn(0f, 1f)
        // Ease the interpolation so each segment has a subtle fade-in/out like skeet.
        val s = localT * localT * (3f - 2f * localT)
        var r = (c0[0] + (c1[0] - c0[0]) * s).toInt().coerceIn(0, 255)
        var g = (c0[1] + (c1[1] - c0[1]) * s).toInt().coerceIn(0, 255)
        var b = (c0[2] + (c1[2] - c0[2]) * s).toInt().coerceIn(0, 255)

        // Slightly desaturate/darken to match the muted skeet line.
        val avg = (r + g + b) / 3
        val satKeep = 0.88f
        r = (avg + (r - avg) * satKeep).toInt().coerceIn(0, 255)
        g = (avg + (g - avg) * satKeep).toInt().coerceIn(0, 255)
        b = (avg + (b - avg) * satKeep).toInt().coerceIn(0, 255)
        r = (r * 0.94f).toInt().coerceIn(0, 255)
        g = (g * 0.94f).toInt().coerceIn(0, 255)
        b = (b * 0.94f).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

}