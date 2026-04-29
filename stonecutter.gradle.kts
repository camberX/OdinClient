import org.gradle.kotlin.dsl.replace

plugins {
    id("dev.kikugie.stonecutter")
    alias(libs.plugins.loom) apply false
}

stonecutter active "1.21.11"

stonecutter parameters {
    swaps["mod_version"] = "\"" + property("mod.version") + "\""
    swaps["mod_id"] = "\"" + property("mod.id") + "\""
    swaps["minecraft"] = "\"" + node.metadata.version + "\""
    // VCS is 1.21.11+ (Identifier). For 1.21.10, rewrite back to ResourceLocation.
    replacements {
        string {
            direction = eval(current.version, "<1.21.11")
            replace("import net.minecraft.resources.Identifier", "import net.minecraft.resources.ResourceLocation")
        }
        string {
            direction = eval(current.version, "<1.21.11")
            replace("Identifier", "ResourceLocation")
        }
    }
}