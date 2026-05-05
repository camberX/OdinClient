package starred.skies.odin.features.impl.cheats

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.noControlCodes
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import kotlin.math.max
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import starred.skies.odin.events.EntityMetadataEvent
import starred.skies.odin.mixin.accessors.InventoryAccessor
import starred.skies.odin.utils.Skit
import starred.skies.odin.utils.nullableID
import starred.skies.odin.utils.rightClick

/**
 * Hellion dagger swap: read **ASHEN ♨ / …** from the struck mob’s stack + nearby armor-stand lines, then hotbar + morph.
 *
 * One place for mutable fight state ([FightState]); everything else is scan → queue → [swapTo].
 */
object AutoDagger : Module(
    name = "Auto Dagger",
    description = "Hit-based swap to the correct blaze dagger using boss / miniboss hologram lines.",
    category = Skit.CHEATS
) {
    // --- Settings -----------------------------------------------------------------------------

    private val crimsonOnly by BooleanSetting("Crimson isle only", true, desc = "Only activate while on Crimson Isle.")
    private val standScan by NumberSetting(
        "Hologram scan",
        12.0,
        4.0,
        24.0,
        1.0,
        unit = "blocks",
        desc = "Radius around the struck mob to scan armor stands / text displays for attunement."
    )
    private val delay by NumberSetting("Delay", 0.0, 0.0, 10.0, 1.0, unit = "ticks", desc = "Extra ticks before swapping after a hit reads attunement. 0 is fastest.")
    private val delayVariance by NumberSetting(
        "Delay variance",
        0.0,
        0.0,
        10.0,
        1.0,
        unit = "ticks",
        desc = "Random extra ticks 0..this per trigger."
    )
    private val probeTicksAfterHit by NumberSetting(
        "Fast probe ticks",
        120.0,
        0.0,
        300.0,
        5.0,
        unit = "ticks",
        desc = "Ticks to keep re-scanning the last-hit boss each tick-start. 0 = hit-only."
    )
    private val debug by BooleanSetting("Debug", false, desc = "Print name lines / SkyBlock IDs while testing.")

    /** All transient fields for one fight / session; reset on disable. */
    private data class FightState(
        var pendingAtt: BlazeAttunement? = null,
        var waitTicks: Int = -1,
        /** Same-gametick guard so we don’t double right-click / slot spam. */
        var hotbarMutationGameTime: Long = Long.MIN_VALUE,
        var bossTargetId: Int = -1,
        var probeTicksLeft: Int = 0,
        /** Last attunement parsed from hologram lines (dedupe spam). */
        var lastReadAtt: BlazeAttunement? = null,
        /** Last attunement we actually matched to hotbar+morph. */
        var lastAppliedAtt: BlazeAttunement? = null,
        var meleeDedupeGameTime: Long = Long.MIN_VALUE,
        var meleeDedupeEntityId: Int = -2,
        var prevHandFingerprint: String? = null,
    ) {
        fun reset() {
            pendingAtt = null
            waitTicks = -1
            hotbarMutationGameTime = Long.MIN_VALUE
            bossTargetId = -1
            probeTicksLeft = 0
            lastReadAtt = null
            lastAppliedAtt = null
            meleeDedupeGameTime = Long.MIN_VALUE
            meleeDedupeEntityId = -2
            prevHandFingerprint = null
        }
    }

    private var s = FightState()

    // --- Event wiring -------------------------------------------------------------------------

    init {
        on<EntityMetadataEvent> {
            if (!enabled || !canRun()) return@on
            if (crimsonOnly && !LocationUtils.isCurrentArea(Island.CrimsonIsle)) return@on
            if (!holdingHellionDagger()) return@on
            val boss = aliveBossTarget() ?: return@on
            if (!isHologram(entity) || !hologramBox(boss).intersects(entity.boundingBox)) return@on
            readBossAndQueue(boss, debugChat = false)
            drainSwapQueue()
        }

        on<TickEvent.Start> {
            if (!enabled) return@on
            s.pendingAtt?.let { swapTo(it) }
            onHandMaybeChanged()
            tickProbe()
            drainSwapQueue()
        }
    }

    override fun onDisable() {
        s.reset()
    }

    private fun canRun() = mc.screen == null && (!crimsonOnly || LocationUtils.isCurrentArea(Island.CrimsonIsle))

    // --- Entry points (melee mixin calls [noteMeleeTarget]) ----------------------------------

    fun noteMeleeTarget(hit: Entity) {
        if (!enabled || !canRun() || !hit.isAlive) return
        val gt = mc.level?.gameTime ?: return
        if (gt == s.meleeDedupeGameTime && hit.id == s.meleeDedupeEntityId) return
        s.meleeDedupeGameTime = gt
        s.meleeDedupeEntityId = hit.id

        if (s.bossTargetId >= 0 && s.bossTargetId != hit.id) {
            s.lastReadAtt = null
            s.lastAppliedAtt = null
        }
        s.probeTicksLeft = probeTicksAfterHit.toInt().coerceAtLeast(0)
        s.bossTargetId = hit.id
        readBossAndQueue(hit, debugChat = true)
        drainSwapQueue()
    }

    // --- Hotbar fingerprint: clear dedupe when you leave daggers or re-equip ------------------

    private fun onHandMaybeChanged() {
        val player = mc.player ?: return
        val inv = player.inventory ?: return
        val acc = inv as InventoryAccessor
        val held = inv.getItem(acc.selectedSlot)
        val sb = held.skyblockIdNormalized()
        val itemKey = BuiltInRegistries.ITEM.getKey(held.item)?.toString() ?: held.item.javaClass.simpleName
        val finger = "${acc.selectedSlot}|${sb ?: "—"}|$itemKey"
        val prev = s.prevHandFingerprint
        s.prevHandFingerprint = finger
        if (finger == prev) return

        val onDagger = sb != null && sb in BlazeAttunement.ALL_SB_IDS_NORMALIZED
        when {
            prev == null -> Unit
            !onDagger -> {
                s.lastAppliedAtt = null
                s.lastReadAtt = null
                return
            }
            else -> {
                s.lastAppliedAtt = null
                val ent = aliveBossTarget() ?: return
                readBossAndQueue(ent, debugChat = false)
                drainSwapQueue()
            }
        }
    }

    private fun tickProbe() {
        if (!canRun()) {
            s.probeTicksLeft = 0
            s.bossTargetId = -1
            s.lastReadAtt = null
            s.lastAppliedAtt = null
            s.prevHandFingerprint = null
            return
        }
        if (s.probeTicksLeft <= 0 || s.bossTargetId < 0) return
        s.probeTicksLeft--
        val ent = mc.level?.getEntity(s.bossTargetId)?.takeIf { it.isAlive } ?: run {
            s.lastReadAtt = null
            s.lastAppliedAtt = null
            if (s.probeTicksLeft <= 0) s.bossTargetId = -1
            return
        }
        if (!holdingHellionDagger()) return
        readBossAndQueue(ent, debugChat = false)
        drainSwapQueue()
    }

    /** Run [swapTo] in a tight loop so non-zero Delay finishes same tick as far as possible. */
    private fun drainSwapQueue() {
        var n = 0
        while (s.pendingAtt != null && n++ < 16) swapTo(s.pendingAtt!!)
    }

    private fun holdingHellionDagger(): Boolean {
        val id = mainHandSkyblockId() ?: return false
        return id in BlazeAttunement.ALL_SB_IDS_NORMALIZED
    }

    private fun mainHandSkyblockId(): String? {
        val p = mc.player ?: return null
        val inv = p.inventory ?: return null
        val acc = inv as InventoryAccessor
        return inv.getItem(acc.selectedSlot).skyblockIdNormalized()
    }

    private fun aliveBossTarget(): Entity? =
        if (s.bossTargetId >= 0) mc.level?.getEntity(s.bossTargetId)?.takeIf { it.isAlive } else null

    // --- Read holograms → queue ---------------------------------------------------------------

    private fun readBossAndQueue(hit: Entity, debugChat: Boolean) {
        val lines = collectLines(hit, debugChat)
        if (debugChat && debug && lines.isNotEmpty()) {
            modMessage("[Auto Dagger] context lines (${lines.size}): ${lines.joinToString(" | ") { it.noControlCodes }}")
        }
        val matched = lines.firstNotNullOfOrNull(::parseAttLine) ?: run {
            if (debugChat) dbg("no attunement in ${lines.size} line(s)")
            return
        }
        val att = matched.first
        val stripped = matched.second

        if (att == s.lastReadAtt) {
            if (s.pendingAtt == att || s.waitTicks > 0) return
            if (s.lastAppliedAtt == att && s.pendingAtt == null && s.waitTicks <= 0) return
        }
        s.lastReadAtt = att
        queue(att, stripped)
    }

    private fun collectLines(hit: Entity, logEmpty: Boolean): List<String> {
        val out = LinkedHashSet<String>()
        fun take(e: Entity) {
            for (piece in e.namePieces()) out.add(piece)
        }
        mountedTree(hit).forEach(::take)

        val level = mc.level ?: return out.toList()
        val nearby = level.getEntities(hit, hologramBox(hit)) { e ->
            e.isAlive && e !== hit && e !is net.minecraft.world.entity.player.Player
        }
        nearby.forEach { if (isHologram(it)) take(it) }
        if (logEmpty && debug && nearby.none { isHologram(it) }) {
            dbg("no hologram entities in strike scan (${standScan.toInt()}h)")
        }
        return out.toList()
    }

    private fun hologramBox(hit: Entity): AABB {
        val h = standScan.toDouble()
        val v = max(h * 2.5, 42.0)
        val c = hit.boundingBox.center
        return AABB(c, c).inflate(h, v, h)
    }

    private fun mountedTree(hit: Entity): List<Entity> {
        var top = hit
        while (top.vehicle != null) top = top.vehicle!!
        val q = ArrayDeque<Entity>()
        val out = mutableListOf<Entity>()
        q.add(top)
        while (q.isNotEmpty()) {
            val e = q.removeFirst()
            out.add(e)
            q.addAll(e.passengers)
        }
        return out
    }

    private fun isHologram(e: Entity) = e is ArmorStand || e.javaClass.simpleName == "TextDisplay"

    private fun Entity.namePieces(): Sequence<String> = sequence {
        val plain = name.string.trim()
        if (plain.isNotEmpty()) yield(plain)
        customName?.string?.trim()?.takeIf { it.isNotEmpty() && it != plain }?.let { yield(it) }
    }

    private fun parseAttLine(raw: String): Pair<BlazeAttunement, String>? {
        if (!looksLikeAtt(raw)) return null
        val stripped = raw.noControlCodes
        val att = BlazeAttunement.fromName(stripped) ?: return null
        return att to stripped
    }

    private fun looksLikeAtt(text: String): Boolean {
        if (Sym.hasHotSpring(text)) return true
        val u = text.noControlCodes.uppercase()
        return Sym.names.any { it in u }
    }

    private fun queue(att: BlazeAttunement, reason: String) {
        val supersede = s.pendingAtt != null && s.pendingAtt != att
        if (!supersede && s.pendingAtt == att) return
        s.pendingAtt = att
        s.waitTicks = if (supersede) {
            0
        } else {
            delay.toInt().coerceAtLeast(0) + (0..delayVariance.toInt().coerceAtLeast(0)).random()
        }
        dbg("queue ${att.matcher} (${reason.take(48)}) ticks=${s.waitTicks}")
        if (s.waitTicks <= 0 && enabled && canRun()) swapTo(att)
    }

    private fun swapTo(att: BlazeAttunement) {
        if (!enabled || !canRun()) return
        val level = mc.level ?: run {
            s.pendingAtt = null
            return
        }
        val gt = level.gameTime
        if (s.waitTicks > 0) {
            s.waitTicks--
            return
        }
        if (s.hotbarMutationGameTime == gt) return

        val inv = mc.player?.inventory ?: run {
            s.pendingAtt = null
            return
        }
        val acc = inv as InventoryAccessor

        val held = inv.getItem(acc.selectedSlot)
        val heldSb = held.skyblockIdNormalized()
        if (heldSb != null && heldSb in att.idsNormalized) {
            morphIfNeeded(inv, att, gt, held)
            s.pendingAtt = null
            s.lastAppliedAtt = att
            return
        }

        for (i in slotsMorphFirst(inv, att)) {
            val stack = inv.getItem(i)
            if (stack.isEmpty) continue
            val sb = stack.skyblockIdNormalized() ?: continue
            if (sb !in att.idsNormalized) continue
            if (acc.selectedSlot != i) {
                acc.setSelectedSlot(i)
                s.hotbarMutationGameTime = gt
            }
            morphIfNeeded(inv, att, gt, inv.getItem(acc.selectedSlot))
            s.pendingAtt = null
            s.lastAppliedAtt = att
            dbg("swapped to hotbar slot $i id=$sb")
            return
        }

        s.pendingAtt = null
        s.lastAppliedAtt = null
        if (debug) {
            val parts = (0..8).map { i -> "${i}:${inv.getItem(i).skyblockIdNormalized() ?: "—"}" }
            modMessage("[Auto Dagger] hotbar SB ids → ${parts.joinToString(" ")}")
        }
        dbg("no matching dagger on hotbar (need one of ${att.ids})")
    }

    /** Prefer slots whose vanilla sword already matches Hellion morph for this phase. */
    private fun slotsMorphFirst(inv: Inventory, att: BlazeAttunement): List<Int> {
        val good = mutableListOf<Int>()
        val poor = mutableListOf<Int>()
        for (i in 0..8) {
            val stack = inv.getItem(i)
            if (stack.isEmpty) continue
            val sb = stack.skyblockIdNormalized() ?: continue
            if (sb !in att.idsNormalized) continue
            if (stack.item == att.expectedItem) good.add(i) else poor.add(i)
        }
        return good + poor
    }

    private fun morphIfNeeded(inv: Inventory, att: BlazeAttunement, gt: Long, stack: ItemStack) {
        val sb = stack.skyblockIdNormalized() ?: return
        if (sb !in att.idsNormalized || stack.item == att.expectedItem) return
        rightClick()
        s.hotbarMutationGameTime = gt
    }

    private fun dbg(msg: String) {
        if (debug) modMessage("[Auto Dagger] $msg")
    }

    private fun ItemStack.skyblockIdNormalized(): String? {
        val raw = itemId ?: nullableID ?: return null
        return raw.uppercase().trim().takeIf { it.isNotEmpty() }
    }

    private object Sym {
        const val HOT_SPRINGS_CHAR: String = "♨"
        const val HOT_SPRINGS_UNICODE: String = "\u2668"
        const val HOT_SPRINGS_EMOJI: String = "\u2668\uFE0F"
        val names = listOf("ASHEN", "AURIC", "CRYSTAL", "SPIRIT")

        fun hasHotSpring(t: CharSequence) =
            t.indexOf(HOT_SPRINGS_CHAR) >= 0 || t.indexOf(HOT_SPRINGS_UNICODE) >= 0 || t.indexOf(HOT_SPRINGS_EMOJI) >= 0
    }

    private enum class BlazeAttunement(
        val matcher: String,
        val ids: Set<String>,
        val expectedItem: Item
    ) {
        Ashen("ASHEN ♨", setOf("HEARTFIRE_DAGGER", "BURSTFIRE_DAGGER", "FIREDUST_DAGGER"), Items.STONE_SWORD),
        Auric("AURIC ♨", setOf("HEARTFIRE_DAGGER", "BURSTFIRE_DAGGER", "FIREDUST_DAGGER"), Items.GOLDEN_SWORD),
        Crystal("CRYSTAL ♨", setOf("HEARTMAW_DAGGER", "BURSTMAW_DAGGER", "MAWDUST_DAGGER"), Items.DIAMOND_SWORD),
        Spirit("SPIRIT ♨", setOf("HEARTMAW_DAGGER", "BURSTMAW_DAGGER", "MAWDUST_DAGGER"), Items.IRON_SWORD);

        val idsNormalized: Set<String> = ids.map { it.uppercase() }.toSet()

        companion object {
            val ALL_SB_IDS_NORMALIZED: Set<String> = entries.flatMap { it.idsNormalized }.toSet()

            fun fromName(strippedLine: String): BlazeAttunement? {
                val u = strippedLine.uppercase()
                return entries.firstOrNull { att ->
                    val strippedMatcher = att.matcher.noControlCodes
                    val head = strippedMatcher.substringBefore(Sym.HOT_SPRINGS_CHAR)
                        .substringBefore(Sym.HOT_SPRINGS_UNICODE)
                        .substringBefore(Sym.HOT_SPRINGS_EMOJI)
                        .trim()
                        .uppercase()
                    head.isNotBlank() && (head in u || strippedMatcher.uppercase() in u)
                }
            }
        }
    }
}
