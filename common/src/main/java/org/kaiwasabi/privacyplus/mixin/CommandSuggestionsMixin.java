package org.kaiwasabi.privacyplus.mixin;

import com.mojang.brigadier.suggestion.Suggestions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.FormattedCharSequence;
import org.kaiwasabi.privacyplus.chat.EncryptedChat;
import org.kaiwasabi.privacyplus.stealth.CommandSuggestionGate;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin {
    @Shadow
    @Final
    private EditBox input;
    @Shadow
    @Final
    private List<FormattedCharSequence> commandUsage;
    @Shadow
    private @Nullable CompletableFuture<Suggestions> pendingSuggestions;
    @Shadow
    private CommandSuggestions.@Nullable SuggestionsList suggestions;

    @Shadow
    public abstract void updateCommandInfo();

    @Shadow
    public abstract void showSuggestions(boolean immediateNarration);

    // Tab with no suggestion list open: ask the server now for what was held back while typing. The
    // answer arrives a moment later and shows like vanilla's (or on the next Tab if auto-suggest is off).
    @Inject(method = "keyPressed", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/CommandSuggestions;showSuggestions(Z)V"))
    private void privacyplus$requestOnTab(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        CommandSuggestionGate.onTab(this::updateCommandInfo);
    }

    // /pp is handled locally and the server doesn't know it, so vanilla would show "Unknown command" in
    // red. Offer its subcommands and channel names instead, worked out locally (nothing is sent).
    @Inject(method = "updateCommandInfo", at = @At("HEAD"), cancellable = true)
    private void privacyplus$localCommand(CallbackInfo ci) {
        CompletableFuture<Suggestions> local = EncryptedChat.suggestLocalCommand(this.input.getValue(), this.input.getCursorPosition());
        if (local == null) {
            return;
        }
        this.commandUsage.clear();
        this.input.setSuggestion(null);
        this.suggestions = null;
        this.pendingSuggestions = local;
        if (Minecraft.getInstance().options.autoSuggestions().get()) {
            this.showSuggestions(false);
        }
        ci.cancel();
    }
}
