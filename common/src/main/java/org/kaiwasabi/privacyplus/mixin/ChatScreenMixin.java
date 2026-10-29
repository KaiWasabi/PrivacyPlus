package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.kaiwasabi.privacyplus.chat.ChatSettingsScreen;
import org.kaiwasabi.privacyplus.chat.Coords;
import org.kaiwasabi.privacyplus.chat.EncryptedChat;
import org.kaiwasabi.privacyplus.chat.GearButton;
import org.kaiwasabi.privacyplus.chat.LockButton;
import org.kaiwasabi.privacyplus.coords.ChannelPickerScreen;
import org.kaiwasabi.privacyplus.coords.CoordsButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {
    @Shadow
    protected EditBox input;

    protected ChatScreenMixin(Component title) {
        super(title);
    }

    @Shadow
    public abstract String normalizeChatMessage(String message);

    // The lock (on/off) and gear (settings) sit at the right end of the chat box, like No Chat
    // Reports' encryption button. Right-clicking the lock also opens the settings. The 📍 to their
    // left shares your location (/coords); right-click picks the channel.
    @Inject(method = "init", at = @At("TAIL"))
    private void privacyplus$addLock(CallbackInfo ci) {
        int lockX = this.width - LockButton.SIZE - 3;
        int gearX = lockX - GearButton.SIZE - 3;
        int pinX = gearX - CoordsButton.SIZE - 3;
        this.input.setWidth(pinX - 4 - 2);
        this.addRenderableWidget(new CoordsButton(pinX, this.height - 14,
            () -> {
                Coords.share(this.minecraft, null, null, this);
                this.setFocused(this.input);
            },
            () -> this.minecraft.gui.setScreen(new ChannelPickerScreen(this))));
        this.addRenderableWidget(new GearButton(gearX, this.height - 14, () -> this.minecraft.gui.setScreen(new ChatSettingsScreen(this))));
        this.addRenderableWidget(new LockButton(lockX, this.height - 14,
            () -> {
                EncryptedChat.toggleLock(this.minecraft, this);
                this.setFocused(this.input);
            },
            () -> this.minecraft.gui.setScreen(new ChatSettingsScreen(this))));
    }

    // Clicking the 🔒 in front of an encrypted line opens the settings. Handled here, before vanilla
    // looks at the click, so nothing about it ever reaches the server.
    @Inject(method = "handleComponentClicked", at = @At("HEAD"), cancellable = true)
    private void privacyplus$lockMarkClicked(Style clicked, boolean allowInsertions, CallbackInfoReturnable<Boolean> cir) {
        if (EncryptedChat.opensSettings(clicked)) {
            this.minecraft.gui.setScreen(new ChatSettingsScreen(this));
            cir.setReturnValue(true);
        } else if (EncryptedChat.runLocalClick(this.minecraft, clicked)) {
            cir.setReturnValue(true);
        }
    }

    // Runs before vanilla records the input in its history (which writes commands to disk) or sends it
    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void privacyplus$encryptedChat(String msg, boolean addToRecent, CallbackInfo ci) {
        if (EncryptedChat.handleInput(this.minecraft, this.normalizeChatMessage(msg), addToRecent)) {
            ci.cancel();
        }
    }

    // Always visible while typing, so it's obvious whether a message will go out encrypted
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void privacyplus$drawLockLabel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        String label = EncryptedChat.lockLabel();
        if (label != null) {
            graphics.text(this.font, label, this.width - this.font.width(label) - 4, this.height - 24, 0xFF55FFFF);
        }
    }
}
