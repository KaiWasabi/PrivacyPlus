package org.kaiwasabi.privacyplus;

import java.nio.file.Path;
import org.kaiwasabi.privacyplus.chat.ChatSettings;
import org.kaiwasabi.privacyplus.chat.Coords;
import org.kaiwasabi.privacyplus.chat.Friends;
import org.kaiwasabi.privacyplus.logs.LogCleaner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Loader-independent entry point. The Fabric and Forge modules only call init().
public final class PrivacyPlus {
    public static final String MOD_ID = "privacyplus";
    public static final String MOD_NAME = "PrivacyPlus";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    private PrivacyPlus() {
    }

    public static void init(Path configDir) {
        PrivacyPlusConfig.load(configDir);
        LogCleaner.install(configDir.toAbsolutePath().getParent());
        ChatSettings.load(configDir);
        Friends.register();
        Coords.register();
        LOG.info("{} loaded", MOD_NAME);
    }
}
