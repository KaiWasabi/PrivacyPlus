package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.client.telemetry.ClientTelemetryManager;
import net.minecraft.client.telemetry.TelemetryEventSender;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.kaiwasabi.privacyplus.telemetry.TelemetryBlocker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// createEventSender() backs both the outside-session sender (game load, graphics capabilities)
// and every per-world sender, so this covers required and optional events alike.
@Mixin(ClientTelemetryManager.class)
public abstract class ClientTelemetryManagerMixin {
    @Inject(method = "createEventSender", at = @At("HEAD"), cancellable = true)
    private void privacyplus$blockTelemetry(CallbackInfoReturnable<TelemetryEventSender> cir) {
        if (PrivacyPlusConfig.blockTelemetry) {
            cir.setReturnValue(TelemetryBlocker.SENDER);
        }
    }
}
