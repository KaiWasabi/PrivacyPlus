package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.client.CommandHistory;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.kaiwasabi.privacyplus.chat.EncryptedChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Decrypts only what is shown on screen. logChatMessage runs before these calls with the original
// message, so latest.log keeps the ciphertext and plaintext never touches the disk.
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @ModifyArg(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessageToDisplayQueue(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V")
    )
    private GuiMessage privacyplus$decryptForDisplay(GuiMessage message) {
        return privacyplus$decrypt(message);
    }

    // The full history, used to rebuild the visible lines when the chat is resized
    @ModifyArg(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessageToQueue(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V")
    )
    private GuiMessage privacyplus$decryptForHistory(GuiMessage message) {
        return privacyplus$decrypt(message);
    }

    // Incognito: no "[CHAT] ..." lines in latest.log (or the rotated log files made from it)
    @Inject(method = "logChatMessage", at = @At("HEAD"), cancellable = true)
    private void privacyplus$incognitoLog(GuiMessage message, CallbackInfo ci) {
        if (PrivacyPlusConfig.incognito) {
            ci.cancel();
        }
    }

    // Incognito: sent commands stay in this session's up-arrow history but aren't written to
    // command_history.txt (it is rewritten from memory, so they mustn't enter it at all)
    @Redirect(method = "addRecentChat", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/CommandHistory;addCommand(Ljava/lang/String;)V"))
    private void privacyplus$incognitoHistory(CommandHistory history, String command) {
        if (!PrivacyPlusConfig.incognito) {
            history.addCommand(command);
        }
    }

    @Unique
    private static GuiMessage privacyplus$decrypt(GuiMessage message) {
        var content = EncryptedChat.decryptForDisplay(message.content());
        return content == message.content()
            ? message
            : new GuiMessage(message.addedTime(), content, message.signature(), message.source(), message.tag());
    }
}
