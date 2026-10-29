package org.kaiwasabi.privacyplus.chat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Kind;
import org.kaiwasabi.privacyplus.chat.Friends.Friend;
import org.jspecify.annotations.Nullable;

// Making a channel: a passphrase channel (everyone types or pastes the same passphrase), or a friend channel
// (pick friends you exchanged keys with; your client hands out a random key and changes it when someone
// leaves). Also where Paste invite lands, with the name and passphrase filled in.
final class NewChannelScreen extends Screen {
    private final Screen parent;
    private Kind kind;
    private String draftName;
    private String draftPassphrase;
    private final Set<String> picked = new HashSet<>();
    private @Nullable String error;
    private boolean weakConfirmed;
    // Below this many bits (see strengthBits), creating the channel asks first
    static final int WEAK_BELOW = 60;

    NewChannelScreen(Screen parent) {
        this(parent, Kind.PASSPHRASE, "", "");
    }

    // From an invite: a passphrase channel with these filled in
    NewChannelScreen(Screen parent, String name, String passphrase) {
        this(parent, Kind.PASSPHRASE, ChatSettings.freeName(name), passphrase);
    }

    private NewChannelScreen(Screen parent, Kind kind, String name, String passphrase) {
        super(Component.literal("New channel"));
        this.parent = parent;
        this.kind = kind;
        this.draftName = name;
        this.draftPassphrase = passphrase;
    }

    private int contentWidth() {
        return Math.min(380, this.width - 40);
    }

    @Override
    protected void init() {
        int width = this.contentWidth();
        int left = (this.width - width) / 2;
        int field = left + 74;
        int fieldWidth = width - 74;
        int y = 34;
        this.addRenderableWidget(ChatSettingsScreen.cycle(List.of(Kind.PASSPHRASE, Kind.FRIENDS), this.kind,
            v -> v == Kind.PASSPHRASE ? "Passphrase: share it yourself" : "Friends: keys handed out for you", fieldWidth, v -> {
                this.kind = v;
                this.error = null;
                this.rebuildWidgets();
            })).setPosition(field, y);
        y += 24;
        EditBox name = this.addRenderableWidget(new EditBox(this.font, field, y, fieldWidth, 20, Component.literal("Channel name")));
        name.setMaxLength(24);
        name.setHint(Component.literal("letters, digits, - and _"));
        name.setValue(this.draftName);
        name.setResponder(value -> this.draftName = value);
        y += 24;

        if (this.kind == Kind.PASSPHRASE) {
            EditBox passphrase = this.addRenderableWidget(new EditBox(this.font, field, y, fieldWidth - 108, 20, Component.literal("Passphrase")));
            passphrase.setMaxLength(128);
            passphrase.setHint(Component.literal("at least 12 characters"));
            passphrase.setValue(this.draftPassphrase);
            passphrase.setResponder(value -> {
                this.draftPassphrase = value;
                this.weakConfirmed = false;
            });
            this.addRenderableWidget(Button.builder(Component.literal("Random"), b -> passphrase.setValue(ChatSettings.randomPassphrase()))
                .tooltip(Tooltip.create(Component.literal("A long random passphrase: the safest kind")))
                .bounds(field + fieldWidth - 104, y, 52, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Copy"), b -> this.minecraft.keyboardHandler.setClipboard(this.draftPassphrase))
                .bounds(field + fieldWidth - 48, y, 48, 20).build());
        } else {
            RowList list = new RowList(this.minecraft, this.width, y + 14, this.height - 50, width);
            List<Friend> friends = Friends.all().stream().filter(Friend::confirmed).toList();
            if (friends.isEmpty()) {
                list.text(Component.literal("No friends yet. Exchange keys with friends first (settings > Friends), then come back."), RowList.GRAY);
            }
            for (Friend friend : friends) {
                Checkbox box = Checkbox.builder(Component.empty(), this.font).selected(this.picked.contains(friend.publicKey()))
                    .onValueChange((checkbox, value) -> {
                        if (value) {
                            this.picked.add(friend.publicKey());
                        } else {
                            this.picked.remove(friend.publicKey());
                        }
                    }).build();
                list.row(Component.literal(friend.name() + (friend.verified() ? "" : "  (not verified)")), RowList.WHITE, box);
            }
            this.addRenderableWidget(list);
        }

        int buttonsY = this.height - 28;
        this.addRenderableWidget(Button.builder(Component.literal("Create"), b -> this.create()).bounds(left, buttonsY, (width - 4) / 2, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
            .bounds(left + (width + 4) / 2, buttonsY, (width - 4) / 2, 20).build());
    }

    private void create() {
        String name = this.draftName.trim();
        if (!ChatSettings.isValidName(name)) {
            this.error = "Channel names use letters, digits, - and _ (up to 24).";
        } else if (ChatSettings.get(name) != null) {
            this.error = "There is already a channel called " + name + ".";
        } else if (this.kind == Kind.PASSPHRASE) {
            if (this.draftPassphrase.length() < 12) {
                this.error = "Use a passphrase of at least 12 characters (try Random).";
            } else if (ChatSettings.passphraseBits(this.draftPassphrase) < WEAK_BELOW && !this.weakConfirmed) {
                this.minecraft.gui.setScreen(new ConfirmScreen(yes -> {
                    this.weakConfirmed = yes;
                    this.minecraft.gui.setScreen(this);
                    if (yes) {
                        this.create();
                    }
                }, Component.literal("Use a weak passphrase?"), Component.literal("The server sees every encrypted message and can try"
                    + " guessing the passphrase offline, with lists of common words and passwords made once for everyone. A passphrase"
                    + " like this one may be found. Random makes one that can't. Use it only if it's a friend's channel you're joining."),
                    Component.literal("Use it anyway"), CommonComponents.GUI_CANCEL));
                return;
            } else {
                ChatSettings.addChannel(name, this.draftPassphrase);
                this.minecraft.gui.setScreen(new ChannelScreen(this.parent, name));
                return;
            }
        } else {
            EncryptedChat.setSelfName(this.minecraft.getUser().getName());
            List<Friend> members = Friends.all().stream().filter(f -> f.confirmed() && this.picked.contains(f.publicKey())).toList();
            this.error = members.isEmpty() ? "Pick at least one friend." : FriendChannels.create(name, members);
            if (this.error == null) {
                this.minecraft.gui.setScreen(this.parent);
                return;
            }
        }
        this.rebuildWidgets();
    }

    static String strength(String passphrase) {
        if (passphrase.isEmpty()) {
            return "";
        }
        int bits = ChatSettings.passphraseBits(passphrase);
        if (passphrase.length() < 12 || bits < WEAK_BELOW) {
            return "Weak: the server could guess it from encrypted messages";
        }
        return bits < 80 ? "Fair: longer is safer" : "Strong";
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int width = this.contentWidth();
        int left = (this.width - width) / 2;
        graphics.centeredText(this.font, this.title.getString(), this.width / 2, 14, RowList.WHITE);
        graphics.text(this.font, "Kind", left, 40, RowList.WHITE);
        graphics.text(this.font, "Name", left, 64, RowList.WHITE);
        if (this.kind == Kind.PASSPHRASE) {
            graphics.text(this.font, "Passphrase", left, 88, RowList.WHITE);
            String strength = strength(this.draftPassphrase);
            graphics.text(this.font, strength, left + 74, 108, strength.startsWith("Strong") ? RowList.GREEN
                : strength.startsWith("Fair") ? RowList.YELLOW : RowList.GOLD);
            graphics.textWithWordWrap(this.font, Component.literal("Everyone in the channel needs this passphrase. Share it outside Minecraft"
                + " (Discord, Signal, in person), never in game chat. After creating, Copy invite text makes a message they can paste."),
                left, 124, width, RowList.GRAY);
        } else {
            graphics.text(this.font, "Members: tick the friends to invite", left, 86, RowList.WHITE);
        }
        if (this.error != null) {
            graphics.centeredText(this.font, this.error, this.width / 2, this.height - 42, RowList.RED);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
