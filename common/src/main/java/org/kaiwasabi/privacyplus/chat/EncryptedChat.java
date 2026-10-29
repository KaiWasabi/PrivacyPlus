package org.kaiwasabi.privacyplus.chat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import javax.crypto.SecretKey;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.kaiwasabi.privacyplus.PrivacyPlus;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Channel;
import org.kaiwasabi.privacyplus.dns.PrivateDns;
import org.kaiwasabi.privacyplus.gui.PrivacyPlusScreen;
import org.kaiwasabi.privacyplus.stealth.ProbeAlarm;
import org.jspecify.annotations.Nullable;

// End-to-end encrypted chat over ordinary chat lines and commands like /msg, in the style of No Chat
// Reports' encryption: a lock next to the chat box turns it on and off. The server and players without
// the key see random-looking text with no marker; anyone with the channel's key sees the message.
// Private messages to a friend use the keys shared with that friend instead (see Friends).
// Who wrote a decrypted message comes from the key (a friend's), or from the sender check in the encrypted
// text matched against the message's real sender (the player chat packet's) and otherwise the names on the
// line; whenever the line's own attribution isn't confirmed, the writer is shown on the line. Each token is
// shown as new only once (by its nonce): repeats are marked [replayed] and never act again.
// /pp commands are handled locally and never sent.
public final class EncryptedChat {
    private static final String COMMAND = "/pp";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    public static final String LOCK = "🔒";
    private static final String SETTINGS_CLICK = "PrivacyPlus: encrypted chat settings";
    private static final ClickEvent OPEN_SETTINGS = new ClickEvent.CopyToClipboard(SETTINGS_CLICK);
    // Candidate player names on a chat line
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern PLAYER = Pattern.compile("[A-Za-z0-9_]{1,16}");
    // How much of one line is tried at all, so a server can't make the render thread decrypt without end
    private static final int MAX_LINE_CHARS = 4096;
    private static final int MAX_TOKEN_CHARS = 400;
    private static final int MAX_TOKENS_PER_LINE = 6;
    // Seen nonces are kept this long past the "old" threshold, for clock differences
    private static final Duration NONCE_MARGIN = Duration.ofMinutes(2);
    private static final int MAX_NONCES = 20_000;
    // Pause between the parts of a long message, like someone typing them
    private static final long PART_GAP_MIN_MILLIS = 1200;
    private static final long PART_GAP_MAX_MILLIS = 2500;

    // Encrypted text starting with this is a PrivacyPlus control message (a key exchange, shared
    // coordinates...): "<CONTROL><verb> <body>". Its handler decides what the line shows instead.
    public static final char CONTROL = '\u0001';

    // A local command such as /pp or /coords: handled on this computer and never sent to the server
    public interface LocalCommand {
        void run(Minecraft mc, String args);

        // Completions for the last word; words are the arguments typed so far (the last one possibly empty)
        default List<String> complete(String[] words) {
            return List.of();
        }
    }

    // A decrypted control message. keyLabel names the key that opened it (a channel or a friend); channel is
    // set when that key is a channel's, extraKey when it is a registered extra key (a friend's, say).
    // writer is who wrote it: the friend whose key opened it, or the name (the message's sender, or else one on
    // the line) that matches the encrypted sender check. mine: this client sent it (the server echoing it
    // back). oldKey: opened with an older key of a friend channel.
    public record Control(String verb, String body, String keyLabel, @Nullable Channel channel, @Nullable String writer,
                          boolean stale, Instant sentAt, @Nullable ExtraKey extraKey, boolean mine, boolean oldKey) {
        public Control(String verb, String body, String keyLabel, @Nullable Channel channel, @Nullable String writer,
                       boolean stale, Instant sentAt) {
            this(verb, body, keyLabel, channel, writer, stale, sentAt, null, false, false);
        }

        // The body may carry keys (channel invitations): never printed
        @Override
        public String toString() {
            return "Control[" + this.verb + " from " + this.writer + "]";
        }
    }

    public interface ControlHandler {
        // What the line shows in place of the encrypted text. Called once per distinct message, so it may
        // have effects (store a key, ...); a server replaying the same text doesn't run it again.
        Component handle(Control control, Style style);
    }

    // A key to try on incoming encrypted text besides the channels', such as a friend's. owner is whatever
    // the source wants back in Control.extraKey() (Friends puts the friend there).
    public record ExtraKey(String label, SecretKey key, @Nullable Object owner) {
        public ExtraKey(String label, SecretKey key) {
            this(label, key, null);
        }

        @Override
        public String toString() {
            return "ExtraKey[" + this.label + "]";
        }
    }

    // Clickable chat text that runs something on this computer (Accept, Copy, Track...): a CopyToClipboard
    // event whose value is this prefix plus a random id, intercepted by ChatScreenMixin before vanilla
    // handles the click. A server can't guess the ids, and nothing about a click is ever sent.
    private static final String LOCAL_CLICK = "PrivacyPlus local action ";
    private static final Map<String, Runnable> LOCAL_ACTIONS = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Runnable> eldest) {
            return this.size() > 1024;
        }
    };
    private static final Map<String, LocalCommand> COMMANDS = new ConcurrentHashMap<>();
    private static final Map<String, ControlHandler> CONTROLS = new ConcurrentHashMap<>();
    private static final List<Supplier<List<ExtraKey>>> KEY_SOURCES = new CopyOnWriteArrayList<>();
    // What each control message showed, by nonce: a repeat shows it again, marked, without running its handler
    private static final Map<String, Component> HANDLED = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Component> eldest) {
            return this.size() > 2048;
        }
    };
    // Nonces of tokens shown, with when they may be forgotten (when a repeat would be marked [old] anyway)
    private static final LinkedHashMap<String, Long> SEEN = new LinkedHashMap<>();
    // Nonces of tokens this client sent, with the name it sent them as. When the server echoes one back (public
    // chat, or "You whisper to Bob: ..." where our name isn't on the line) we know who wrote it.
    private static final Map<String, String> SENT = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return this.size() > 1024;
        }
    };
    // The last part of a multi-part message shown per key and writer, to notice parts that don't belong together
    private static final Map<String, int[]> LAST_PART = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, int[]> eldest) {
            return this.size() > 256;
        }
    };
    // The last few lines worked out: the chat shows each line once but asks three times (hide?, display,
    // history), and the second and third asks mustn't count as replays
    private static final Processed[] RECENT = new Processed[4];
    private static int recentIndex;
    private static final Deque<PendingPart> PARTS = new ArrayDeque<>();
    private static final Component UNREADABLE = Component.literal("[encrypted]").setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)
        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Encrypted text you don't have the key for"))));
    // The player's own name, remembered whenever the game hands it over (it never changes mid-session)
    private static volatile @Nullable String selfName;
    private static volatile @Nullable String joinedServer;
    // Who sent the player chat message being shown right now (set by ChatListenerMixin), null for others
    private static volatile @Nullable String packetSender;

    // label is the channel's name or the extra key's label; channel is null for extra keys. attributed: the
    // message's real sender is the writer. sender: that real sender, if the message had one.
    private record Decrypted(String label, @Nullable Channel channel, @Nullable ExtraKey extraKey, boolean oldKey, ChatCrypto.Message message,
                             @Nullable String writer, boolean mine, boolean stale, boolean attributed, @Nullable String sender, boolean replayed) {
        Decrypted replayed(boolean value) {
            return new Decrypted(this.label, this.channel, this.extraKey, this.oldKey, this.message, this.writer, this.mine, this.stale,
                this.attributed, this.sender, value);
        }
    }

    private record Processed(Component source, Component shown, boolean hide) {
    }

    private record PendingPart(String commandPrefix, String token, long sendAt, ClientPacketListener connection) {
    }

    // Where a line typed into the chat box goes: commandPrefix "" is public chat. Exactly one of channel
    // and friend is set.
    record Route(String commandPrefix, String text, @Nullable Channel channel, Friends.@Nullable Friend friend) {
    }

    private EncryptedChat() {
    }

    // A click event for chat text that runs action on the render thread when clicked
    public static ClickEvent localClick(Runnable action) {
        String id = UUID.randomUUID().toString();
        synchronized (LOCAL_ACTIONS) {
            LOCAL_ACTIONS.put(id, action);
        }
        return new ClickEvent.CopyToClipboard(LOCAL_CLICK + id);
    }

    // Runs the local action behind a clicked style. True if the click was a local one (handled, or
    // too old to run any more), so vanilla must not handle it.
    public static boolean runLocalClick(Minecraft mc, Style style) {
        if (!(style.getClickEvent() instanceof ClickEvent.CopyToClipboard copy) || !copy.value().startsWith(LOCAL_CLICK)) {
            return false;
        }
        Runnable action;
        synchronized (LOCAL_ACTIONS) {
            action = LOCAL_ACTIONS.get(copy.value().substring(LOCAL_CLICK.length()));
        }
        if (action != null) {
            action.run();
        } else {
            feedback(mc, "That button is too old to use any more.");
        }
        return true;
    }

    // name without the slash, e.g. "coords"
    public static void registerCommand(String name, LocalCommand command) {
        COMMANDS.put(name.toLowerCase(Locale.ROOT), command);
    }

    public static void registerControl(String verb, ControlHandler handler) {
        CONTROLS.put(verb, handler);
    }

    public static void registerKeySource(Supplier<List<ExtraKey>> source) {
        KEY_SOURCES.add(source);
    }

    // The player's name, once the game has been seen running (null in tests that don't set it)
    static @Nullable String selfName() {
        return selfName;
    }

    static void setSelfName(@Nullable String name) {
        selfName = name;
    }

    // ChatListenerMixin: the sender of the player chat message about to be shown, then null again
    public static void setPacketSender(@Nullable String name) {
        packetSender = name;
    }

    // The registered local command this input runs, if any
    private static @Nullable String localCommandName(String input) {
        if (!input.startsWith("/")) {
            return null;
        }
        int space = input.indexOf(' ');
        String name = input.substring(1, space == -1 ? input.length() : space).toLowerCase(Locale.ROOT);
        return COMMANDS.containsKey(name) ? name : null;
    }

    // Called with the normalized chat box input. Returns true when PrivacyPlus took care of it,
    // in which case vanilla must neither send it nor add it to the history.
    public static boolean handleInput(Minecraft mc, String input, boolean addToRecent) {
        selfName = mc.getUser().getName();
        if (input.equals(COMMAND) || input.startsWith(COMMAND + " ")) {
            runCommand(mc, input.substring(COMMAND.length()).trim());
            return true;
        }
        String local = localCommandName(input);
        if (local != null) {
            COMMANDS.get(local).run(mc, input.substring(1 + local.length()).trim());
            return true;
        }
        Route route = route(input);
        if (route == null) {
            String refusal = plainTextRefusal(input);
            if (refusal != null) {
                // Never plain text to a name that is, or was, a friend's
                feedback(mc, Component.literal(refusal).withStyle(ChatFormatting.RED));
                return true;
            }
            return false;
        }
        if (addToRecent && !input.startsWith("/")) {
            // Plain chat (not a command), so this stays in memory and is never written to disk. Commands are
            // deliberately not added: they would be saved to command_history.txt.
            mc.gui.hud.getChat().addRecentChat(input);
        }
        if (route.friend() != null) {
            SecretKey key = Friends.keyOf(route.friend());
            if (key == null) {
                feedback(mc, "Your key with " + route.friend().name() + " isn't available; nothing was sent.");
            } else {
                sendEncrypted(mc, key, route.commandPrefix(), route.text());
            }
        } else {
            send(mc, route.channel(), route.commandPrefix(), route.text());
        }
        return true;
    }

    // How input would be sent: encrypted to the active channel, encrypted to a friend (a private message to
    // a friend, with the lock on or "always encrypt" set), or null for vanilla to send as it is
    static @Nullable Route route(String input) {
        Channel channel = ChatSettings.active();
        if (input.startsWith("/")) {
            Optional<String[]> split = splitCommand(input.substring(1), ChatSettings.encryptCommands());
            if (split.isEmpty()) {
                return null;
            }
            if (channel != null || ChatSettings.encryptFriendMessages()) {
                String recipient = recipient(split.get()[0], ChatSettings.encryptCommands());
                Friends.Friend friend = recipient == null ? null : Friends.forRecipient(recipient);
                if (friend != null) {
                    return new Route(split.get()[0], split.get()[1], null, friend);
                }
            }
            return channel == null ? null : new Route(split.get()[0], split.get()[1], channel, null);
        }
        return channel != null && ChatSettings.encryptPublicChat() ? new Route("", input, channel, null) : null;
    }

    // Why input, which wouldn't be encrypted, mustn't go out at all: a private message to the name a friend had,
    // or to a friend whose key exchange isn't finished. Null if it may go out as it is.
    static @Nullable String plainTextRefusal(String input) {
        if (!input.startsWith("/") || (ChatSettings.active() == null && !ChatSettings.encryptFriendMessages())) {
            return null;
        }
        Optional<String[]> split = splitCommand(input.substring(1), ChatSettings.encryptCommands());
        String recipient = split.isEmpty() ? null : recipient(split.get()[0], ChatSettings.encryptCommands());
        return recipient == null ? null : Friends.plainTextRefusal(recipient);
    }

    // The player a private message goes to: the one readable word of a command like "msg Steve ", or null
    static @Nullable String recipient(String commandPrefix, Map<String, Integer> rules) {
        String[] words = commandPrefix.trim().split(" ");
        String name = words[0].toLowerCase(Locale.ROOT);
        Integer readable = rules.get(name.substring(name.indexOf(':') + 1));
        return readable != null && readable == 1 && words.length == 2 && PLAYER.matcher(words[1]).matches() ? words[1] : null;
    }

    // Splits "msg Steve hi there" into the part that stays readable ("msg Steve ") and the message
    // ("hi there"), following rules like msg:1. Empty if the command isn't listed or has no message.
    static Optional<String[]> splitCommand(String command, Map<String, Integer> rules) {
        int end = command.indexOf(' ');
        if (end == -1) {
            return Optional.empty();
        }
        String name = command.substring(0, end).toLowerCase(Locale.ROOT);
        Integer readableArgs = rules.get(name.substring(name.indexOf(':') + 1));
        if (readableArgs == null) {
            return Optional.empty();
        }
        for (int i = 0; i < readableArgs; i++) {
            end = command.indexOf(' ', end + 1);
            if (end == -1) {
                return Optional.empty();
            }
        }
        String message = command.substring(end + 1);
        return message.isBlank() ? Optional.empty() : Optional.of(new String[]{command.substring(0, end + 1), message});
    }

    // Whether sending this input (with or without its leading "/") would encrypt part of it
    public static boolean wouldEncrypt(String input) {
        if (ChatSettings.active() == null && !ChatSettings.encryptFriendMessages()) {
            return false;
        }
        String command = input.startsWith("/") ? input.substring(1) : input;
        return route("/" + command) != null;
    }

    // Completions for /pp while it's typed, or null if the input isn't /pp
    public static @Nullable CompletableFuture<Suggestions> suggestLocalCommand(String input, int cursor) {
        String local = localCommandName(input);
        if (local != null) {
            String typed = input.substring(0, Math.min(cursor, input.length()));
            int start = typed.lastIndexOf(' ') + 1;
            if (start == 0) {
                return Suggestions.empty();
            }
            String[] words = typed.substring(local.length() + 2).split(" ", -1);
            return SharedSuggestionProvider.suggest(COMMANDS.get(local).complete(words), new SuggestionsBuilder(typed, start));
        }
        if (!input.equals(COMMAND) && !input.startsWith(COMMAND + " ")) {
            return null;
        }
        String typed = input.substring(0, Math.min(cursor, input.length()));
        int start = typed.lastIndexOf(' ') + 1;
        if (start == 0) {
            return Suggestions.empty();
        }
        String[] words = typed.substring(COMMAND.length() + 1).split(" ", -1);
        List<String> options = new ArrayList<>();
        if (words.length == 1) {
            options.addAll(List.of("off", "status", "settings", "probes", "incognito", "dns", "reload"));
            ChatSettings.all().forEach(channel -> options.add(channel.name()));
        } else if (words.length == 2 && words[0].equals("incognito")) {
            options.addAll(List.of("on", "off"));
        } else if (words.length == 2 && words[0].equals("dns")) {
            options.add("off");
            PrivateDns.BUILT_IN.forEach(provider -> options.add(provider.setting()));
        }
        return SharedSuggestionProvider.suggest(options, new SuggestionsBuilder(typed, start));
    }

    // Left-click on the lock: off -> on with the channel used last; on -> off
    public static void toggleLock(Minecraft mc, @Nullable Screen parent) {
        if (ChatSettings.active() != null) {
            ChatSettings.setActive(null);
            return;
        }
        Channel channel = ChatSettings.lastUsed();
        if (channel == null) {
            mc.gui.setScreen(new ChatSettingsScreen(parent));
        } else {
            ChatSettings.setActive(channel);
        }
    }

    // Shift-click on the lock: on with the next channel in the list (the first one if it was off)
    public static void cycleLock(Minecraft mc, @Nullable Screen parent) {
        Channel next = ChatSettings.next(ChatSettings.active());
        if (next == null) {
            mc.gui.setScreen(new ChatSettingsScreen(parent));
        } else {
            ChatSettings.setActive(next);
        }
    }

    // Shown above the chat box while the lock is on, or while typing a private message that goes to a friend
    // encrypted. In red (a legacy color code, which plain-string text drawing honors) when what's typed would
    // go out as plain text although the lock is on.
    public static @Nullable String lockLabel() {
        return lockLabel(currentInput());
    }

    static @Nullable String lockLabel(String input) {
        Route route = route(input);
        if (route != null && route.friend() != null) {
            return "Encrypting to " + route.friend().name() + " with your friend key" + (route.friend().verified() ? "" : " (not verified)");
        }
        String refusal = route == null ? plainTextRefusal(input) : null;
        if (refusal != null) {
            return "§cWon't be sent: " + refusal.substring(0, refusal.indexOf(';') == -1 ? refusal.length() : refusal.indexOf(';'));
        }
        Channel channel = ChatSettings.active();
        if (channel == null) {
            return null;
        }
        if (route == null && !input.isBlank() && localCommandName(input) == null && !input.equals(COMMAND) && !input.startsWith(COMMAND + " ")) {
            if (!input.startsWith("/")) {
                return "§cNot encrypted: the lock only encrypts private messages (see Options)";
            }
            int space = input.indexOf(' ');
            String name = input.substring(1, space == -1 ? input.length() : space).toLowerCase(Locale.ROOT);
            if (!ChatSettings.encryptCommands().containsKey(name.substring(name.indexOf(':') + 1))) {
                return "§cNot encrypted: /" + name + " isn't one of your private-message commands";
            }
        }
        return (ChatSettings.encryptPublicChat() ? "Encrypting to " : "Encrypting commands to ") + channel.name();
    }

    // What is typed in the open chat box, "" if none
    private static String currentInput() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null || !(mc.gui.screen() instanceof ChatScreen chat)) {
            return "";
        }
        return chat.children().stream().filter(EditBox.class::isInstance).map(c -> ((EditBox) c).getValue()).findFirst().orElse("");
    }

    // Every quarter second on the render thread (see Friends): sends the later parts of long messages, and turns
    // the lock on when joining a server that has a channel set for it
    static void tick(Minecraft mc) {
        selfName = mc.getUser().getName();
        ClientPacketListener connection = mc.getConnection();
        sendDueParts(mc, connection);
        ServerData server = connection == null || mc.isLocalServer() ? null : connection.getServerData();
        String address = server == null || mc.player == null ? null : server.ip;
        if (address == null || address.equals(joinedServer)) {
            if (connection == null) {
                joinedServer = null;
            }
            return;
        }
        joinedServer = address;
        Channel channel = ChatSettings.autoChannel(address);
        Channel active = ChatSettings.active();
        if (channel != null && (active == null || !active.name().equals(channel.name()))) {
            ChatSettings.setActive(channel);
            feedback(mc, "Encrypting your messages to " + channel.name() + " on this server. Click the lock to stop.");
        }
    }

    private static void sendDueParts(Minecraft mc, @Nullable ClientPacketListener connection) {
        long now = System.currentTimeMillis();
        synchronized (PARTS) {
            while (!PARTS.isEmpty() && PARTS.peekFirst().sendAt() <= now) {
                PendingPart part = PARTS.pollFirst();
                // Parts for a connection that has closed are dropped, never sent somewhere else
                if (connection != null && connection == part.connection() && mc.player != null) {
                    sendToken(mc, part.commandPrefix(), part.token());
                }
            }
        }
    }

    private static void runCommand(Minecraft mc, String args) {
        if (args.equals("dns") || args.startsWith("dns ")) {
            privateDns(mc, args.substring(3).trim());
            return;
        }
        switch (args) {
            // The chat screen closes right after this runs, so open the screen once it has
            case "" -> mc.execute(() -> PrivacyPlusScreen.open(mc, null));
            case "status" -> status(mc);
            case "off" -> {
                ChatSettings.setActive(null);
                feedback(mc, "Encrypted chat off. Messages are sent as plain text.");
            }
            case "reload" -> {
                int count = ChatSettings.reload();
                Friends.reload();
                feedback(mc, "Reloaded " + count + " channel(s) and " + Friends.all().size() + " friend(s).");
            }
            case "probes" -> {
                List<ProbeAlarm.Event> events = ProbeAlarm.events();
                if (events.isEmpty()) {
                    feedback(mc, "No probes noticed this session.");
                }
                events.stream().skip(Math.max(0, events.size() - 20)).forEach(event -> feedback(mc,
                    TIME.format(event.at()) + " " + event.server() + " " + event.kind().description + ": " + event.detail()));
            }
            case "incognito", "incognito on", "incognito off" -> {
                PrivacyPlusConfig.setIncognito(args.equals("incognito") ? !PrivacyPlusConfig.incognito : args.endsWith("on"));
                feedback(mc, PrivacyPlusConfig.incognito
                    ? "Incognito on: chat, commands and the servers you join are no longer saved to disk."
                    : "Incognito off: chat logs and command history are saved again.");
            }
            // The chat screen closes right after this runs, so open the settings once it has
            case "settings" -> mc.execute(() -> mc.gui.setScreen(new ChatSettingsScreen(null)));
            default -> {
                int space = args.indexOf(' ');
                String name = space == -1 ? args : args.substring(0, space);
                Channel channel = ChatSettings.get(name);
                if (channel == null) {
                    feedback(mc, "No channel named \"" + name + "\". Right-click the lock next to the chat box to add one.");
                } else if (space == -1) {
                    ChatSettings.setActive(channel);
                    feedback(mc, "Encrypting your messages to " + name + ". /pp off to stop.");
                } else {
                    send(mc, channel, "", args.substring(space + 1).trim());
                }
            }
        }
    }

    // /pp dns shows the setting; /pp dns off, cloudflare, quad9 or an https:// address changes it
    private static void privateDns(Minecraft mc, String setting) {
        if (!setting.isEmpty()) {
            try {
                PrivacyPlusConfig.setPrivateDns(setting);
            } catch (IllegalArgumentException e) {
                feedback(mc, e.getMessage() + ". Use /pp dns off, cloudflare, quad9 or the https:// address of a DNS-over-HTTPS service.");
                return;
            }
        }
        feedback(mc, PrivateDns.describe());
    }

    private static void status(Minecraft mc) {
        if (ChatSettings.all().isEmpty()) {
            feedback(mc, "No channels yet. Right-click the lock next to the chat box to add one.");
            return;
        }
        String label = lockLabel();
        feedback(mc, label == null ? "Encrypted chat is off." : label + ".");
        ChatSettings.all().forEach(c -> feedback(mc, " - " + c.name() + (c.group() != null ? " (friends)" : "")
            + (c.readyKey() == null ? " (key still being prepared)" : "")));
        feedback(mc, "/pp <channel> to turn on, /pp <channel> <message> to send one, /pp off, /pp settings");
    }

    // commandPrefix is "" for public chat, otherwise the readable start of a command such as "msg Steve "
    private static void send(Minecraft mc, Channel channel, String commandPrefix, String text) {
        SecretKey key = channel.readyKey();
        if (key == null) {
            // Never fall back to plain text
            feedback(mc, "The key for " + channel.name() + " is still being prepared; nothing was sent. Try again in a moment.");
            return;
        }
        sendEncrypted(mc, key, commandPrefix, text);
    }

    // Sends a control message (see CONTROL) encrypted with key. False if nothing was sent.
    public static boolean sendControl(Minecraft mc, SecretKey key, String commandPrefix, String verb, String body) {
        return sendEncrypted(mc, key, commandPrefix, CONTROL + verb + (body.isEmpty() ? "" : " " + body));
    }

    // Encrypts text with key and sends it as public chat (commandPrefix "") or as the end of a command
    // such as "msg Steve ". Long text goes out in several parts, a second or two apart. False if nothing was sent.
    public static boolean sendEncrypted(Minecraft mc, SecretKey key, String commandPrefix, String text) {
        if (text.isEmpty() || mc.player == null) {
            return false;
        }
        String me = mc.getUser().getName();
        selfName = me;
        // Commands are typed with a leading "/" that counts toward the chat box limit
        int limit = SharedConstants.MAX_CHAT_LENGTH - (commandPrefix.isEmpty() ? 0 : commandPrefix.length() + 1);
        List<String> tokens;
        try {
            tokens = ChatCrypto.encrypt(key, me, text, limit, Instant.now());
        } catch (IllegalArgumentException e) {
            feedback(mc, "That command leaves too little room to encrypt the message; nothing was sent.");
            return false;
        }
        for (String token : tokens) {
            synchronized (SENT) {
                SENT.put(ChatCrypto.nonceOf(token), me);
            }
        }
        sendToken(mc, commandPrefix, tokens.getFirst());
        long at = System.currentTimeMillis();
        synchronized (PARTS) {
            for (String token : tokens.subList(1, tokens.size())) {
                at += ThreadLocalRandom.current().nextLong(PART_GAP_MIN_MILLIS, PART_GAP_MAX_MILLIS + 1);
                PARTS.addLast(new PendingPart(commandPrefix, token, at, mc.player.connection));
            }
        }
        return true;
    }

    private static void sendToken(Minecraft mc, String commandPrefix, String token) {
        if (commandPrefix.isEmpty()) {
            mc.player.connection.sendChat(token);
        } else {
            mc.player.connection.sendCommand(commandPrefix + token);
        }
        Friends.delayAutomaticSends();
    }

    // Replaces tokens that decrypt with one of our keys and puts a lock mark in front of the line; tokens
    // nobody here can read become "[encrypted]" if the player chose so. Anything else is returned unchanged.
    // The sender of the player chat message being shown, if any, is the one ChatListenerMixin set.
    public static Component decryptForDisplay(Component message) {
        return process(message, packetSender).shown();
    }

    // For a message whose real sender (from a player chat packet) is known, or null
    static Component decryptForDisplay(Component message, @Nullable String sender) {
        return process(message, sender).shown();
    }

    // "Encrypted messages you can't read: hide". True for a line with encrypted text and nothing we can read.
    public static boolean hideLine(Component message) {
        return process(message, packetSender).hide();
    }

    // Works a line out once, however many times the chat asks about the same line
    private static Processed process(Component message, @Nullable String sender) {
        synchronized (RECENT) {
            for (Processed recent : RECENT) {
                if (recent != null && recent.source() == message) {
                    return recent;
                }
            }
        }
        Processed processed = compute(message, sender);
        synchronized (RECENT) {
            RECENT[recentIndex] = processed;
            recentIndex = (recentIndex + 1) % RECENT.length;
        }
        return processed;
    }

    private static Processed compute(Component message, @Nullable String sender) {
        ChatSettings.Unreadable unreadable = ChatSettings.unreadable();
        boolean replace = unreadable == ChatSettings.Unreadable.REPLACE;
        Processed unchanged = new Processed(message, message, false);
        if (unreadable == ChatSettings.Unreadable.SHOW && !haveKeys()) {
            return unchanged;
        }
        String line = message.getString();
        if (line.length() > MAX_LINE_CHARS || !ChatCrypto.TOKEN.matcher(line).find()) {
            return unchanged;
        }
        List<String> names = namesOn(line);

        MutableComponent rebuilt = Component.empty();
        List<Decrypted> found = new ArrayList<>();
        int[] tokens = {0};
        boolean[] changed = {false};
        message.visit((style, text) -> {
            Matcher matcher = ChatCrypto.TOKEN.matcher(text);
            int last = 0;
            while (matcher.find()) {
                if (matcher.group().length() > MAX_TOKEN_CHARS || ++tokens[0] > MAX_TOKENS_PER_LINE) {
                    continue;
                }
                Optional<Decrypted> decrypted = tryDecrypt(matcher.group(), names, sender).map(EncryptedChat::firstSight);
                if (decrypted.isEmpty() && !replace) {
                    continue;
                }
                if (matcher.start() > last) {
                    rebuilt.append(Component.literal(text.substring(last, matcher.start())).setStyle(style));
                }
                if (decrypted.isPresent()) {
                    rebuilt.append(render(decrypted.get(), style));
                    found.add(decrypted.get());
                } else {
                    rebuilt.append(UNREADABLE);
                }
                changed[0] = true;
                last = matcher.end();
            }
            if (last < text.length()) {
                rebuilt.append(Component.literal(text.substring(last)).setStyle(style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        boolean hide = unreadable == ChatSettings.Unreadable.HIDE && tokens[0] > 0 && found.isEmpty();
        if (!changed[0]) {
            return new Processed(message, message, hide);
        }
        Component shown = found.isEmpty() ? rebuilt : Component.empty().append(lockMark(found.getFirst())).append(rebuilt);
        return new Processed(message, shown, hide);
    }

    // Records the token's nonce. The first sighting of a message may act (note who wrote in a channel, re-send a
    // missed key); a repeat is a replay, unless it's our own message (whispering yourself shows it twice).
    private static Decrypted firstSight(Decrypted decrypted) {
        ChatCrypto.Message message = decrypted.message();
        boolean first = remember(message.nonce(), message.sentAt());
        if (first && !decrypted.stale() && !decrypted.mine() && decrypted.writer() != null && decrypted.channel() != null) {
            if (decrypted.oldKey()) {
                FriendChannels.oldKeyUsed(decrypted.channel(), decrypted.writer());
            } else if (message.senderVerifiable()) {
                // Someone we share this channel with: a key exchange with them can go under its key
                Friends.sawInChannel(decrypted.writer(), decrypted.channel());
            }
        }
        return decrypted.replayed(!first && !decrypted.mine());
    }

    // False if the nonce was seen within the window already
    private static boolean remember(String nonce, Instant sentAt) {
        long now = System.currentTimeMillis();
        long keep = Math.max(now, sentAt.toEpochMilli()) + Duration.ofMinutes(ChatSettings.oldAfterMinutes()).plus(NONCE_MARGIN).toMillis();
        synchronized (SEEN) {
            for (Iterator<Map.Entry<String, Long>> it = SEEN.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Long> oldest = it.next();
                if (oldest.getValue() > now && SEEN.size() <= MAX_NONCES) {
                    break;
                }
                it.remove();
            }
            Long seen = SEEN.get(nonce);
            if (seen != null && seen > now) {
                return false;
            }
            SEEN.put(nonce, keep);
            return true;
        }
    }

    private static boolean haveKeys() {
        return !ChatSettings.all().isEmpty() || KEY_SOURCES.stream().anyMatch(source -> !source.get().isEmpty());
    }

    // Names that appear on the line (the encrypted text itself excluded), to check who wrote it
    private static List<String> namesOn(String line) {
        return NAME.matcher(ChatCrypto.TOKEN.matcher(line).replaceAll(" ")).results().map(MatchResult::group).toList();
    }

    private static Optional<Decrypted> tryDecrypt(String token, List<String> names, @Nullable String sender) {
        for (Channel channel : ChatSettings.all()) {
            SecretKey key = channel.readyKey();
            if (key != null) {
                Optional<Decrypted> decrypted = tryKey(token, names, sender, key, channel.name(), channel, null, false);
                if (decrypted.isPresent()) {
                    return decrypted;
                }
            }
            if (channel.group() != null) {
                for (ChatSettings.OldKey old : channel.group().oldKeys()) {
                    Optional<Decrypted> decrypted = tryKey(token, names, sender, old.key(), channel.name(), channel, null, true);
                    if (decrypted.isPresent()) {
                        return decrypted;
                    }
                }
            }
        }
        for (Supplier<List<ExtraKey>> source : KEY_SOURCES) {
            for (ExtraKey extra : source.get()) {
                Optional<Decrypted> decrypted = tryKey(token, names, sender, extra.key(), extra.label(), null, extra, false);
                if (decrypted.isPresent()) {
                    return decrypted;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Decrypted> tryKey(String token, List<String> names, @Nullable String sender, SecretKey key, String label,
                                              @Nullable Channel channel, @Nullable ExtraKey extra, boolean oldKey) {
        Optional<ChatCrypto.Message> decrypted = ChatCrypto.decrypt(key, token);
        if (decrypted.isEmpty()) {
            return Optional.empty();
        }
        ChatCrypto.Message message = decrypted.get();
        String me = selfName;
        String sentAs;
        synchronized (SENT) {
            sentAs = SENT.get(message.nonce());
        }
        Object owner = extra == null ? null : extra.owner();
        String writer;
        boolean mine;
        if (owner instanceof Friends.OwnKey) {
            // Our send key for a friend: only we write with it
            mine = true;
            writer = me != null ? me : sentAs;
        } else if (owner instanceof Friends.Friend friend) {
            // The friend's send key: only they write with it, whatever the line or the sender check says
            mine = false;
            writer = friend.name();
        } else if (sentAs != null) {
            mine = true;
            writer = sentAs;
        } else {
            // The writer's identity is inside the encrypted part. The message's real sender is checked first; then
            // any name on the line. If none matches, the server (or a plugin) attributed it to someone else.
            writer = sender != null && message.writtenBy(sender) ? sender : names.stream().filter(message::writtenBy).findFirst().orElse(null);
            mine = writer != null && me != null && writer.equalsIgnoreCase(me);
        }
        boolean attributed = writer != null && sender != null && sender.equalsIgnoreCase(writer);
        Duration staleAfter = Duration.ofMinutes(ChatSettings.oldAfterMinutes());
        boolean stale = Duration.between(message.sentAt(), Instant.now()).abs().compareTo(staleAfter) > 0;
        return Optional.of(new Decrypted(label, channel, extra, oldKey, message, writer, mine, stale, attributed, sender, false));
    }

    // The decrypted text, with short warnings in front of it when something is off
    private static Component render(Decrypted decrypted, Style style) {
        ChatCrypto.Message message = decrypted.message();
        if (!message.text().isEmpty() && message.text().charAt(0) == CONTROL) {
            synchronized (HANDLED) {
                Component shown = HANDLED.get(message.nonce());
                if (decrypted.replayed()) {
                    return Component.empty().append(Component.literal("[replayed] ").withStyle(ChatFormatting.GOLD))
                        .append(shown != null ? shown : Friends.note("[a PrivacyPlus message shown before]"));
                }
                if (shown == null) {
                    // Not computeIfAbsent: a handler may decrypt other lines (and so re-enter here)
                    shown = renderControl(decrypted, style);
                    HANDLED.put(message.nonce(), shown);
                }
                return shown;
            }
        }
        StringBuilder warnings = new StringBuilder();
        String from = "";
        if (decrypted.writer() == null) {
            warnings.append("[sender mismatch] ");
        } else if (!decrypted.attributed()) {
            // The line's own sender isn't confirmed to be the writer: say who it really is
            from = "[from " + (decrypted.mine() ? "you" : decrypted.writer()) + "] ";
            if (decrypted.sender() != null) {
                warnings.append(from);
                from = "";
            }
        }
        if (!message.senderVerifiable()) {
            warnings.append("[old format] ");
        }
        if (decrypted.oldKey()) {
            warnings.append("[old key] ");
        }
        if (decrypted.stale()) {
            warnings.append("[old] ");
        }
        if (decrypted.replayed()) {
            warnings.append("[replayed] ");
        }
        MutableComponent text = Component.empty();
        if (!from.isEmpty()) {
            text.append(Component.literal(from).withStyle(ChatFormatting.GRAY));
        }
        if (!warnings.isEmpty()) {
            text.append(Component.literal(warnings.toString()).withStyle(ChatFormatting.GOLD));
        }
        if (message.parts() > 1) {
            boolean follows = followsPrevious(decrypted);
            text.append(Component.literal("[" + message.part() + "/" + message.parts() + (follows ? "] " : ", not after the part before] "))
                .withStyle(follows ? ChatFormatting.GRAY : ChatFormatting.GOLD));
        }
        return text.append(Component.literal(message.text()).setStyle(style));
    }

    // Whether this part continues the message the same writer's previous part (under the same key) belonged to
    private static boolean followsPrevious(Decrypted decrypted) {
        ChatCrypto.Message message = decrypted.message();
        if (!message.senderVerifiable()) {
            return true;
        }
        String key = decrypted.label() + "|" + decrypted.writer();
        synchronized (LAST_PART) {
            int[] last = LAST_PART.put(key, new int[]{message.messageId(), message.part()});
            return message.part() == 1 || (last != null && last[0] == message.messageId() && last[1] == message.part() - 1);
        }
    }

    private static Component renderControl(Decrypted decrypted, Style style) {
        String text = decrypted.message().text().substring(1);
        int space = text.indexOf(' ');
        String verb = space == -1 ? text : text.substring(0, space);
        ControlHandler handler = CONTROLS.get(verb);
        if (!decrypted.message().senderVerifiable()) {
            return Component.literal("[a PrivacyPlus message in an old format: ignored]").withStyle(ChatFormatting.GRAY);
        }
        if (handler == null || decrypted.message().parts() > 1) {
            return Component.literal("[a PrivacyPlus message this version can't show]").withStyle(ChatFormatting.GRAY);
        }
        Control control = new Control(verb, space == -1 ? "" : text.substring(space + 1), decrypted.label(), decrypted.channel(),
            decrypted.writer(), decrypted.stale(), decrypted.message().sentAt(), decrypted.extraKey(), decrypted.mine(), decrypted.oldKey());
        try {
            return handler.handle(control, style);
        } catch (RuntimeException e) {
            PrivacyPlus.LOG.warn("Couldn't show a PrivacyPlus {} message", verb, e);
            return Component.literal("[a PrivacyPlus message that couldn't be shown]").withStyle(ChatFormatting.GRAY);
        }
    }

    // "🔒 " (or "[E] ", or "[channel] ") at the start of the line. Hover for the details, click for the settings.
    private static Component lockMark(Decrypted decrypted) {
        Channel channel = decrypted.channel();
        Object owner = decrypted.extraKey() == null ? null : decrypted.extraKey().owner();
        Friends.Friend friend = owner instanceof Friends.Friend f ? f : owner instanceof Friends.OwnKey own ? own.friend() : null;
        boolean code = owner instanceof Friends.FriendCode || owner instanceof Friends.Outgoing;
        boolean unverified = friend != null && !friend.verified();
        boolean trusted = decrypted.writer() != null && !decrypted.stale() && !decrypted.oldKey() && !decrypted.replayed() && !unverified
            && decrypted.message().senderVerifiable() && (decrypted.attributed() || decrypted.sender() == null);
        String name = channel != null ? channel.name() : friend != null ? friend.name() : code ? "friend code" : decrypted.label();
        int color = code ? 0xAAAAAA : !trusted ? 0xFFAA00 : channel != null ? channel.color() : 0x55FFFF;

        String keyLine;
        if (channel != null) {
            keyLine = decrypted.oldKey() ? "Encrypted with an older key of channel " + channel.name() + ": from before someone left, or replayed"
                : "Encrypted with channel " + channel.name();
        } else if (friend != null) {
            keyLine = "Encrypted with your friend key with " + friend.name() + (decrypted.mine() ? " (sent by you)" : "")
                + (friend.verified() ? " (verified)" : "\nNot verified yet: compare safety codes with " + friend.name() + " (settings > Friends)");
        } else if (code) {
            keyLine = "Encrypted with " + decrypted.label() + ": a key exchange";
        } else {
            keyLine = "Encrypted with " + decrypted.label();
        }
        String writer;
        if (decrypted.writer() == null) {
            writer = "\nNo name on this line matches the writer: the server may be faking the sender";
        } else if (decrypted.attributed() || decrypted.sender() == null) {
            writer = " by " + (decrypted.mine() ? "you" : decrypted.writer());
        } else {
            writer = " by " + (decrypted.mine() ? "you" : decrypted.writer()) + "\nThe server shows it as from " + decrypted.sender();
        }
        String details = keyLine
            + "\nSent at " + TIME.format(decrypted.message().sentAt()) + writer
            + (decrypted.message().senderVerifiable() ? "" : "\nIn an older format, whose sender can't be checked")
            + (decrypted.stale() ? "\nOlder than " + ChatSettings.oldAfterMinutes() + " minutes: the server may be replaying it" : "")
            + (decrypted.replayed() ? "\nShown before: the server is repeating it" : "")
            + "\nClick for encrypted chat settings";
        boolean showName = ChatSettings.showChannelName();
        String mark = switch (ChatSettings.lockStyle()) {
            case LOCK -> LOCK + (showName ? " " + name : "");
            case TEXT -> "[E]" + (showName ? " " + name : "");
            case NAME -> "[" + name + "]";
        };
        return Component.literal(mark + " ").setStyle(Style.EMPTY
            .withColor(color)
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(details)))
            .withClickEvent(OPEN_SETTINGS));
    }

    // Clicking the lock in chat. Handled locally by ChatScreenMixin; if that ever failed, vanilla would
    // just copy this text to the clipboard (never anything sent to the server).
    public static boolean opensSettings(Style style) {
        return style.getClickEvent() instanceof ClickEvent.CopyToClipboard copy && copy.value().equals(SETTINGS_CLICK);
    }

    // A gray "[PrivacyPlus] ..." line in chat, shown only to you
    public static void feedback(Minecraft mc, String text) {
        feedback(mc, Component.literal(text));
    }

    public static void feedback(Minecraft mc, Component text) {
        mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[" + PrivacyPlus.MOD_NAME + "] ").append(text).withStyle(ChatFormatting.GRAY));
    }

    // For tests: forget which messages were seen
    static void resetSeenForTests() {
        synchronized (SEEN) {
            SEEN.clear();
        }
        synchronized (RECENT) {
            java.util.Arrays.fill(RECENT, null);
        }
    }
}
