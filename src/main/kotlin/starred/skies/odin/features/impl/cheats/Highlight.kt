package starred.skies.odin.features.impl.cheats

import com.odtheking.odin.OdinMod
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.MapSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.WorldEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.noControlCodes
import com.odtheking.odin.utils.render.drawLine
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.renderBoundingBox
import com.odtheking.odin.utils.renderPos
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import starred.skies.odin.events.EntityMetadataEvent
import starred.skies.odin.utils.Skit
import starred.skies.odin.utils.drawTracer

object Highlight : Module(
    name = "Highlight (C)",
    description = "Allows you to highlight selected entities.",
    category = Skit.CHEATS
) {
    private val depthCheck by BooleanSetting("Depth Check", false, desc = "Disable to enable ESP")
    private val highlightStar by BooleanSetting("Highlight Starred Mobs", true, desc = "Highlights starred dungeon mobs.")
    private val starredTracer by BooleanSetting("Starred mobs tracers", desc = "Draws a tracer to the starred mobs.")
    val color by ColorSetting("Highlight color", Colors.WHITE, true, desc = "The color of the highlight.")
    private val renderStyle by SelectorSetting("Render Style", "Outline", listOf("Filled", "Outline", "Filled Outline"), desc = "Style of the box.")
    private val hideNonNames by BooleanSetting("Hide non-starred names", true, desc = "Hides names of entities that are not starred.")
    private val teammateClassGlow by BooleanSetting("Teammate Class Glow", true, desc = "Highlights dungeon teammates based on their class color.")
    private val highlightWither by BooleanSetting("Highlight Withers", true, desc = "Highlights Necron, Goldor, Storm and Maxor.")
    private val witherColor by ColorSetting("Wither ESP Color", Color(255, 0, 0, 1f), true, desc = "The color of the wither highlight.")
    private val witherTracer by BooleanSetting("Wither Tracer", true, desc = "Draws a tracer to the wither boss in P3 section 4.")
    private val highlightBats by BooleanSetting("Highlight Bats", true, desc = "Highlights bats in dungeons.")
    val batColor by ColorSetting("Bat color", Color(0, 255, 255, 1f), true, desc = "The color of the bat highlight.")
    private val skeletonESP by BooleanSetting("Skeleton ESP", false, desc = "Draws a skeleton on highlighted entities.")
    val skeletonColor by ColorSetting("Skeleton color", Color(255, 255, 255, 1f), true, desc = "The color of the skeleton.")
    private val customTracer by BooleanSetting("Custom tracer", desc = "Draws a tracer to the mobs added manually")

    private val dungeonMobSpawns = hashSetOf("Lurker", "Dreadlord", "Souleater", "Zombie", "Skeleton", "Skeletor", "Sniper", "Super Archer", "Spider", "Fels", "Withermancer", "Lost Adventurer", "Angry Archaeologist", "Frozen Adventurer", "Shadow Assassin")
    private val starredRegex = Regex("^.*✯ .*\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?[kM]?❤$")

    val highlightMap by MapSetting("highlightMap", mutableMapOf<String, Color>())

    private val starredIds = hashSetOf<Int>()
    private val customIds = hashMapOf<Int, Color>()
    private val witherIds = hashSetOf<Int>()
    private val spiritSceptreIds = hashSetOf<Int>()
    private val checkedIds = hashSetOf<Int>()

    init {
        OdinMod.logger.debug("Loaded ${highlightMap.entries.size}")

        on<EntityMetadataEvent> {
            if (!DungeonUtils.inDungeons) return@on
            if (!entity.isAlive) return@on

            when {
                highlightWither && entity is WitherBoss && entity.isPowered -> {
                    witherIds.add(entity.id)
                }

                !DungeonUtils.inBoss && highlightBats && entity is Bat && !entity.isPassenger && !entity.isInvisible -> {
                    val player = mc.player ?: return@on
                    if (player.distanceTo(entity) < 1.0) {
                        spiritSceptreIds.add(entity.id)
                        return@on
                    }
                }

                !DungeonUtils.inBoss && highlightStar && entity is Player && entity != mc.player && entity.gameProfile.name.contains("Shadow Assassin") -> {
                    starredIds.add(entity.id)
                }

                !DungeonUtils.inBoss && (highlightStar || highlightMap.isNotEmpty()) && entity is ArmorStand -> {
                    val rawName = entity.displayName?.string?.noControlCodes?.takeIf { !it.equals("armor stand", true) } ?: return@on
                    val nameLower = rawName.lowercase()

                    if (highlightStar && dungeonMobSpawns.any(rawName::contains)) {
                        val starred = starredRegex.matches(rawName)
                        if (hideNonNames && entity.isInvisible && !starred) return@on
                        if (starred && checkedIds.add(entity.id)) {
                            entity.fn(true)?.let { starredIds.add(it.id) }
                        }
                    }

                    if (highlightMap.isNotEmpty()) {
                        val match = highlightMap.entries.firstOrNull { nameLower.contains(it.key) } ?: return@on
                        entity.fn(true)?.let { customIds[it.id] = match.value }
                    }
                }
            }
        }

        on<RenderEvent.Extract> {
            if (customIds.isEmpty() && starredIds.isEmpty() && witherIds.isEmpty() && !highlightBats) return@on

            val world = mc.level ?: return@on
            val bool0 = starredTracer
            val bool1 = witherTracer && DungeonUtils.getF7Phase() == M7Phases.P3
            val bool2 = customTracer
            val bool3 = skeletonESP

            starredIds.forEach { id ->
                val entity = world.getEntity(id) ?: return@forEach
                drawStyledBox(entity.renderBoundingBox, color, renderStyle, depthCheck)
                if (bool0) drawTracer(entity.renderPos, color, depth = depthCheck)
                if (bool3 && shouldDrawSkeleton(entity)) drawSkeleton(entity, skeletonColor)
            }

            witherIds.forEach { id ->
                val entity = world.getEntity(id) ?: return@forEach
                drawStyledBox(entity.renderBoundingBox, witherColor, renderStyle, depthCheck)
                if (bool1) drawTracer(entity.renderPos, witherColor, depth = depthCheck)
                if (bool3) drawWitherSkeleton(entity, skeletonColor)
            }

            if (highlightBats && DungeonUtils.inDungeons && !DungeonUtils.inBoss) {
                world.entitiesForRendering()
                    .filterIsInstance<Bat>()
                    .filter { !it.isPassenger && !it.isInvisible && it.isAlive && it.vehicle == null && it.id !in spiritSceptreIds }
                    .forEach {
                        drawStyledBox(it.renderBoundingBox, batColor, renderStyle, depthCheck)
                        if (bool3) drawBatSkeleton(it, skeletonColor)
                    }
            }

            customIds.forEach { (id, color) ->
                val entity = world.getEntity(id) ?: return@forEach
                drawStyledBox(entity.renderBoundingBox, color, renderStyle, depthCheck)
                if (bool2) drawTracer(entity.renderPos, color, depth = depthCheck)
                if (bool3 && shouldDrawSkeleton(entity)) drawSkeleton(entity, skeletonColor)
            }
        }

        on<WorldEvent.Load> {
            starredIds.clear()
            customIds.clear()
            witherIds.clear()
            spiritSceptreIds.clear()
            checkedIds.clear()
        }
    }

    private fun shouldDrawSkeleton(entity: Entity): Boolean =
        entity is WitherBoss ||
                entity is Bat ||
                entity is Player ||
                entity is net.minecraft.world.entity.monster.Zombie ||
                entity is net.minecraft.world.entity.monster.AbstractSkeleton ||
                entity is net.minecraft.world.entity.monster.WitherSkeleton

    private fun RenderEvent.Extract.drawSkeleton(entity: Entity, color: Color) {
        val basePos = entity.renderPos
        val yawRad = Math.toRadians(-entity.yRot.toDouble()).toFloat()
        val h = entity.bbHeight.toDouble()

        val hips =        basePos.add(0.0, h * 0.5, 0.0)
        val chestBase =   basePos.add(0.0, h * 0.75, 0.0)
        val headTop =     basePos.add(0.0, h * 0.95, 0.0)

        val shoulderOff = Vec3(0.3, 0.0, 0.0)
        val leftShoulder =  chestBase.add(shoulderOff.yRot(yawRad).scale(-1.0))
        val rightShoulder = chestBase.add(shoulderOff.yRot(yawRad))

        val isZombie = entity is net.minecraft.world.entity.monster.Zombie
        val leftArmEnd = if (isZombie)
            leftShoulder.add(Vec3(0.0, 0.0, 0.55).yRot(yawRad))
        else
            leftShoulder.add(0.0, -h * 0.25, 0.0)
        val rightArmEnd = if (isZombie)
            rightShoulder.add(Vec3(0.0, 0.0, 0.55).yRot(yawRad))
        else
            rightShoulder.add(0.0, -h * 0.25, 0.0)

        val hipOff = Vec3(0.12, 0.0, 0.0)
        val leftHip =  hips.add(hipOff.yRot(yawRad).scale(-1.0))
        val rightHip = hips.add(hipOff.yRot(yawRad))
        val leftKnee =  leftHip.add(0.0, -h * 0.25, 0.0)
        val rightKnee = rightHip.add(0.0, -h * 0.25, 0.0)
        val leftFoot =  basePos.add(hipOff.yRot(yawRad).scale(-1.0))
        val rightFoot = basePos.add(hipOff.yRot(yawRad))

        val thickness = 1.0f

        drawLine(listOf(hips, chestBase, headTop), color = color, thickness = thickness, depth = depthCheck)
        drawLine(listOf(leftShoulder, chestBase, rightShoulder), color = color, thickness = thickness, depth = depthCheck)
        drawLine(listOf(leftShoulder, leftArmEnd), color = color, thickness = thickness, depth = depthCheck)
        drawLine(listOf(rightShoulder, rightArmEnd), color = color, thickness = thickness, depth = depthCheck)
        drawLine(listOf(leftHip, hips, rightHip), color = color, thickness = thickness, depth = depthCheck)
        drawLine(listOf(leftHip, leftKnee, leftFoot), color = color, thickness = thickness, depth = depthCheck)
        drawLine(listOf(rightHip, rightKnee, rightFoot), color = color, thickness = thickness, depth = depthCheck)
    }

    private fun RenderEvent.Extract.drawWitherSkeleton(entity: Entity, color: Color) {
        val basePos = entity.renderPos
        val yawRad = Math.toRadians(-entity.yRot.toDouble()).toFloat()
        val h = entity.bbHeight.toDouble()
        val w = entity.bbWidth.toDouble() / 2

        val thickness = 1.0f
        val swing = Math.sin(entity.tickCount * 0.15) * w * 0.8

        val spineTop =   basePos.add(0.0, h * 0.95, 0.0)
        val spineJoint = basePos.add(0.0, h * 0.68, 0.0)
        val spineBot =   basePos.add(Vec3(0.0, h * 0.15, swing).yRot(yawRad))

        val headOff =    Vec3(w * 2.8, 0.0, 0.0)
        val headBarL =   spineJoint.add(headOff.yRot(yawRad).scale(-1.0))
        val headBarR =   spineJoint.add(headOff.yRot(yawRad))
        val headTopL =   headBarL.add(0.0, h * 0.07, 0.0)
        val headTopR =   headBarR.add(0.0, h * 0.07, 0.0)

        val ribOff =     Vec3(w * 1.2, 0.0, 0.0)
        val rib1C =      basePos.add(Vec3(0.0, h * 0.52, swing * 0.6).yRot(yawRad))
        val rib1L =      rib1C.add(ribOff.yRot(yawRad).scale(-1.0))
        val rib1R =      rib1C.add(ribOff.yRot(yawRad))
        val rib2C =      basePos.add(Vec3(0.0, h * 0.42, swing * 0.75).yRot(yawRad))
        val rib2L =      rib2C.add(ribOff.yRot(yawRad).scale(-1.0))
        val rib2R =      rib2C.add(ribOff.yRot(yawRad))
        val rib3C =      basePos.add(Vec3(0.0, h * 0.32, swing * 0.9).yRot(yawRad))
        val rib3L =      rib3C.add(ribOff.yRot(yawRad).scale(-1.0))
        val rib3R =      rib3C.add(ribOff.yRot(yawRad))

        drawLine(listOf(spineTop, spineJoint), color = color, thickness = thickness, depth = depthCheck)
        drawLine(listOf(spineJoint, spineBot), color = color, thickness = thickness, depth = depthCheck)
        drawLine(listOf(headBarL, headBarR), color = color, thickness = thickness, depth = depthCheck)
        drawLine(listOf(headTopL, headBarL), color = color, thickness = thickness, depth = depthCheck)
        drawLine(listOf(headTopR, headBarR), color = color, thickness = thickness, depth = depthCheck)
        drawLine(listOf(rib1L, rib1C, rib1R), color = color, thickness = thickness, depth = depthCheck)
        drawLine(listOf(rib2L, rib2C, rib2R), color = color, thickness = thickness, depth = depthCheck)
        drawLine(listOf(rib3L, rib3C, rib3R), color = color, thickness = thickness, depth = depthCheck)
    }

    private fun RenderEvent.Extract.drawBatSkeleton(entity: Entity, color: Color) {
        val bb = entity.renderBoundingBox
        val x = (bb.minX + bb.maxX) / 2
        val z = (bb.minZ + bb.maxZ) / 2
        val w = (bb.maxX - bb.minX) / 2
        val h = bb.maxY - bb.minY

        val flapOffset = Math.sin(entity.tickCount * 0.5) * h * 0.3

        val head =      Vec3(x, bb.minY + h * 0.9, z)
        val body =      Vec3(x, bb.minY + h * 0.5, z)
        val tail =      Vec3(x, bb.minY + h * 0.1, z)

        val wingTipL =  Vec3(x - w * 1.4, bb.minY + h * 0.7 + flapOffset, z)
        val wingMidL =  Vec3(x - w * 0.7, bb.minY + h * 0.6 + flapOffset * 0.5, z)
        val wingTipR =  Vec3(x + w * 1.4, bb.minY + h * 0.7 + flapOffset, z)
        val wingMidR =  Vec3(x + w * 0.7, bb.minY + h * 0.6 + flapOffset * 0.5, z)

        val thickness = 1.0f

        drawLine(listOf(head, body, tail), color = color, thickness = thickness, depth = depthCheck)
        drawLine(listOf(wingTipL, wingMidL, body), color = color, thickness = thickness, depth = depthCheck)
        drawLine(listOf(wingTipR, wingMidR, body), color = color, thickness = thickness, depth = depthCheck)
        drawLine(listOf(wingTipL, body, wingTipR), color = color, thickness = thickness, depth = depthCheck)
    }

    private fun ArmorStand.fn(vis: Boolean = false): Entity? {
        val a = mc.level
            ?.getEntities(this, boundingBox.inflate(0.0, 1.0, 0.0)) { isValidEntity(it, vis) }
            ?.firstOrNull()

        if (a != null) return a

        return mc.level?.getEntity(id - 1)?.takeIf { isValidEntity(it, vis) }
    }

    private fun isValidEntity(entity: Entity, vis: Boolean = false): Boolean =
        when (entity) {
            is ArmorStand -> false
            is Player -> entity.uuid.version() == 2 && entity != mc.player
            is WitherBoss -> true
            else -> entity is EnderMan || (vis || !entity.isInvisible)
        }

    @JvmStatic
    fun getTeammateColor(entity: Entity): Int? {
        if (!enabled || !teammateClassGlow || !DungeonUtils.inDungeons || entity !is Player) return null
        return DungeonUtils.dungeonTeammates.find { it.name == entity.name?.string }?.clazz?.color?.rgba
    }
}