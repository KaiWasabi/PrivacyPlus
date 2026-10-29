package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.kaiwasabi.privacyplus.chat.EncryptedChat;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// "Encrypted messages you can't read: hide". Runs after logChatMessage, so latest.log keeps the line (as
// ciphertext) like any other; it just never reaches the screen or the chat history.
@Mixin(ChatComponent.class)
public abstract class ChatComponentHideMixin {
    @Inject(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessageToDisplayQueue(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V"),
        cancellable = true
    )
    private void privacyplus$hideUnreadable(Component contents, @Nullable MessageSignature signature, GuiMessageSource source,
                                            @Nullable GuiMessageTag tag, CallbackInfo ci) {
        if (EncryptedChat.hideLine(contents)) {
            ci.cancel();
        }
    }
}
