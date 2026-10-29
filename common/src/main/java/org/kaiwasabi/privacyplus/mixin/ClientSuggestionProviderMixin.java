package org.kaiwasabi.privacyplus.mixin;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import org.kaiwasabi.privacyplus.stealth.CommandSuggestionGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// The only place the client sends typed command text to the server for suggestions
@Mixin(ClientSuggestionProvider.class)
public abstract class ClientSuggestionProviderMixin {
    @Inject(method = "customSuggestion", at = @At("HEAD"), cancellable = true)
    private void privacyplus$holdBack(CommandContext<?> context, CallbackInfoReturnable<CompletableFuture<Suggestions>> cir) {
        if (CommandSuggestionGate.shouldHoldBack(context.getInput())) {
            cir.setReturnValue(Suggestions.empty());
        }
    }
}
