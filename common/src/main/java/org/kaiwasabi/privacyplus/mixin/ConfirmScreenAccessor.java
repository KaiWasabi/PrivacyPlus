package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

// The message is only turned into widgets in init(), so it can be changed until the screen is shown
@Mixin(ConfirmScreen.class)
public interface ConfirmScreenAccessor {
    @Accessor("message")
    Component privacyplus$getMessage();

    @Mutable
    @Accessor("message")
    void privacyplus$setMessage(Component message);
}
