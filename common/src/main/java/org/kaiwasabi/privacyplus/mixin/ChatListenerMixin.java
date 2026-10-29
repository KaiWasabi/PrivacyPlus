package org.kaiwasabi.privacyplus.mixin;

import com.mojang.authlib.GameProfile;
import java.time.Instant;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import org.kaiwasabi.privacyplus.chat.EncryptedChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Tells encrypted chat who really sent the player chat message being shown (the packet's sender, not words
// on the decorated line), so a decrypted message is attributed to the name its encrypted sender check
// confirms. System messages have no sender: the setting is cleared for them.
@Mixin(ChatListener.class)
public abstract class ChatListenerMixin {
    @Inject(method = "showMessageToPlayer", at = @At("HEAD"))
    private void privacyplus$senderOn(ChatType.Bound boundChatType, PlayerChatMessage message, Component decoratedMessage, GameProfile sender,
                                      boolean onlyShowSecure, Instant received, CallbackInfoReturnable<Boolean> cir) {
        EncryptedChat.setPacketSender(sender.name());
    }

    @Inject(method = "showMessageToPlayer", at = @At("RETURN"))
    private void privacyplus$senderOff(ChatType.Bound boundChatType, PlayerChatMessage message, Component decoratedMessage, GameProfile sender,
                                       boolean onlyShowSecure, Instant received, CallbackInfoReturnable<Boolean> cir) {
        EncryptedChat.setPacketSender(null);
    }

    @Inject(method = "handleSystemMessage", at = @At("HEAD"))
    private void privacyplus$noSender(Component message, boolean remote, CallbackInfo ci) {
        EncryptedChat.setPacketSender(null);
    }
}
