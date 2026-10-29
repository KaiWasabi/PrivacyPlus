package org.kaiwasabi.privacyplus.stealth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.kaiwasabi.privacyplus.chat.ChatSettings;
import org.kaiwasabi.privacyplus.chat.ChatSettingsTestAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CommandSuggestionGateTest {
    // Stands in for CommandSuggestions.updateCommandInfo(): asks the gate like the real request would
    private final List<Boolean> sent = new ArrayList<>();

    private void type(String input) {
        this.sent.add(!CommandSuggestionGate.shouldHoldBack(input));
    }

    @AfterEach
    void reset() {
        PrivacyPlusConfig.commandSuggestionsOnTab = true;
        ChatSettings.setActive(null);
        CommandSuggestionGate.onTab(() -> { });
    }

    @Test
    void typingSendsNothingUntilTab() {
        type("/trigger a");
        type("/trigger ab");
        assertEquals(List.of(false, false), this.sent);

        CommandSuggestionGate.onTab(() -> type("/trigger ab"));
        assertEquals(List.of(false, false, true), this.sent, "Tab sends one request");

        type("/trigger abc");
        assertFalse(this.sent.getLast(), "and typing goes back to sending nothing");
    }

    @Test
    void tabDoesNothingIfNothingWasHeldBack() {
        CommandSuggestionGate.onTab(() -> type("/trigger x"));
        assertTrue(this.sent.isEmpty());
    }

    @Test
    void vanillaBehaviourWhenDisabled() {
        PrivacyPlusConfig.commandSuggestionsOnTab = false;
        type("/trigger a");
        assertTrue(this.sent.getLast());
    }

    @Test
    void textTheLockWillEncryptIsNeverSent() {
        PrivacyPlusConfig.commandSuggestionsOnTab = false;
        ChatSettingsTestAccess.activateTestChannel("trigger:0 msg:1");
        type("/msg Ste");
        assertTrue(this.sent.getLast(), "completing the recipient's name is fine");
        type("/trigger my secret");
        assertFalse(this.sent.getLast());

        PrivacyPlusConfig.commandSuggestionsOnTab = true;
        type("/msg Steve top sec");
        CommandSuggestionGate.onTab(() -> type("/msg Steve top sec"));
        assertFalse(this.sent.getLast(), "not even on Tab");
    }
}
