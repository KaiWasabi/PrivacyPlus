package org.kaiwasabi.privacyplus.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.kaiwasabi.privacyplus.PrivacyPlus;

public final class PrivacyPlusFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PrivacyPlus.init(FabricLoader.getInstance().getConfigDir());
    }
}
