package org.kaiwasabi.privacyplus.stealth;

import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.kaiwasabi.privacyplus.chat.EncryptedChat;

// For command arguments the server completes itself (most plugin commands, /trigger), vanilla sends
// the typed text to the server on every keystroke, so the server sees commands and private messages
// you start but never send. Here such requests only go out when the player presses Tab, and never
// for text that the encrypted-chat lock is about to encrypt. Render thread only.
public final class CommandSuggestionGate {
    private static boolean tabRequest;
    private static boolean heldBackSinceTab;

    private CommandSuggestionGate() {
    }

    // input is what vanilla would send: the command typed so far, up to the cursor
    public static boolean shouldHoldBack(String input) {
        if (EncryptedChat.wouldEncrypt(input)) {
            return true;
        }
        if (!PrivacyPlusConfig.commandSuggestionsOnTab || tabRequest) {
            return false;
        }
        heldBackSinceTab = true;
        return true;
    }

    // Tab was pressed: if a request was held back while typing, make it now
    public static void onTab(Runnable requestSuggestions) {
        if (!heldBackSinceTab) {
            return;
        }
        heldBackSinceTab = false;
        tabRequest = true;
        try {
            requestSuggestions.run();
        } finally {
            tabRequest = false;
        }
    }
}
