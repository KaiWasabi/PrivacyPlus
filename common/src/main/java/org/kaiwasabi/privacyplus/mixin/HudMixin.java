package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.BossHealthOverlay;
import org.kaiwasabi.privacyplus.coords.CoordsTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The /coords tracker: drawn right after the boss bars, so only while the HUD is shown (F1 hides both),
// checked every client tick for arrival or timeout, and dropped when leaving the world
@Mixin(Hud.class)
public abstract class HudMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    public abstract BossHealthOverlay getBossOverlay();

    @Inject(method = "extractBossOverlay", at = @At("TAIL"))
    private void privacyplus$extractTracker(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        int bossBars = ((BossHealthOverlayAccessor) this.getBossOverlay()).privacyplus$getEvents().size();
        CoordsTracker.extract(graphics, this.minecraft, CoordsTracker.topBelowBossBars(bossBars, graphics.guiHeight()));
    }

    @Inject(method = "tick(Z)V", at = @At("HEAD"))
    private void privacyplus$tickTracker(boolean pause, CallbackInfo ci) {
        CoordsTracker.tick(this.minecraft);
    }

    @Inject(method = "onDisconnected", at = @At("HEAD"))
    private void privacyplus$stopTracker(CallbackInfo ci) {
        CoordsTracker.clear();
    }
}
