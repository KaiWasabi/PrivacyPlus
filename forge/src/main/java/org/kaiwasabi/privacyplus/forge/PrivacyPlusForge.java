package org.kaiwasabi.privacyplus.forge;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.kaiwasabi.privacyplus.PrivacyPlus;

@Mod(PrivacyPlus.MOD_ID)
public final class PrivacyPlusForge {
    public PrivacyPlusForge(FMLJavaModLoadingContext context) {
        PrivacyPlus.init(FMLPaths.CONFIGDIR.get());
    }
}
