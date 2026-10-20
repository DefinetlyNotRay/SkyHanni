package dev.jellyshort.mixin;

import dev.jellyshort.JellyShort;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void jellyshort$visible(Entity entity, Frustum frustum, double x, double y, double z,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (JellyShort.shouldHide(entity)) cir.setReturnValue(false);
    }
}
