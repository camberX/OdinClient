package starred.skies.odin.mixin.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import starred.skies.odin.events.InteractEvent;
import starred.skies.odin.features.impl.cheats.AutoDagger;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Shadow
    public HitResult hitResult;

    @Shadow
    public LocalPlayer player;

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void preAttack(CallbackInfoReturnable<Boolean> cir) {
        if (hitResult == null) return;
        if (hitResult.getType() != HitResult.Type.BLOCK) return;

        BlockHitResult bhr = (BlockHitResult) hitResult;
        if (new InteractEvent.HitBlock(player.getMainHandItem(), bhr.getBlockPos()).postAndCatch()) cir.cancel();
    }

    @Inject(method = "startAttack", at = @At("HEAD"))
    private void odinAutoDaggerEntityTarget(CallbackInfoReturnable<Boolean> cir) {
        if (hitResult == null || hitResult.getType() != HitResult.Type.ENTITY) return;
        Entity e = ((EntityHitResult) hitResult).getEntity();
        AutoDagger.INSTANCE.noteMeleeTarget(e);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void onContinueAttack(boolean leftClick, CallbackInfo ci) {
        if (hitResult == null) return;
        if (hitResult.getType() != HitResult.Type.BLOCK) return;

        BlockHitResult bhr = (BlockHitResult) hitResult;
        if (new InteractEvent.HitBlock(player.getMainHandItem(), bhr.getBlockPos()).postAndCatch()) ci.cancel();
    }
}