package starred.skies.odin.commands

import com.github.stivais.commodore.Commodore
import com.github.stivais.commodore.utils.GreedyString
import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.utils.handlers.schedule
import com.odtheking.odin.utils.modMessage
import starred.skies.odin.features.impl.cheats.AutoPy

val autoPyCommand = Commodore("autopy") {
    literal("start").runs { tickArg: GreedyString? ->
        val startTick = tickArg?.string?.trim()?.toIntOrNull()?.coerceIn(0, 95) ?: 95
        startSimulationWithSingleplayerDelay(startTick)
    }

    literal("set").runs { tickArg: GreedyString ->
        val tick = tickArg.string.trim().toIntOrNull()
            ?: return@runs modMessage("Usage: /autopy set <tick>  (tick range: -1..95)")
        AutoPy.debugSetSimulationTick(tick)
        modMessage("AutoPY simulation tick set to ${AutoPy.debugCurrentPyTick()}.")
    }

    literal("stop").runs {
        AutoPy.debugStopSimulation()
        modMessage("AutoPY simulation stopped and state reset.")
    }

    literal("status").runs {
        modMessage("AutoPY sim status: tick=${AutoPy.debugCurrentPyTick()}, class=${AutoPy.debugCurrentClassMode()}.")
    }

    literal("sim") {
        literal("start").runs { tickArg: GreedyString? ->
            val startTick = tickArg?.string?.trim()?.toIntOrNull()?.coerceIn(0, 95) ?: 95
            startSimulationWithSingleplayerDelay(startTick)
        }

        literal("set").runs { tickArg: GreedyString ->
            val tick = tickArg.string.trim().toIntOrNull()
                ?: return@runs modMessage("Usage: /autopy sim set <tick>  (tick range: -1..95)")
            AutoPy.debugSetSimulationTick(tick)
            modMessage("AutoPY simulation tick set to ${AutoPy.debugCurrentPyTick()}.")
        }

        literal("stop").runs {
            AutoPy.debugStopSimulation()
            modMessage("AutoPY simulation stopped and state reset.")
        }

        literal("status").runs {
            modMessage("AutoPY sim status: tick=${AutoPy.debugCurrentPyTick()}, class=${AutoPy.debugCurrentClassMode()}.")
        }
    }
}

private fun startSimulationWithSingleplayerDelay(startTick: Int) {
    if (mc.hasSingleplayerServer()) {
        modMessage("AutoPY simulation will start in 5s (singleplayer delay) at tick=$startTick.")
        schedule(100) {
            AutoPy.debugStartSimulation(startTick)
            modMessage("AutoPY simulation started at tick=$startTick (class=${AutoPy.debugCurrentClassMode()}).")
        }
    } else {
        AutoPy.debugStartSimulation(startTick)
        modMessage("AutoPY simulation started at tick=$startTick (class=${AutoPy.debugCurrentClassMode()}).")
    }
}
