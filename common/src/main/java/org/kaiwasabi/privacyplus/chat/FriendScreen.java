package org.kaiwasabi.privacyplus.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.chat.Friends.Friend;

// A friend's safety code, big enough to read out, and the button to mark them verified once it matches
// what they see. A server that swapped keys during the exchange can't make the two codes match.
final class FriendScreen extends Screen {
    private final ChatSettingsScreen parent;
    private final String publicKey;

    FriendScreen(ChatSettingsScreen parent, String publicKey) {
        super(Component.literal("Safety code"));
        this.parent = parent;
        this.publicKey = publicKey;
    }

    @Override
    protected void init() {
        Friend friend = Friends.byKey(this.publicKey);
        if (friend == null) {
            this.minecraft.gui.setScreen(this.parent);
            return;
        }
        int width = Math.min(300, this.width - 20);
        int left = (this.width - width) / 2;
        // Just above Remove and Done, so the explanation above has room even in a small window
        int y = this.height - 52;
        if (friend.confirmed()) {
            String label = friend.verified() ? "Mark as not verified" : "The codes match: mark verified";
            this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
                Friends.setVerified(this.publicKey, !friend.verified());
                this.rebuildWidgets();
            }).bounds(left, y, width, 20).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("Remove friend"), b -> this.parent.confirmRemove(friend))
            .bounds(left, this.height - 28, (width - 4) / 2, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
            .bounds(left + (width + 4) / 2, this.height - 28, (width - 4) / 2, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        Friend friend = Friends.byKey(this.publicKey);
        if (friend == null) {
            return;
        }
        int width = Math.min(300, this.width - 20);
        int left = (this.width - width) / 2;
        int mid = this.height / 2;
        graphics.centeredText(this.font, "Safety code with " + friend.name(), this.width / 2, 14, RowList.WHITE);
        Component state = !friend.confirmed()
            ? Component.literal("Waiting for " + friend.name() + " to finish the key exchange").withStyle(ChatFormatting.GRAY)
            : friend.verified() ? Component.literal("Verified").withStyle(ChatFormatting.GREEN)
            : Component.literal("Not verified yet").withStyle(ChatFormatting.GOLD);
        graphics.centeredText(this.font, state, this.width / 2, mid - 60, RowList.WHITE);
        if (friend.safetyCode() != null) {
            // Twice the size: drawn as four groups with wide gaps
            String[] groups = friend.safetyCode().split(" ");
            int x = this.width / 2 - (groups.length * 44 - 12) / 2;
            for (String group : groups) {
                graphics.fill(x - 4, mid - 42, x + 36, mid - 24, 0xFF202020);
                graphics.text(this.font, group, x + 16 - this.font.width(group) / 2, mid - 37, 0xFF55FFFF);
                x += 44;
            }
        }
        graphics.textWithWordWrap(this.font, Component.literal("Compare this code with " + friend.name() + " outside Minecraft: by voice, Signal or"
            + " in person, not in game chat. If you both see the same code, nobody (not even the server) is in the middle of your messages."
            + " If it differs, remove " + friend.name() + " and exchange keys again."), left, mid - 14, width, RowList.GRAY);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
