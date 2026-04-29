package starred.skies.odin.features.impl.cheats

import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.WorldEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.noControlCodes
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.renderBoundingBox
import com.odtheking.odin.utils.renderPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import starred.skies.odin.events.EntityMetadataEvent
import starred.skies.odin.utils.Skit
import starred.skies.odin.utils.drawTracer

object TrapperESP : Module(
    name = "Trapper ESP",
    description = "Highlights trapper mobs by nametag keywords.",
    category = Skit.CHEATS
) {
    private val enabledEsp by BooleanSetting("Enable ESP", true, desc = "Render ESP box for trapper mobs.")
    private val tracer by BooleanSetting("Show Tracer", true, desc = "Draw a tracer to trapper mobs.")
    private val renderLastSeen by BooleanSetting("Render Last Seen", true, desc = "Keeps rendering the last known position when entity de-tracks.")
    private val lastSeenSeconds by NumberSetting("Last Seen Time", 8.0, 1.0, 30.0, 1.0, unit = "s", desc = "How long to keep last seen markers.")
        .withDependency { renderLastSeen }
    private val rescanIntervalTicks by NumberSetting("Rescan Interval", 10.0, 2.0, 40.0, 1.0, unit = "t", desc = "How often to rescan nametag carriers.")
    private val depthCheck by BooleanSetting("Depth Check", false, desc = "Disable to render through walls.").withDependency { enabledEsp || tracer }
    private val color by ColorSetting("Color", Colors.MINECRAFT_AQUA, true, desc = "ESP and tracer color.").withDependency { enabledEsp || tracer }
    private val renderStyle by SelectorSetting("Render Style", "Outline", listOf("Filled", "Outline", "Filled Outline"), desc = "Style of the ESP box.").withDependency { enabledEsp }

    private val tierKeywords = listOf("elusive", "endangered", "undetected", "untrackable", "trackable")
    private val animalKeywords = listOf("cow", "pig", "sheep", "rabbit", "chicken", "horse")
    private val tracked = HashMap<Int, CachedTarget>()
    private var rescanTicker = 0

    private data class CachedTarget(
        var lastSeenAt: Long,
        var lastPos: Vec3,
        var lastBox: AABB
    )

    init {
        on<EntityMetadataEvent> {
            val rawName = entity.displayName?.string?.noControlCodes ?: entity.name?.string?.noControlCodes ?: return@on
            if (!isTrapperName(rawName)) return@on
            val target = resolveTrapperTarget(entity)
            track(target.id, target.renderPos, target.renderBoundingBox)
        }

        on<TickEvent.End> {
            val world = mc.level ?: return@on
            rescanTicker++
            if (rescanTicker < rescanIntervalTicks.toInt().coerceAtLeast(2)) return@on
            rescanTicker = 0

            // Throttled fallback scan: much cheaper than scanning every render frame.
            for (entity in world.entitiesForRendering()) {
                if (entity !is ArmorStand) continue
                val rawName = entity.displayName?.string?.noControlCodes ?: entity.name?.string?.noControlCodes ?: continue
                if (!isTrapperName(rawName)) continue
                val target = resolveTrapperTarget(entity)
                track(target.id, target.renderPos, target.renderBoundingBox)
            }
        }

        on<RenderEvent.Extract> {
            val world = mc.level ?: return@on
            if (!enabledEsp && !tracer) return@on
            val now = System.currentTimeMillis()
            val keepMs = (lastSeenSeconds * 1000.0).toLong()

            val iterator = tracked.entries.iterator()
            while (iterator.hasNext()) {
                val (id, cache) = iterator.next()
                val live = world.getEntity(id)
                if (live == null || !live.isAlive) {
                    if (!renderLastSeen || now - cache.lastSeenAt > keepMs) {
                        iterator.remove()
                        continue
                    }
                    if (enabledEsp) drawStyledBox(resolveRenderableBox(cache.lastPos, cache.lastBox), color, renderStyle, depthCheck)
                    if (tracer) drawTracer(cache.lastPos, color, depth = depthCheck)
                    continue
                }

                cache.lastSeenAt = now
                cache.lastPos = live.renderPos
                cache.lastBox = live.renderBoundingBox
                if (enabledEsp) drawStyledBox(resolveRenderableBox(cache.lastPos, cache.lastBox), color, renderStyle, depthCheck)
                if (tracer) drawTracer(cache.lastPos, color, depth = depthCheck)
            }
        }

        on<WorldEvent.Load> {
            tracked.clear()
            rescanTicker = 0
        }
    }

    private fun isTrapperName(name: String): Boolean {
        val lowered = name.lowercase()
        val hasTier = tierKeywords.any(lowered::contains)
        val hasAnimal = animalKeywords.any(lowered::contains)
        return hasTier && hasAnimal
    }

    private fun track(id: Int, pos: Vec3, box: AABB) {
        val now = System.currentTimeMillis()
        val existing = tracked[id]
        if (existing == null) {
            tracked[id] = CachedTarget(now, pos, box)
        } else {
            existing.lastSeenAt = now
            existing.lastPos = pos
            existing.lastBox = box
        }
    }

    private fun resolveRenderableBox(pos: Vec3, box: AABB): AABB {
        val w = box.maxX - box.minX
        val h = box.maxY - box.minY
        val d = box.maxZ - box.minZ
        // Marker/name entities can have near-zero hitboxes; give them a visible fallback ESP cube.
        if (w < 0.05 || h < 0.05 || d < 0.05) {
            return AABB(pos.x - 0.4, pos.y - 0.1, pos.z - 0.4, pos.x + 0.4, pos.y + 1.9, pos.z + 0.4)
        }
        return box
    }

    private fun resolveTrapperTarget(nameEntity: Entity): Entity {
        if (nameEntity !is ArmorStand) return nameEntity
        val world = mc.level ?: return nameEntity

        // Hypixel-style nametags are usually armor stands above the real mob.
        val nearby = world.getEntities(
            nameEntity,
            nameEntity.boundingBox.inflate(1.75, 2.25, 1.75)
        ) { other ->
            other !is ArmorStand &&
                other is LivingEntity &&
                other.isAlive &&
                isTrapperAnimalType(other) &&
                other != mc.player
        }

        return nearby.minByOrNull { it.distanceToSqr(nameEntity) } ?: nameEntity
    }

    private fun isTrapperAnimalType(entity: Entity): Boolean {
        val typeName = entity.type.toString().lowercase()
        return animalKeywords.any(typeName::contains)
    }
}
