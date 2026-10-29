package org.kaiwasabi.privacyplus.chat;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Channel;
import org.kaiwasabi.privacyplus.chat.ChatSettings.CommandRule;
import org.kaiwasabi.privacyplus.chat.ChatSettings.LockStyle;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Unreadable;
import org.kaiwasabi.privacyplus.chat.Friends.Friend;
import org.jspecify.annotations.Nullable;

// Opened by right-clicking the lock (or the gear, or /pp settings). Three sections: Channels (use, edit,
// make and join channels), Friends (exchange keys, compare safety codes) and Options. Everything is done
// with buttons, so passphrases and keys never have to be typed into chat or a file. Controls stay out of
// the top right corner, where toasts appear.
public final class ChatSettingsScreen extends Screen {
    public enum Section { CHANNELS, FRIENDS, OPTIONS }

    private static final int TABS_Y = 34;
    private static final int TOP = 60;
    // Text after this in an invite is the channel name and the passphrase (base64)
    static final String INVITE_TAG = "privacyplus-channel:";
    private static final Pattern INVITE = Pattern.compile(Pattern.quote(INVITE_TAG) + "([A-Za-z0-9_-]{1,24}):([A-Za-z0-9_-]+)");

    private final @Nullable Screen parent;
    private Section section;
    private @Nullable RowList list;
    private double scroll;
    private String friendName = "";
    // The channel a key exchange request goes through; empty: the one we last saw them write in
    private String through = "";
    private @Nullable String status;
    private int statusColor = RowList.GRAY;
    private @Nullable List<CommandRule> commandDraft;

    public ChatSettingsScreen(@Nullable Screen parent) {
        this(parent, Section.CHANNELS);
    }

    public ChatSettingsScreen(@Nullable Screen parent, Section section) {
        super(Component.literal("Encrypted chat"));
        this.parent = parent;
        this.section = section;
    }

    public Section section() {
        return this.section;
    }

    private int contentWidth() {
        return Math.min(380, this.width - 40);
    }

    @Override
    protected void init() {
        int width = this.contentWidth();
        int left = (this.width - width) / 2;
        int tabWidth = (width - 8) / 3;
        Section[] sections = Section.values();
        for (int i = 0; i < sections.length; i++) {
            Section tab = sections[i];
            String label = switch (tab) {
                case CHANNELS -> "Channels";
                case FRIENDS -> "Friends" + (Friends.requests().isEmpty() ? "" : " (" + Friends.requests().size() + ")");
                case OPTIONS -> "Options";
            };
            Component text = tab == this.section
                ? Component.literal(label).withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE)
                : Component.literal(label);
            this.addRenderableWidget(Button.builder(text, b -> this.show(tab)).bounds(left + i * (tabWidth + 4), TABS_Y, tabWidth, 20).build());
        }

        int top = TOP;
        if (this.section == Section.FRIENDS) {
            top = this.addFriendBox(left, width);
        }
        this.list = new RowList(this.minecraft, this.width, top, this.height - 50, width);
        switch (this.section) {
            case CHANNELS -> this.channels();
            case FRIENDS -> this.friends();
            case OPTIONS -> this.options();
        }
        this.addRenderableWidget(this.list);
        this.list.setScrollAmount(this.scroll);

        int buttonsY = this.height - 28;
        if (this.section == Section.CHANNELS) {
            int third = (width - 8) / 3;
            this.addRenderableWidget(Button.builder(Component.literal("New channel"), b -> this.minecraft.gui.setScreen(new NewChannelScreen(this)))
                .bounds(left, buttonsY, third, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Paste invite"), b -> this.pasteInvite())
                .tooltip(Tooltip.create(Component.literal("Join a passphrase channel from invite text someone sent you (copy it first)")))
                .bounds(left + third + 4, buttonsY, third, 20).build());
            this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
                .bounds(left + 2 * (third + 4), buttonsY, third, 20).build());
        } else {
            this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
                .bounds(this.width / 2 - 100, buttonsY, 200, 20).build());
        }
    }

    // Switches section (or redraws this one) keeping what was typed
    void show(Section next) {
        this.saveCommands();
        if (next != this.section) {
            this.scroll = 0;
            this.status = null;
        }
        this.section = next;
        this.rebuildWidgets();
    }

    private void refresh() {
        this.scroll = this.list == null ? 0 : this.list.scrollAmount();
        this.rebuildWidgets();
    }

    void say(String text, int color) {
        this.status = text;
        this.statusColor = color;
    }

    // Channels: the ones you have, invitations waiting for an answer
    private void channels() {
        RowList rows = this.list;
        List<Channel> channels = ChatSettings.all();
        Channel active = ChatSettings.active();
        String myKey = Friends.publicKey();
        if (channels.isEmpty() && FriendChannels.invites().isEmpty()) {
            rows.text(Component.literal("No channels yet. Make one with New channel: a passphrase you share with friends, or a channel of"
                + " friends you exchanged keys with. Or join one with Paste invite."), RowList.GRAY);
        }
        for (Channel channel : channels) {
            boolean inUse = active != null && active.name().equals(channel.name());
            String kind;
            if (channel.group() == null) {
                kind = channel.readyKey() == null ? "passphrase, preparing key..." : "passphrase";
            } else if (myKey != null && channel.group().ownedBy(myKey)) {
                kind = "friends, yours";
            } else {
                Friend owner = Friends.byKey(channel.group().ownerKey());
                kind = "friends, from " + (owner != null ? owner.name() : channel.group().ownerName());
            }
            Component text = Component.literal(channel.name()).withColor(channel.color())
                .append(Component.literal("  " + kind).withStyle(ChatFormatting.GRAY))
                .append(inUse ? Component.literal("  in use").withStyle(ChatFormatting.GREEN) : Component.empty());
            // Same as clicking the lock in the chat box. Kept in the list rather than a corner, where toasts would cover it.
            Button use = Button.builder(Component.literal(inUse ? "Turn off" : "Use"), b -> {
                ChatSettings.setActive(inUse ? null : channel);
                this.refresh();
            }).size(56, 20).build();
            Button edit = Button.builder(Component.literal("Edit"), b -> this.minecraft.gui.setScreen(new ChannelScreen(this, channel.name())))
                .size(44, 20).build();
            rows.row(text, RowList.WHITE, channel.color(), use, edit);
        }
        List<FriendChannels.PendingInvite> invites = FriendChannels.invites();
        if (!invites.isEmpty()) {
            rows.heading("Invitations");
            for (FriendChannels.PendingInvite invite : invites) {
                Button join = Button.builder(Component.literal("Join"), b -> {
                    String joined = FriendChannels.join(invite.handle());
                    this.say(joined == null ? "That invitation is gone." : "Joined " + joined + ".", joined == null ? RowList.RED : RowList.GREEN);
                    this.refresh();
                }).size(44, 20).build();
                Button ignore = Button.builder(Component.literal("Ignore"), b -> {
                    FriendChannels.ignore(invite.handle());
                    this.refresh();
                }).size(50, 20).build();
                rows.row(Component.literal(invite.ownerName() + " invites you to " + invite.name()), RowList.YELLOW, join, ignore);
            }
        }
    }

    // Join a passphrase channel from text like "... privacyplus-channel:<name>:<base64 passphrase>"
    private void pasteInvite() {
        Matcher matcher = INVITE.matcher(this.minecraft.keyboardHandler.getClipboard());
        byte[] passphrase = matcher.find() ? FriendCrypto.decode(matcher.group(2)) : null;
        if (passphrase == null || passphrase.length == 0) {
            this.say("No invite found. Copy the whole invite text first, then click Paste invite.", RowList.RED);
            this.refresh();
            return;
        }
        this.minecraft.gui.setScreen(new NewChannelScreen(this, matcher.group(1), new String(passphrase, java.nio.charset.StandardCharsets.UTF_8)));
    }

    // Invite text for a passphrase channel, to paste outside Minecraft
    static String inviteText(Channel channel) {
        return "Join my encrypted Minecraft chat channel \"" + channel.name() + "\". With PrivacyPlus: copy this whole message, right-click the"
            + " lock next to the chat box, then Paste invite. Anyone with this text can read the channel, so never post it in game chat. "
            + INVITE_TAG + channel.name() + ":" + FriendCrypto.encode(channel.passphrase().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // The add-a-friend controls above the list: a name and the channel to ask through, and friend codes for
    // players you share no channel with. Returns where the list starts.
    private int addFriendBox(int left, int width) {
        EditBox box = new EditBox(this.font, left, TOP - 2, width - 188, 20, Component.literal("Player name")) {
            @Override
            public boolean keyPressed(KeyEvent event) {
                if (this.isFocused() && event.input() == InputConstants.KEY_TAB) {
                    String completion = ChatSettingsScreen.this.completion(this.getValue());
                    if (completion != null) {
                        this.setValue(completion);
                        return true;
                    }
                }
                if (this.isFocused() && event.isConfirmation()) {
                    ChatSettingsScreen.this.sendRequest();
                    return true;
                }
                return super.keyPressed(event);
            }
        };
        box.setMaxLength(16);
        box.setHint(Component.literal("player name (Tab completes)"));
        box.setValue(this.friendName);
        box.setResponder(value -> {
            this.friendName = value;
            String completion = this.completion(value);
            box.setSuggestion(completion == null || value.isEmpty() ? null : completion.substring(value.length()));
        });
        this.addRenderableWidget(box);
        List<String> channels = new ArrayList<>();
        channels.add("");
        ChatSettings.all().stream().filter(c -> c.readyKey() != null).forEach(c -> channels.add(c.name()));
        if (!channels.contains(this.through)) {
            this.through = "";
        }
        CycleButton<String> through = cycle(channels, this.through, v -> v.isEmpty() ? "Through: auto" : "Through: " + v, 80, v -> this.through = v);
        through.setTooltip(Tooltip.create(Component.literal("The request travels encrypted with a channel you both have, so the server can't"
            + " read or recognize it. Auto: the channel where you last saw them write.")));
        through.setPosition(left + width - 184, TOP - 2);
        this.addRenderableWidget(through);
        this.addRenderableWidget(Button.builder(Component.literal("Send request"), b -> this.sendRequest())
            .tooltip(Tooltip.create(Component.literal("Sends them your public key by /msg. They need PrivacyPlus, the channel, and to be online.")))
            .bounds(left + width - 100, TOP - 2, 100, 20).build());

        int half = (width - 4) / 2;
        this.addRenderableWidget(Button.builder(Component.literal("Create friend code"), b -> this.createCode())
            .tooltip(Tooltip.create(Component.literal("For someone you share no channel with: a one-time code to send them outside Minecraft."
                + " Whoever uses it becomes your friend.")))
            .bounds(left, TOP + 22, half, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("I have a friend code"),
                b -> this.minecraft.gui.setScreen(new FriendCodeScreen(this)))
            .tooltip(Tooltip.create(Component.literal("Someone gave you a friend code: use it to exchange keys with them")))
            .bounds(left + half + 4, TOP + 22, half, 20).build());
        return TOP + 48;
    }

    private void createCode() {
        EncryptedChat.setSelfName(this.minecraft.getUser().getName());
        Friends.Outcome outcome = Friends.createCode();
        if (outcome.ok()) {
            this.minecraft.gui.setScreen(new FriendCodeScreen(this, outcome.message()));
        } else {
            this.say(outcome.message(), RowList.RED);
            this.refresh();
        }
    }

    // An online player whose name starts with what's typed
    private @Nullable String completion(String typed) {
        if (typed.isEmpty()) {
            return null;
        }
        return this.onlinePlayers().stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed.toLowerCase(Locale.ROOT)))
            .findFirst().orElse(null);
    }

    private List<String> onlinePlayers() {
        ClientPacketListener connection = this.minecraft.getConnection();
        if (connection == null) {
            return List.of();
        }
        String me = this.minecraft.getUser().getName();
        return connection.getOnlinePlayers().stream().map(p -> p.getProfile().name()).filter(name -> !name.equalsIgnoreCase(me))
            .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private void sendRequest() {
        this.request(this.friendName.trim());
    }

    private void request(String name) {
        EncryptedChat.setSelfName(this.minecraft.getUser().getName());
        Friends.Outcome outcome = Friends.requestExchange(name, this.through.isEmpty() ? null : ChatSettings.get(this.through));
        if (outcome.ok()) {
            this.friendName = "";
        }
        this.say(outcome.message(), outcome.ok() ? RowList.GREEN : RowList.RED);
        this.refresh();
    }

    // Friends: requests both ways, our friend codes, friends with their status, and players online to add
    private void friends() {
        RowList rows = this.list;
        List<Friends.Request> requests = Friends.requests();
        List<Friends.Outgoing> outgoing = Friends.outgoing();
        List<Friends.FriendCode> codes = Friends.codes();
        if (!requests.isEmpty() || !outgoing.isEmpty()) {
            rows.heading("Requests");
            for (Friends.Request request : requests) {
                Button accept = Button.builder(Component.literal("Accept"), b -> {
                    EncryptedChat.setSelfName(this.minecraft.getUser().getName());
                    Friends.accept(request.publicKey());
                    this.refresh();
                }).size(54, 20).build();
                Button ignore = Button.builder(Component.literal("Ignore"), b -> {
                    Friends.ignore(request.publicKey());
                    this.refresh();
                }).size(50, 20).build();
                rows.row(Component.literal(request.name() + " wants to exchange keys (through " + request.route() + ")"), RowList.YELLOW,
                    accept, ignore);
            }
            for (Friends.Outgoing request : outgoing) {
                Button cancel = Button.builder(Component.literal("Cancel"), b -> {
                    Friends.cancelRequest(request.name());
                    this.refresh();
                }).size(54, 20).build();
                boolean queued = Friends.pendingSends().contains(FriendMessages.HELLO + " to " + request.name());
                String text = (queued ? "Waiting for " + request.name() + " to be online" : "Waiting for " + request.name() + " to answer")
                    + " (through " + request.route() + ")";
                rows.row(Component.literal(text), RowList.GRAY, cancel);
            }
        }
        if (!codes.isEmpty()) {
            rows.heading("Your friend codes");
            long now = System.currentTimeMillis();
            for (Friends.FriendCode code : codes) {
                Button copy = Button.builder(Component.literal("Copy"), b -> {
                    this.minecraft.keyboardHandler.setClipboard(code.code());
                    this.say("Copied. Send it outside Minecraft; it works once.", RowList.GREEN);
                }).size(44, 20).build();
                Button cancel = Button.builder(Component.literal("Cancel"), b -> {
                    Friends.cancelCode(code.code());
                    this.refresh();
                }).size(50, 20).build();
                long hours = Math.max(1, (code.expiresAt() - now + 3_599_999) / 3_600_000);
                rows.row(Component.literal(code.code()).withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("  unused, " + hours + " h left").withStyle(ChatFormatting.GRAY)), RowList.WHITE, copy, cancel);
            }
        }

        rows.heading("Friends");
        List<Friend> friends = Friends.all();
        if (friends.isEmpty()) {
            rows.text(Component.literal("No friends yet. Friends get their own key: private messages to them are encrypted for them alone, and"
                + " you can make channels of friends. The first exchange needs a secret the server doesn't have: a channel you both"
                + " have (type their name above), or a friend code."), RowList.GRAY);
        }
        for (Friend friend : friends) {
            Component state = !friend.confirmed() ? Component.literal("  finishing the exchange...").withStyle(ChatFormatting.GRAY)
                : friend.verified() ? Component.literal("  verified").withStyle(ChatFormatting.GREEN)
                : Component.literal("  not verified").withStyle(ChatFormatting.GOLD);
            Button code = Button.builder(Component.literal("Safety code"),
                b -> this.minecraft.gui.setScreen(new FriendScreen(this, friend.publicKey()))).size(76, 20).build();
            Button remove = Button.builder(Component.literal("Remove"), b -> this.confirmRemove(friend)).size(54, 20).build();
            rows.row(Component.literal(friend.name()).append(state), RowList.WHITE, code, remove);
        }

        List<String> online = this.onlinePlayers().stream()
            .filter(name -> friends.stream().noneMatch(f -> f.name().equalsIgnoreCase(name)))
            .filter(name -> outgoing.stream().noneMatch(o -> o.name().equalsIgnoreCase(name)))
            .limit(40).toList();
        if (!online.isEmpty()) {
            rows.heading("Online now");
            for (String name : online) {
                Button add = Button.builder(Component.literal("Add"), b -> this.request(name)).size(44, 20).build();
                rows.row(Component.literal(name), RowList.WHITE, add);
            }
        }
    }

    void confirmRemove(Friend friend) {
        this.minecraft.gui.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                Friends.remove(friend.publicKey());
            }
            this.minecraft.gui.setScreen(this);
        }, Component.literal("Remove " + friend.name() + "?"), Component.literal("Your key with " + friend.name() + " is deleted, and they are taken"
            + " out of channels you run (the others get a new key). To message them encrypted again, exchange keys again."),
            Component.literal("Remove"), CommonComponents.GUI_CANCEL));
    }

    // Options: what the lock encrypts, how encrypted lines look, the private-message commands, this server
    private void options() {
        RowList rows = this.list;
        int wide = Math.min(200, this.contentWidth() / 2);
        rows.heading("The lock");
        rows.row(Component.literal("Encrypts"), RowList.WHITE, cycle(List.of(true, false), ChatSettings.encryptPublicChat(),
            v -> v ? "Chat and private messages" : "Private messages only", wide, ChatSettings::setEncryptPublicChat));
        rows.row(Component.literal("Private messages to friends"), RowList.WHITE, cycle(List.of(true, false), ChatSettings.encryptFriendMessages(),
            v -> v ? "Always encrypted" : "Only with the lock on", wide, ChatSettings::setEncryptFriendMessages));

        rows.heading("Encrypted lines");
        rows.row(Component.literal("Mark in front"), RowList.WHITE, cycle(List.of(LockStyle.values()), ChatSettings.lockStyle(),
            v -> switch (v) {
                case LOCK -> EncryptedChat.LOCK;
                case TEXT -> "[E]";
                case NAME -> "Channel name only";
            }, wide, ChatSettings::setLockStyle));
        rows.row(Component.literal("Channel name on each line"), RowList.WHITE, cycle(List.of(true, false), ChatSettings.showChannelName(),
            v -> v ? "Shown" : "Hidden", wide, ChatSettings::setShowChannelName));
        rows.row(Component.literal("Messages you can't read"), RowList.WHITE, cycle(List.of(Unreadable.values()), ChatSettings.unreadable(),
            v -> switch (v) {
                case SHOW -> "Shown as they are";
                case REPLACE -> "Shown as [encrypted]";
                case HIDE -> "Hidden";
            }, wide, ChatSettings::setUnreadable));
        List<Integer> minutes = new ArrayList<>(ChatSettings.OLD_AFTER_CHOICES);
        if (!minutes.contains(ChatSettings.oldAfterMinutes())) {
            minutes.add(ChatSettings.oldAfterMinutes());
            minutes.sort(null);
        }
        rows.row(Component.literal("Mark messages as [old] after"), RowList.WHITE, cycle(minutes, ChatSettings.oldAfterMinutes(),
            v -> v + " minutes", wide, ChatSettings::setOldAfterMinutes));
        rows.text(Component.literal("An old message may be one the server recorded and is showing again."), RowList.GRAY);

        rows.heading("Private-message commands");
        rows.text(Component.literal("Encrypted while the lock is on. The first words stay readable, like the name in /msg Steve hi. A command"
            + " with one readable word is a private message to that player: to a friend, it's encrypted with your key with them."), RowList.GRAY);
        if (this.commandDraft == null) {
            this.commandDraft = new ArrayList<>(ChatSettings.commandRules());
        }
        List<CommandRule> draft = this.commandDraft;
        for (int i = 0; i < draft.size(); i++) {
            int index = i;
            EditBox name = new EditBox(this.font, 0, 0, 90, 18, Component.literal("Command"));
            name.setMaxLength(32);
            name.setValue(draft.get(i).name());
            name.setResponder(value -> draft.set(index, new CommandRule(value, draft.get(index).readableWords())));
            List<Integer> counts = List.of(0, 1, 2, 3, 4, 5);
            CycleButton<Integer> readable = cycle(counts, draft.get(i).readableWords(), v -> v == 1 ? "1 readable word" : v + " readable words", 110,
                v -> draft.set(index, new CommandRule(draft.get(index).name(), v)));
            Button remove = Button.builder(Component.literal("Remove"), b -> {
                draft.remove(index);
                this.saveCommands();
                this.refresh();
            }).size(54, 20).build();
            rows.row(Component.literal("/"), RowList.WHITE, name, readable, remove);
        }
        Button add = Button.builder(Component.literal("Add command"), b -> {
            draft.add(new CommandRule("", 1));
            this.refresh();
        }).size(96, 20).build();
        Button reset = Button.builder(Component.literal("Reset to defaults"), b -> {
            ChatSettings.setEncryptCommands(ChatSettings.DEFAULT_COMMANDS);
            this.commandDraft = null;
            this.refresh();
        }).size(110, 20).build();
        rows.row(Component.empty(), RowList.WHITE, add, reset);

        ServerData server = this.currentServer();
        if (server != null) {
            String host = ServerAddress.parseString(server.ip).getHost();
            rows.heading("This server: " + host);
            List<String> choices = new ArrayList<>();
            choices.add("");
            ChatSettings.all().forEach(c -> choices.add(c.name()));
            Channel auto = ChatSettings.autoChannel(server.ip);
            rows.row(Component.literal("Turn the lock on when joining"), RowList.WHITE, cycle(choices, auto == null ? "" : auto.name(),
                v -> v.isEmpty() ? "No" : "With " + v, wide, v -> ChatSettings.setAutoChannel(server.ip, v.isEmpty() ? null : v)));
            rows.row(Component.literal("Your chat here"), RowList.WHITE, cycle(List.of(false, true), ChatSettings.isUnsigned(server.ip),
                v -> v ? "Unsigned: can't be reported" : "Signed, as normal", wide, v -> ChatSettings.setUnsigned(server.ip, v)));
            rows.text(Component.literal("Unsigned chat can't be reported to Mojang. Applies next time you join; the server can tell, and some"
                + " refuse unsigned chat."), RowList.GRAY);
        }
    }

    private void saveCommands() {
        if (this.commandDraft != null) {
            ChatSettings.setCommandRules(this.commandDraft);
            this.commandDraft = null;
        }
    }

    static <T> CycleButton<T> cycle(List<T> values, T current, java.util.function.Function<T, String> label, int width, Consumer<T> onChange) {
        return CycleButton.<T>builder(v -> Component.literal(label.apply(v)), current)
            .withValues(values)
            .displayOnlyValue()
            .create(0, 0, width, 20, Component.literal(label.apply(current)), (button, value) -> onChange.accept(value));
    }

    private @Nullable ServerData currentServer() {
        ClientPacketListener connection = this.minecraft.getConnection();
        return connection == null || this.minecraft.isLocalServer() ? null : connection.getServerData();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title.getString(), this.width / 2, 14, RowList.WHITE);
        if (this.status != null) {
            int width = this.contentWidth();
            String text = this.font.width(this.status) > width ? this.font.plainSubstrByWidth(this.status, width - 6) + "..." : this.status;
            graphics.centeredText(this.font, text, this.width / 2, this.height - 42, this.statusColor);
        }
    }

    @Override
    public void onClose() {
        this.saveCommands();
        this.minecraft.gui.setScreen(this.parent);
    }
}
