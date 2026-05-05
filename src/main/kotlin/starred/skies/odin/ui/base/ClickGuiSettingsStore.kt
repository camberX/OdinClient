package starred.skies.odin.ui.base

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale

data class ClickGuiSettingsSnapshot(
    val accentColor: Int,
    val showRainbowBar: Boolean,
    val accentBarSkeetFade: Boolean,
    val denseLayout: Boolean,
    val textScale: Float,
    val categorySize: Int,
    val menuOpenKeyCode: Int,
)

object ClickGuiSettingsStore {
    fun load(path: Path, defaults: ClickGuiSettingsSnapshot): ClickGuiSettingsSnapshot {
        return runCatching {
            Files.createDirectories(path.parent)
            if (!Files.exists(path)) {
                save(path, defaults)
                return defaults
            }
            val raw = Files.readString(path)
            if (raw.isBlank()) {
                save(path, defaults)
                return defaults
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
            ClickGuiSettingsSnapshot(
                accentColor = findInt("accentColor", defaults.accentColor),
                showRainbowBar = findBool("showRainbowBar", defaults.showRainbowBar),
                accentBarSkeetFade = findBool("accentBarSkeetFade", defaults.accentBarSkeetFade),
                denseLayout = findBool("denseLayout", defaults.denseLayout),
                textScale = findFloat("textScale", defaults.textScale).coerceIn(0.58f, 0.95f),
                categorySize = findInt("categorySize", defaults.categorySize).coerceIn(28, 56),
                menuOpenKeyCode = findInt("menuOpenKeyCode", defaults.menuOpenKeyCode),
            )
        }.getOrElse {
            save(path, defaults)
            defaults
        }
    }

    fun save(path: Path, s: ClickGuiSettingsSnapshot) {
        runCatching {
            Files.createDirectories(path.parent)
            val json = buildString {
                append("{\n")
                append("  \"accentColor\": ").append(s.accentColor).append(",\n")
                append("  \"showRainbowBar\": ").append(s.showRainbowBar).append(",\n")
                append("  \"accentBarSkeetFade\": ").append(s.accentBarSkeetFade).append(",\n")
                append("  \"denseLayout\": ").append(s.denseLayout).append(",\n")
                append("  \"textScale\": ").append(String.format(Locale.ROOT, "%.3f", s.textScale)).append(",\n")
                append("  \"categorySize\": ").append(s.categorySize).append(",\n")
                append("  \"menuOpenKeyCode\": ").append(s.menuOpenKeyCode).append("\n")
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
}
