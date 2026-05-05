package starred.skies.odin.mixin.mixins;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import starred.skies.odin.features.impl.cheats.AspectRatio;

@Mixin(GameRenderer.class)
public class GameRendererAspectMixin {
    @ModifyArg(
            method = "getProjectionMatrix",
            at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;perspective(FFFF)Lorg/joml/Matrix4f;"),
            index = 1,
            require = 0
    )
    private float odin$forceAspectRatio(float vanillaAspect) {
        if (!AspectRatio.isAspectRatioActive()) return vanillaAspect;
        return vanillaAspect * AspectRatio.aspectRatioMultiplier();
    }
}
