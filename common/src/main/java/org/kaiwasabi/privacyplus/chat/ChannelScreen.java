package org.kaiwasabi.privacyplus.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Channel;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Group;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Member;
import org.kaiwasabi.privacyplus.chat.Friends.Friend;
import org.jspecify.annotations.Nullable;

// One channel: its name and color, and then by kind: invite text for a passphrase channel; members for a
// friend channel you own (ticking and unticking friends); the owner and Leave for one you were invited to.
final class ChannelScreen extends Screen {
    static final int[] COLORS = {
        0x55FF55, 0x55FFFF, 0x5555FF, 0xFF55FF, 0xFF5555, 0xFFAA00, 0xFFFF55, 0xFFFFFF, 0xAAAAAA, 0x00AA00, 0x00AAAA, 0xAA00AA
    };
    static final String[] COLOR_NAMES = {"Green", "Aqua", "Blue", "Pink", "Red", "Gold", "Yellow", "White", "Gray", "Dark green", "Teal", "Purple"};

    private final Screen parent;
    private String name;
    private String draftName;
    private @Nullable Set<String> draftMembers;
    private @Nullable RowList list;
    private @Nullable String status;
    private int statusColor = RowList.GRAY;

    ChannelScreen(Screen parent, String name) {
        super(Component.literal("Channel"));
        this.parent = parent;
        this.name = name;
        this.draftName = name;
    }

    private int contentWidth() {
        return Math.min(380, this.width - 40);
    }

    @Override
    protected void init() {
        Channel channel = ChatSettings.get(this.name);
        if (channel == null) {
            this.minecraft.gui.setScreen(this.parent);
            return;
        }
        int width = this.contentWidth();
        int left = (this.width - width) / 2;
        int y = 34;

        EditBox nameBox = this.addRenderableWidget(new EditBox(this.font, left + 50, y, width - 50 - 74, 20, Component.literal("Channel name")));
        nameBox.setMaxLength(24);
        nameBox.setValue(this.draftName);
        nameBox.setResponder(value -> this.draftName = value);
        this.addRenderableWidget(Button.builder(Component.literal("Rename"), b -> this.rename())
            .tooltip(Tooltip.create(Component.literal("Only changes the name here; friends in the channel keep their own names for it")))
            .bounds(left + width - 70, y, 70, 20).build());
        y += 26;
        for (int i = 0; i < COLORS.length; i++) {
            int rgb = COLORS[i];
            RowList.Swatch swatch = new RowList.Swatch(rgb, channel.color() == rgb, COLOR_NAMES[i], picked -> {
                ChatSettings.setColor(this.name, picked);
                this.rebuildWidgets();
            });
            swatch.setPosition(left + 50 + i * (RowList.Swatch.SIZE + 3), y + 3);
            this.addRenderableWidget(swatch);
        }
        y += 26;

        Group group = channel.group();
        String myKey = Friends.publicKey();
        boolean owned = group != null && myKey != null && group.ownedBy(myKey);
        int buttonsY = this.height - 28;
        int listBottom = this.listBottom(owned);
        if (group == null) {
            this.addRenderableWidget(Button.builder(Component.literal("Copy invite text"), b -> {
                this.minecraft.keyboardHandler.setClipboard(ChatSettingsScreen.inviteText(channel));
                this.say("Copied. Paste it to your friends outside Minecraft (Discord, Signal...).", RowList.GREEN);
            }).tooltip(Tooltip.create(Component.literal("Text with the passphrase that PrivacyPlus can read back with Paste invite")))
                .bounds(left, y, (width - 4) / 2, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Copy passphrase"), b -> {
                this.minecraft.keyboardHandler.setClipboard(channel.passphrase());
                this.say("Copied the passphrase.", RowList.GREEN);
            }).bounds(left + (width + 4) / 2, y, (width - 4) / 2, 20).build());
            this.list = new RowList(this.minecraft, this.width, y + 26, listBottom, width);
            this.list.text(Component.literal("Everyone with the passphrase can read this channel. Share it outside Minecraft, never in game chat."),
                RowList.GRAY);
        } else if (owned) {
            this.list = new RowList(this.minecraft, this.width, y, listBottom, width);
            this.members(channel, group);
            this.addRenderableWidget(Button.builder(Component.literal("Save members"), b -> this.saveMembers(channel))
                .tooltip(Tooltip.create(Component.literal("New members get the key when they're next online. Removing someone gives everyone else"
                    + " a new key, so they can't read anything new.")))
                .bounds(left, buttonsY - 24, width, 20).build());
        } else {
            this.list = new RowList(this.minecraft, this.width, y, listBottom, width);
            Friend owner = Friends.byKey(group.ownerKey());
            this.list.row(Component.literal("Owner: " + (owner != null ? owner.name() : group.ownerName())), RowList.WHITE);
            if (owner == null) {
                this.list.text(Component.literal("The owner isn't your friend any more, so you won't get new keys for this channel."), RowList.GOLD);
            } else if (!owner.verified()) {
                this.list.text(Component.literal(owner.name() + " isn't verified yet: compare safety codes under Friends."), RowList.GOLD);
            }
            this.list.text(Component.literal("Only the owner can add or remove people. If someone leaves or is removed, the owner sends everyone"
                + " a new key; older messages then show as [old key]."), RowList.GRAY);
        }
        this.addRenderableWidget(this.list);

        String removeLabel = group == null ? "Remove" : owned ? "Delete channel" : "Leave channel";
        this.addRenderableWidget(Button.builder(Component.literal(removeLabel), b -> this.confirmRemove(channel, owned))
            .bounds(left, buttonsY, (width - 4) / 2, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
            .bounds(left + (width + 4) / 2, buttonsY, (width - 4) / 2, 20).build());
    }

    // Owners get a Save members button above Done and Delete
    private int listBottom(boolean owned) {
        return this.height - (owned ? 74 : 50);
    }

    // A row per finished friend, ticked if a member, with how far along they are
    private void members(Channel channel, Group group) {
        if (this.draftMembers == null) {
            this.draftMembers = new HashSet<>(group.members().stream().map(Member::publicKey).toList());
        }
        Set<String> draft = this.draftMembers;
        List<Friend> friends = Friends.all().stream().filter(Friend::confirmed).toList();
        if (friends.isEmpty()) {
            this.list.text(Component.literal("Exchange keys with friends first (Friends tab), then tick them here."), RowList.GRAY);
        }
        for (Friend friend : friends) {
            Member member = group.member(friend.publicKey());
            Component state = member == null ? Component.empty() : Component.literal("  " + switch (member.state()) {
                case INVITE -> "invitation waits for them to be online";
                case INVITED -> "invited";
                case JOINED -> member.ackedEpoch() >= group.epoch() ? "in" : "getting the new key";
                case EXPIRED -> "didn't answer";
            }).withStyle(member.state() == ChatSettings.MemberState.JOINED ? ChatFormatting.GREEN : ChatFormatting.GRAY);
            Checkbox box = Checkbox.builder(Component.empty(), this.font)
                .selected(draft.contains(friend.publicKey()))
                .onValueChange((checkbox, value) -> {
                    if (value) {
                        draft.add(friend.publicKey());
                    } else {
                        draft.remove(friend.publicKey());
                    }
                })
                .build();
            if (member != null && (member.state() == ChatSettings.MemberState.EXPIRED || member.state() == ChatSettings.MemberState.INVITED)) {
                Button again = Button.builder(Component.literal("Invite again"), b -> {
                    FriendChannels.inviteAgain(channel, friend.publicKey());
                    this.rebuildWidgets();
                }).size(80, 20).build();
                this.list.row(Component.literal(friend.name()).append(state), RowList.WHITE, again, box);
            } else {
                this.list.row(Component.literal(friend.name()).append(state), RowList.WHITE, box);
            }
        }
    }

    private void saveMembers(Channel channel) {
        Set<String> draft = this.draftMembers == null ? Set.of() : this.draftMembers;
        List<Friend> members = new ArrayList<>(Friends.all().stream().filter(f -> f.confirmed() && draft.contains(f.publicKey())).toList());
        FriendChannels.setMembers(channel, members);
        this.draftMembers = null;
        this.say("Saved. Keys go out as members come online.", RowList.GREEN);
        this.rebuildWidgets();
    }

    private void rename() {
        String wanted = this.draftName.trim();
        if (wanted.equals(this.name)) {
            return;
        }
        if (ChatSettings.renameChannel(this.name, wanted)) {
            this.name = wanted;
            this.say("Renamed.", RowList.GREEN);
        } else {
            this.say(ChatSettings.isValidName(wanted) ? "There is already a channel called " + wanted + "."
                : "Channel names use letters, digits, - and _ (up to 24).", RowList.RED);
        }
        this.rebuildWidgets();
    }

    private void confirmRemove(Channel channel, boolean owned) {
        String message = channel.group() == null ? "You won't be able to read this channel any more unless you add its passphrase again."
            : owned ? "Members are told the channel is gone, and its key is deleted here."
            : "The owner is told you left, and the others get a new key.";
        this.minecraft.gui.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                if (channel.group() != null) {
                    FriendChannels.removeChannel(channel);
                } else {
                    ChatSettings.removeChannel(channel.name());
                }
                this.minecraft.gui.setScreen(this.parent);
            } else {
                this.minecraft.gui.setScreen(this);
            }
        }, Component.literal((channel.group() == null ? "Remove " : owned ? "Delete " : "Leave ") + channel.name() + "?"), Component.literal(message),
            Component.literal(channel.group() == null ? "Remove" : owned ? "Delete" : "Leave"), CommonComponents.GUI_CANCEL));
    }

    private void say(String text, int color) {
        this.status = text;
        this.statusColor = color;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        Channel channel = ChatSettings.get(this.name);
        int width = this.contentWidth();
        int left = (this.width - width) / 2;
        String kind = channel == null || channel.group() == null ? "passphrase channel" : "friend channel";
        graphics.centeredText(this.font, Component.literal(this.name).withColor(channel == null ? 0xFFFFFF : channel.color())
            .append(Component.literal("  " + kind).withStyle(ChatFormatting.GRAY)), this.width / 2, 14, RowList.WHITE);
        graphics.text(this.font, "Name", left, 40, RowList.WHITE);
        graphics.text(this.font, "Color", left, 66, RowList.WHITE);
        if (this.status != null) {
            String myKey = Friends.publicKey();
            boolean owned = channel != null && channel.group() != null && myKey != null && channel.group().ownedBy(myKey);
            graphics.centeredText(this.font, this.status, this.width / 2, this.listBottom(owned) + 8, this.statusColor);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
