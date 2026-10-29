package org.kaiwasabi.privacyplus.coords;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.chat.ChatSettings;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Channel;
import org.kaiwasabi.privacyplus.chat.ChatSettingsScreen;
import org.kaiwasabi.privacyplus.chat.Coords;

// Right-click on the 📍 in the chat box: pick the channel to share your location with. Goes back to
// the chat box afterwards, where the confirmation shows.
public final class ChannelPickerScreen extends Screen {
    private static final int BUTTON_WIDTH = 120;
    private static final int ROW = 24;
    private final Screen parent;

    public ChannelPickerScreen(Screen parent) {
        super(Component.literal("Share my location with"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        List<Channel> channels = ChatSettings.all();
        int rows = Math.max(1, (this.height - 90) / ROW);
        int columns = Math.max(1, (channels.size() + rows - 1) / rows);
        int left = this.width / 2 - (columns * (BUTTON_WIDTH + 4) - 4) / 2;
        int top = 36;
        for (int i = 0; i < channels.size(); i++) {
            Channel channel = channels.get(i);
            boolean ready = channel.readyKey() != null;
            Button button = Button.builder(Component.literal(channel.name() + (ready ? "" : " (key not ready)")), b -> {
                this.onClose();
                Coords.share(this.minecraft, channel, null, this.parent);
            }).bounds(left + i / rows * (BUTTON_WIDTH + 4), top + i % rows * ROW, BUTTON_WIDTH, 20).build();
            button.active = ready;
            this.addRenderableWidget(button);
        }
        if (channels.isEmpty()) {
            this.addRenderableWidget(Button.builder(Component.literal("Add a channel..."),
                b -> this.minecraft.gui.setScreen(new ChatSettingsScreen(this.parent))).bounds(this.width / 2 - 100, top, 200, 20).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
            .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title.getString(), this.width / 2, 14, 0xFFFFFFFF);
        graphics.centeredText(this.font, "Encrypted, so only people in the channel can read it", this.width / 2, 24, 0xFFA0A0A0);
    }

    // Like the chat box itself, this doesn't pause a single-player game
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
