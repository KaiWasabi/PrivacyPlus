package org.kaiwasabi.privacyplus.mixin;

import java.util.List;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.kaiwasabi.privacyplus.stealth.ServerPackLanguage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Translations are reloaded with every resource reload, including the ones that apply or remove a
// server's resource packs: the moment to note which translations those packs bring.
@Mixin(LanguageManager.class)
public abstract class LanguageManagerMixin {
    @Shadow
    public abstract String getSelected();

    @Shadow
    public abstract @Nullable LanguageInfo getLanguage(String code);

    @Inject(method = "onResourceManagerReload", at = @At("TAIL"))
    private void privacyplus$noteServerPackTranslations(ResourceManager resourceManager, CallbackInfo ci) {
        // The same languages vanilla just loaded: English, then the game language if a pack provides it
        String selected = this.getSelected();
        List<String> languageStack = selected.equals("en_us") || this.getLanguage(selected) == null
            ? List.of("en_us")
            : List.of("en_us", selected);
        ServerPackLanguage.reload(resourceManager, languageStack);
    }
}
