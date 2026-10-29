package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.kaiwasabi.privacyplus.stealth.ProbeAlarm;
import org.kaiwasabi.privacyplus.stealth.ProbeShield;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The name field is filled from the item's (server-supplied) name and echoed back as a rename packet.
// Both methods must resolve the same way, or onNameChanged sees a "rename" that the player never typed.
@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin {
    @Redirect(
        method = {"slotChanged", "onNameChanged"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/Component;getString()Ljava/lang/String;")
    )
    private String privacyplus$resolveLikeVanilla(Component name) {
        return ProbeShield.resolve(name);
    }

    // An item placed in the first slot whose name is a probe (onNameChanged would only see it again)
    @Inject(method = "slotChanged", at = @At("HEAD"))
    private void privacyplus$noticeProbe(AbstractContainerMenu container, int slotIndex, ItemStack itemStack, CallbackInfo ci) {
        if (slotIndex == 0 && !itemStack.isEmpty()) {
            ProbeAlarm.inspect(ProbeAlarm.Kind.ANVIL, itemStack.getHoverName());
        }
    }
}
