package org.kaiwasabi.privacyplus.chat;

// Lets tests in other packages turn the lock on without deriving a key
public final class ChatSettingsTestAccess {
    private ChatSettingsTestAccess() {
    }

    public static void activateTestChannel(String encryptCommands) {
        ChatSettings.put("gate-test", ChatCryptoTest.KEY);
        ChatSettings.setEncryptCommands(encryptCommands);
        ChatSettings.setActive(ChatSettings.get("gate-test"));
    }
}
