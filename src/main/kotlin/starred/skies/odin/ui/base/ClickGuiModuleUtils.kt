package starred.skies.odin.ui.base

import com.odtheking.odin.features.Module

object ClickGuiModuleUtils {
    fun getModuleCategory(module: Module): String {
        val key = (module.javaClass.simpleName + " " + module.name).lowercase()

        if (key.contains("highlight") || key.contains("trajectory") || key.contains("noglow") || key.contains("worldscanner")) {
            return "Rendering"
        }
        if (key.contains("autoterms") || key.contains("queueterms") || key.contains("simon") ||
            key.contains("autosuperboom") || key.contains("autopy")
        ) {
            return "Floor 7"
        }
        if (key.contains("dungeon") || key.contains("secret") || key.contains("livid") || key.contains("spiritbear") ||
            key.contains("sentry") || key.contains("door") || key.contains("etherwarp") || key.contains("farmkeys") ||
            key.contains("autodojo") || key.contains("autogfs") || key.contains("autosell") || key.contains("simon") ||
            key.contains("breakerhelper") || key.contains("keyhighlight") || key.contains("ghostblock") || key.contains("cancelinteract")
        ) {
            return "Dungeons"
        }
        return "General"
    }

    fun displayModuleName(module: Module): String {
        var name = module.name
        name = name.replace(Regex("\\s*\\([^)]*\\)"), "")
        name = name.replace(Regex("^\\s*[-_—]{2,}\\s*"), "")
            .replace(Regex("\\s*[-_—]{2,}\\s*$"), "")
        name = name.replace(Regex("\\s{2,}"), " ").trim()
        return if (name.isBlank()) module.name else name
    }

    fun isModuleEnabled(module: Module): Boolean {
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

    fun toggleModule(module: Module) {
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
}
