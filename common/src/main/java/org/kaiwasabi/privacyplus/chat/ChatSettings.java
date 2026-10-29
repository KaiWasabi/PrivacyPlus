package org.kaiwasabi.privacyplus.chat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.kaiwasabi.privacyplus.PrivacyPlus;
import org.jspecify.annotations.Nullable;

// Everything in config/privacyplus-chat.properties: channels (passphrase channels, and friend channels
// whose keys are managed by their owner's client), the lock state, what the lock encrypts, how encrypted
// lines look, and per-server choices. The file holds passphrases and keys, so it is readable by its owner
// only. Changed from the render thread (lock button, settings screen, /pp, friend messages); read from anywhere.
public final class ChatSettings {
    private static final String FILE_NAME = PrivacyPlus.MOD_ID + "-chat.properties";
    static final String DEFAULT_COMMANDS = "msg:1 tell:1 w:1 whisper:1 m:1 pm:1 r:0 reply:0 teammsg:0 tm:0 me:0";
    public static final int DEFAULT_COLOR = 0x55FF55;
    public static final int MAX_READABLE_WORDS = 5;
    public static final List<Integer> OLD_AFTER_CHOICES = List.of(2, 5, 10, 30, 60);
    // Old keys of a friend channel kept to read older messages
    static final int MAX_OLD_KEYS = 8;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,24}");
    private static final Pattern COMMAND_NAME = Pattern.compile("[a-z0-9_.:-]{1,32}");
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final String HEADER = """
        # PrivacyPlus encrypted chat. Easiest to change in game: right-click the lock next to the chat box.
        # This file holds passphrases and keys: keep it to yourself.
        #
        # channel.<name>        A shared passphrase. Everyone in a channel needs the same one: share it
        #                       outside Minecraft (Signal, in person), never in game chat. Make it long; the
        #                       server sees every encrypted message and can try to guess weak ones offline.
        # friendChannel.<name>  A channel whose key the owner's client hands out to friends (id, epoch, key,
        #                       owner key, owner name; .old: older keys; .members: the owner's member list).
        # color.<name>          Color of the lock mark and channel name, as RRGGBB.
        # active                Channel the lock encrypts to. Empty means the lock is off.
        # encryptPublicChat     Encrypt normal chat while the lock is on (otherwise only the commands below).
        # encryptCommands       Commands encrypted while the lock is on, as name:N, where the first N
        #                       arguments stay readable (msg:1 keeps the recipient's name).
        # encryptFriendMessages Always encrypt private messages to friends, even with the lock off.
        # lockMark              lock, text ([E]) or name (the channel name only).
        # showChannelName       Show the channel name on encrypted lines.
        # unreadable            Encrypted messages you can't read: show, replace (with [encrypted]) or hide.
        # oldAfterMinutes       Encrypted messages older than this are marked [old]: the server may be replaying them.
        # autoChannels          server>channel pairs: the channel the lock turns on when you join that server.
        # unsignedChatServers   Servers where your chat isn't signed, so it can't be reported to Mojang.
        #                       Those servers can tell, and some refuse unsigned chat.

        """;

    public enum Kind { PASSPHRASE, FRIENDS }

    public enum LockStyle { LOCK, TEXT, NAME }

    public enum Unreadable { SHOW, REPLACE, HIDE }

    // A friend channel's member, as its owner's client tracks them. INVITE: invitation not sent yet (they
    // haven't been online); INVITED: sent, no answer; JOINED: in, with the newest epoch they confirmed;
    // EXPIRED: never answered.
    public enum MemberState { INVITE, INVITED, JOINED, EXPIRED }

    public record Member(String publicKey, String name, MemberState state, int ackedEpoch, int addedEpoch, long since) {
        public Member withState(MemberState newState, long now) {
            return new Member(this.publicKey, this.name, newState, this.ackedEpoch, this.addedEpoch, now);
        }

        public Member withAcked(int epoch) {
            return new Member(this.publicKey, this.name, MemberState.JOINED, Math.max(this.ackedEpoch, epoch), this.addedEpoch, this.since);
        }
    }

    public record OldKey(int epoch, SecretKey key) {
        @Override
        public String toString() {
            return "OldKey[epoch " + this.epoch + "]";
        }
    }

    // How a friend channel is run. Its owner (identified by their public key) creates a random key and sends
    // it to each member, encrypted with that member's friend key; removing someone means a new key (epoch + 1)
    // for everyone else. Members only know the owner; members is the owner's list, empty for everyone else.
    public record Group(String id, String ownerKey, String ownerName, int epoch, SecretKey key, List<OldKey> oldKeys, List<Member> members) {
        public Group {
            oldKeys = List.copyOf(oldKeys);
            members = List.copyOf(members);
        }

        public boolean ownedBy(String publicKey) {
            return this.ownerKey.equals(publicKey);
        }

        public @Nullable Member member(String publicKey) {
            return this.members.stream().filter(m -> m.publicKey().equals(publicKey)).findFirst().orElse(null);
        }

        // The next epoch with a new key; the current key is kept to read older messages
        public Group rekeyed(int newEpoch, SecretKey newKey) {
            List<OldKey> old = new ArrayList<>(this.oldKeys);
            old.addFirst(new OldKey(this.epoch, this.key));
            List<OldKey> kept = old.subList(0, Math.min(old.size(), MAX_OLD_KEYS));
            return new Group(this.id, this.ownerKey, this.ownerName, newEpoch, newKey, kept, this.members);
        }

        public Group withMember(Member member) {
            List<Member> updated = new ArrayList<>(this.members.stream().filter(m -> !m.publicKey().equals(member.publicKey())).toList());
            updated.add(member);
            return this.withMembers(updated);
        }

        public Group withoutMember(String publicKey) {
            return this.withMembers(this.members.stream().filter(m -> !m.publicKey().equals(publicKey)).toList());
        }

        public Group withMembers(List<Member> newMembers) {
            return new Group(this.id, this.ownerKey, this.ownerName, this.epoch, this.key, this.oldKeys, newMembers);
        }

        public Group withOwnerName(String name) {
            return new Group(this.id, this.ownerKey, name, this.epoch, this.key, this.oldKeys, this.members);
        }

        @Override
        public String toString() {
            return "Group[" + this.ownerName + ", epoch " + this.epoch + ", " + this.members.size() + " members]";
        }
    }

    // passphrase is empty for friend channels, whose key comes from group
    public record Channel(String name, String passphrase, CompletableFuture<SecretKey> key, int color, @Nullable Group group) {
        public Channel(String name, String passphrase, CompletableFuture<SecretKey> key) {
            this(name, passphrase, key, DEFAULT_COLOR, null);
        }

        public @Nullable SecretKey readyKey() {
            return this.key.isDone() && !this.key.isCompletedExceptionally() ? this.key.join() : null;
        }

        public Kind kind() {
            return this.group == null ? Kind.PASSPHRASE : Kind.FRIENDS;
        }

        Channel renamed(String newName) {
            return new Channel(newName, this.passphrase, this.key, this.color, this.group);
        }

        Channel withColor(int newColor) {
            return new Channel(this.name, this.passphrase, this.key, newColor, this.group);
        }

        Channel withGroup(Group newGroup) {
            return new Channel(this.name, "", CompletableFuture.completedFuture(newGroup.key()), this.color, newGroup);
        }

        // Never the passphrase or key, wherever a channel ends up printed
        @Override
        public String toString() {
            return "Channel[" + this.name + ", " + this.kind().name().toLowerCase(Locale.ROOT) + "]";
        }
    }

    // One row of the private-message commands: /name, with the first readableWords arguments left readable
    public record CommandRule(String name, int readableWords) {
    }

    private static volatile Map<String, Channel> channels = Map.of();
    private static volatile @Nullable String activeName;
    private static volatile @Nullable String lastUsedName;
    private static volatile boolean encryptPublicChat = true;
    private static volatile String encryptCommandsText = DEFAULT_COMMANDS;
    private static volatile Map<String, Integer> encryptCommands = parseCommands(DEFAULT_COMMANDS);
    private static volatile boolean encryptFriendMessages = true;
    private static volatile LockStyle lockStyle = LockStyle.LOCK;
    private static volatile boolean showChannelName = false;
    private static volatile Unreadable unreadable = Unreadable.SHOW;
    private static volatile int oldAfterMinutes = 10;
    private static volatile Map<String, String> autoChannels = Map.of();
    private static volatile Set<String> unsignedServers = Set.of();
    private static @Nullable Path file;

    private ChatSettings() {
    }

    public static void load(Path configDir) {
        file = configDir.resolve(FILE_NAME);
        reload();
    }

    // Where the chat files live (null before load, e.g. in tests that don't load)
    static @Nullable Path configDir() {
        return file == null ? null : file.toAbsolutePath().getParent();
    }

    public static synchronized int reload() {
        Properties props = new Properties();
        if (file != null && Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException e) {
                PrivacyPlus.LOG.error("Couldn't read {}", file, e);
            }
        }

        Map<String, Channel> loaded = new LinkedHashMap<>();
        props.stringPropertyNames().stream().sorted().forEach(key -> {
            int dot = key.indexOf('.');
            String name = dot == -1 ? "" : key.substring(dot + 1);
            if (!NAME.matcher(name).matches() || loaded.containsKey(name)) {
                return;
            }
            int color = parseColor(props.getProperty("color." + name, ""));
            if (key.startsWith("channel.")) {
                Channel channel = newChannel(name, props.getProperty(key));
                loaded.put(name, channel.withColor(color));
            } else if (key.startsWith("friendChannel.")) {
                Group group = parseGroup(props.getProperty(key), props.getProperty(key + ".old", ""), props.getProperty(key + ".members", ""));
                if (group != null) {
                    loaded.put(name, new Channel(name, "", CompletableFuture.completedFuture(group.key()), color, group));
                } else {
                    PrivacyPlus.LOG.warn("Skipping unreadable friend channel {} in {}", name, file);
                }
            }
        });
        channels = Map.copyOf(loaded);
        String active = props.getProperty("active", "").trim();
        activeName = channels.containsKey(active) ? active : null;
        lastUsedName = activeName;
        encryptPublicChat = Boolean.parseBoolean(props.getProperty("encryptPublicChat", "true").trim());
        setCommandsQuietly(props.getProperty("encryptCommands", DEFAULT_COMMANDS));
        encryptFriendMessages = Boolean.parseBoolean(props.getProperty("encryptFriendMessages", "true").trim());
        lockStyle = parseEnum(LockStyle.class, props.getProperty("lockMark", ""), LockStyle.LOCK);
        showChannelName = Boolean.parseBoolean(props.getProperty("showChannelName", "false").trim());
        unreadable = parseEnum(Unreadable.class, props.getProperty("unreadable", ""), Unreadable.SHOW);
        try {
            oldAfterMinutes = Math.clamp(Integer.parseInt(props.getProperty("oldAfterMinutes", "10").trim()), 1, 1440);
        } catch (NumberFormatException e) {
            oldAfterMinutes = 10;
        }
        Map<String, String> auto = new TreeMap<>();
        for (String pair : props.getProperty("autoChannels", "").split("[,\\s]+")) {
            int arrow = pair.lastIndexOf('>');
            if (arrow > 0 && NAME.matcher(pair.substring(arrow + 1)).matches()) {
                auto.put(serverKey(pair.substring(0, arrow)), pair.substring(arrow + 1));
            }
        }
        autoChannels = Map.copyOf(auto);
        Set<String> servers = new TreeSet<>();
        for (String server : props.getProperty("unsignedChatServers", "").split("[,\\s]+")) {
            if (!server.isBlank()) {
                servers.add(serverKey(server));
            }
        }
        unsignedServers = Set.copyOf(servers);
        save();
        return channels.size();
    }

    // Rewrites the whole file. Passphrases are written back as they were entered.
    public static synchronized void save() {
        if (file == null) {
            return;
        }
        StringBuilder out = new StringBuilder(HEADER)
            .append("active=").append(activeName == null ? "" : activeName).append('\n')
            .append("encryptPublicChat=").append(encryptPublicChat).append('\n')
            .append("encryptCommands=").append(escape(encryptCommandsText)).append('\n')
            .append("encryptFriendMessages=").append(encryptFriendMessages).append('\n')
            .append("lockMark=").append(lockStyle.name().toLowerCase(Locale.ROOT)).append('\n')
            .append("showChannelName=").append(showChannelName).append('\n')
            .append("unreadable=").append(unreadable.name().toLowerCase(Locale.ROOT)).append('\n')
            .append("oldAfterMinutes=").append(oldAfterMinutes).append('\n')
            .append("autoChannels=").append(String.join(",", autoChannels.entrySet().stream().map(e -> e.getKey() + ">" + e.getValue()).toList()))
            .append('\n')
            .append("unsignedChatServers=").append(String.join(",", unsignedServers)).append("\n\n");
        for (Channel c : all()) {
            if (c.group() == null) {
                out.append("channel.").append(c.name()).append('=').append(escape(c.passphrase())).append('\n');
            } else {
                writeGroup(out, c.name(), c.group());
            }
            if (c.color() != DEFAULT_COLOR) {
                out.append("color.").append(c.name()).append('=').append(String.format(Locale.ROOT, "%06X", c.color())).append('\n');
            }
        }
        try {
            SecretFiles.write(file, out.toString());
        } catch (IOException e) {
            PrivacyPlus.LOG.error("Couldn't write {}", file, e);
        }
    }

    public static List<Channel> all() {
        return channels.values().stream().sorted(Comparator.comparing(c -> c.name().toLowerCase(Locale.ROOT))).toList();
    }

    public static @Nullable Channel get(String name) {
        return channels.get(name);
    }

    // The friend channel with this id, wherever it's listed and whatever it's called here
    public static @Nullable Channel byGroupId(String id) {
        return channels.values().stream().filter(c -> c.group() != null && c.group().id().equals(id)).findFirst().orElse(null);
    }

    // The friend channel this owner runs under this id: ids are only unique per owner, so a friend can't take
    // over another's channel by reusing its id
    public static @Nullable Channel byGroup(String ownerKey, String id) {
        return channels.values().stream().filter(c -> c.group() != null && c.group().id().equals(id) && c.group().ownedBy(ownerKey))
            .findFirst().orElse(null);
    }

    public static boolean isValidName(String name) {
        return NAME.matcher(name).matches();
    }

    // name, or name-2, name-3... if a channel already has it
    public static String freeName(String wanted) {
        String base = isValidName(wanted) ? wanted : "channel";
        String name = base;
        for (int i = 2; channels.containsKey(name); i++) {
            String suffix = "-" + i;
            name = base.substring(0, Math.min(base.length(), 24 - suffix.length())) + suffix;
        }
        return name;
    }

    public static synchronized void addChannel(String name, String passphrase) {
        if (!isValidName(name) || passphrase.isEmpty()) {
            throw new IllegalArgumentException("Invalid channel");
        }
        Channel old = channels.get(name);
        put(newChannel(name, passphrase).withColor(old == null ? DEFAULT_COLOR : old.color()));
        save();
    }

    public static synchronized void addFriendChannel(String name, Group group) {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("Invalid channel");
        }
        put(new Channel(name, "", CompletableFuture.completedFuture(group.key()), DEFAULT_COLOR, group));
        save();
    }

    // Replaces a friend channel's group (new key, members...). False if there is no such friend channel.
    public static synchronized boolean updateGroup(String name, UnaryOperator<Group> update) {
        Channel channel = channels.get(name);
        if (channel == null || channel.group() == null) {
            return false;
        }
        put(channel.withGroup(update.apply(channel.group())));
        save();
        return true;
    }

    public static synchronized void removeChannel(String name) {
        Map<String, Channel> updated = new LinkedHashMap<>(channels);
        updated.remove(name);
        channels = Map.copyOf(updated);
        if (name.equals(activeName)) {
            activeName = null;
        }
        if (name.equals(lastUsedName)) {
            lastUsedName = null;
        }
        Map<String, String> auto = new TreeMap<>(autoChannels);
        auto.values().removeIf(name::equals);
        autoChannels = Map.copyOf(auto);
        save();
    }

    // Names are local: friends who share a channel may call it different things. False if newName is taken or invalid.
    public static synchronized boolean renameChannel(String name, String newName) {
        Channel channel = channels.get(name);
        if (channel == null || !isValidName(newName) || (channels.containsKey(newName) && !newName.equals(name))) {
            return false;
        }
        Map<String, Channel> updated = new LinkedHashMap<>(channels);
        updated.remove(name);
        updated.put(newName, channel.renamed(newName));
        channels = Map.copyOf(updated);
        if (name.equals(activeName)) {
            activeName = newName;
        }
        if (name.equals(lastUsedName)) {
            lastUsedName = newName;
        }
        Map<String, String> auto = new TreeMap<>(autoChannels);
        auto.replaceAll((server, channelName) -> channelName.equals(name) ? newName : channelName);
        autoChannels = Map.copyOf(auto);
        save();
        return true;
    }

    public static synchronized void setColor(String name, int color) {
        Channel channel = channels.get(name);
        if (channel != null) {
            put(channel.withColor(color & 0xFFFFFF));
            save();
        }
    }

    // The channel the lock encrypts to, or null when the lock is off
    public static @Nullable Channel active() {
        String name = activeName;
        return name == null ? null : channels.get(name);
    }

    public static synchronized void setActive(@Nullable Channel channel) {
        activeName = channel == null ? null : channel.name();
        if (channel != null) {
            lastUsedName = channel.name();
        }
        save();
    }

    // What the lock turns on with: the channel used last, otherwise the first one
    public static @Nullable Channel lastUsed() {
        Channel last = lastUsedName == null ? null : channels.get(lastUsedName);
        return last != null ? last : all().stream().findFirst().orElse(null);
    }

    // The channel after current in the list (wrapping), or the first one; null if there are none
    public static @Nullable Channel next(@Nullable Channel current) {
        List<Channel> list = all();
        if (list.isEmpty()) {
            return null;
        }
        int index = current == null ? -1 : list.stream().map(Channel::name).toList().indexOf(current.name());
        return list.get((index + 1) % list.size());
    }

    public static boolean encryptPublicChat() {
        return encryptPublicChat;
    }

    public static void setEncryptPublicChat(boolean value) {
        encryptPublicChat = value;
        save();
    }

    public static Map<String, Integer> encryptCommands() {
        return encryptCommands;
    }

    public static String encryptCommandsText() {
        return encryptCommandsText;
    }

    public static void setEncryptCommands(String text) {
        setCommandsQuietly(text);
        save();
    }

    // The commands in the order written, e.g. for the settings screen
    public static List<CommandRule> commandRules() {
        return commandRules(encryptCommandsText);
    }

    static List<CommandRule> commandRules(String text) {
        Map<String, Integer> parsed = new LinkedHashMap<>();
        for (String rule : text.trim().split("[,\\s]+")) {
            int colon = rule.lastIndexOf(':');
            String name = (colon == -1 ? rule : rule.substring(0, colon)).replaceFirst("^/", "").toLowerCase(Locale.ROOT);
            try {
                int readable = colon == -1 ? 0 : Integer.parseInt(rule.substring(colon + 1));
                if (COMMAND_NAME.matcher(name).matches() && readable >= 0 && readable <= MAX_READABLE_WORDS) {
                    parsed.putIfAbsent(name, readable);
                }
            } catch (NumberFormatException ignored) {
                // skip malformed rule
            }
        }
        return parsed.entrySet().stream().map(e -> new CommandRule(e.getKey(), e.getValue())).toList();
    }

    // Saved in the same "msg:1 tell:1" form as before; rows with an empty or invalid name are dropped
    public static void setCommandRules(List<CommandRule> rules) {
        setEncryptCommands(String.join(" ", rules.stream()
            .map(r -> new CommandRule(r.name().trim().replaceFirst("^/", "").toLowerCase(Locale.ROOT), r.readableWords()))
            .filter(r -> COMMAND_NAME.matcher(r.name()).matches())
            .map(r -> r.name() + ":" + Math.clamp(r.readableWords(), 0, MAX_READABLE_WORDS))
            .toList()));
    }

    public static boolean encryptFriendMessages() {
        return encryptFriendMessages;
    }

    public static void setEncryptFriendMessages(boolean value) {
        encryptFriendMessages = value;
        save();
    }

    public static LockStyle lockStyle() {
        return lockStyle;
    }

    public static void setLockStyle(LockStyle value) {
        lockStyle = value;
        save();
    }

    public static boolean showChannelName() {
        return showChannelName;
    }

    public static void setShowChannelName(boolean value) {
        showChannelName = value;
        save();
    }

    public static Unreadable unreadable() {
        return unreadable;
    }

    public static void setUnreadable(Unreadable value) {
        unreadable = value;
        save();
    }

    public static int oldAfterMinutes() {
        return oldAfterMinutes;
    }

    public static void setOldAfterMinutes(int minutes) {
        oldAfterMinutes = Math.clamp(minutes, 1, 1440);
        save();
    }

    // The channel to turn on when joining this server, if one is set and still exists
    public static @Nullable Channel autoChannel(String serverAddress) {
        String name = autoChannels.get(serverKey(serverAddress));
        return name == null ? null : channels.get(name);
    }

    public static synchronized void setAutoChannel(String serverAddress, @Nullable String channelName) {
        Map<String, String> updated = new TreeMap<>(autoChannels);
        if (channelName == null) {
            updated.remove(serverKey(serverAddress));
        } else {
            updated.put(serverKey(serverAddress), channelName);
        }
        autoChannels = Map.copyOf(updated);
        save();
    }

    public static boolean isUnsigned(String serverAddress) {
        return unsignedServers.contains(serverKey(serverAddress));
    }

    public static synchronized void setUnsigned(String serverAddress, boolean unsigned) {
        Set<String> updated = new TreeSet<>(unsignedServers);
        if (unsigned) {
            updated.add(serverKey(serverAddress));
        } else {
            updated.remove(serverKey(serverAddress));
        }
        unsignedServers = Set.copyOf(updated);
        save();
    }

    // 144 random bits, short enough to read out or paste into a message
    public static String randomPassphrase() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // A rough, cautious guess at how many bits of guessing the passphrase takes. Every channel's key comes from
    // its passphrase the same way, so an attacker can try a dictionary once against all channels: words count
    // as common words (about 11 bits each), and repeats, runs and well-known passwords count for little.
    public static int passphraseBits(String passphrase) {
        String lower = passphrase.toLowerCase(Locale.ROOT);
        for (String common : COMMON) {
            if (lower.contains(common)) {
                lower = lower.replace(common, "");
            }
        }
        if (lower.isBlank()) {
            return 0;
        }
        String[] words = lower.trim().split("[\\s_.,-]+");
        if (words.length >= 2 && Arrays.stream(words).allMatch(w -> w.matches("[\\p{L}]+"))) {
            return words.length * 11;
        }
        int pool = 0;
        if (lower.chars().anyMatch(Character::isLetter)) {
            pool += passphrase.chars().anyMatch(Character::isUpperCase) && passphrase.chars().anyMatch(Character::isLowerCase) ? 52 : 26;
        }
        if (lower.chars().anyMatch(Character::isDigit)) {
            pool += 10;
        }
        if (lower.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) {
            pool += 33;
        }
        // Characters that repeat or continue a run add little
        int counted = 0;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean predictable = i > 0 && (c == lower.charAt(i - 1) || c == lower.charAt(i - 1) + 1 || c == lower.charAt(i - 1) - 1);
            counted += predictable ? 0 : 1;
        }
        long distinct = lower.chars().distinct().count();
        return (int) (Math.min(counted, distinct * 2) * Math.log(Math.max(pool, 2)) / Math.log(2));
    }

    private static final List<String> COMMON = List.of("password", "passwort", "qwerty", "azerty", "123456", "letmein", "minecraft",
        "admin", "welcome", "iloveyou", "dragon", "monkey", "abc123");

    // For tests: a channel with an already derived key
    static void put(String name, SecretKey key) {
        put(new Channel(name, "", CompletableFuture.completedFuture(key)));
    }

    private static synchronized void put(Channel channel) {
        Map<String, Channel> updated = new LinkedHashMap<>(channels);
        updated.put(channel.name(), channel);
        channels = Map.copyOf(updated);
    }

    // "msg:1 r:0" (spaces or commas) -> {msg=1, r=0}; anything malformed is skipped
    static Map<String, Integer> parseCommands(String text) {
        Map<String, Integer> parsed = new LinkedHashMap<>();
        for (String rule : text.trim().split("[,\\s]+")) {
            int colon = rule.indexOf(':');
            String name = (colon == -1 ? rule : rule.substring(0, colon)).replaceFirst("^/", "").toLowerCase(Locale.ROOT);
            try {
                int plain = colon == -1 ? 0 : Integer.parseInt(rule.substring(colon + 1));
                if (!name.isEmpty() && plain >= 0 && plain <= MAX_READABLE_WORDS) {
                    parsed.put(name, plain);
                }
            } catch (NumberFormatException ignored) {
                // skip malformed rule
            }
        }
        return Map.copyOf(parsed);
    }

    // play.Example.com, play.example.com:25565 and the like all name the same server
    static String serverKey(String serverAddress) {
        ServerAddress address = ServerAddress.parseString(serverAddress.trim());
        return address.getHost().toLowerCase(Locale.ROOT) + ":" + address.getPort();
    }

    private static void setCommandsQuietly(String text) {
        encryptCommandsText = text.trim();
        encryptCommands = parseCommands(encryptCommandsText);
    }

    private static Channel newChannel(String name, String passphrase) {
        return new Channel(name, passphrase, CompletableFuture.supplyAsync(() -> ChatCrypto.deriveKey(passphrase)));
    }

    // friendChannel.<name>=<id> <epoch> <key> <owner key> <owner name>
    // friendChannel.<name>.old=<epoch>:<key> ...
    // friendChannel.<name>.members=<key>:<name>:<state>:<acked epoch>:<added epoch>:<since> ...
    private static void writeGroup(StringBuilder out, String name, Group group) {
        String prefix = "friendChannel." + name;
        out.append(prefix).append('=').append(group.id()).append(' ').append(group.epoch()).append(' ')
            .append(FriendCrypto.encode(group.key().getEncoded())).append(' ').append(group.ownerKey()).append(' ').append(group.ownerName())
            .append('\n');
        if (!group.oldKeys().isEmpty()) {
            out.append(prefix).append(".old=").append(String.join(" ", group.oldKeys().stream()
                .map(k -> k.epoch() + ":" + FriendCrypto.encode(k.key().getEncoded())).toList())).append('\n');
        }
        if (!group.members().isEmpty()) {
            out.append(prefix).append(".members=").append(String.join(" ", group.members().stream()
                .map(m -> String.join(":", m.publicKey(), m.name(), m.state().name().toLowerCase(Locale.ROOT),
                    String.valueOf(m.ackedEpoch()), String.valueOf(m.addedEpoch()), String.valueOf(m.since())))
                .toList())).append('\n');
        }
    }

    static @Nullable Group parseGroup(String main, String old, String members) {
        try {
            String[] f = main.trim().split(" ");
            if (f.length != 5 || !FriendMessages.isChannelId(f[0]) || !FriendCrypto.isPublicKey(f[3]) || !PLAYER_NAME.matcher(f[4]).matches()) {
                return null;
            }
            SecretKey key = keyOf(f[2]);
            List<OldKey> oldKeys = new ArrayList<>();
            for (String entry : old.trim().split(" ")) {
                int colon = entry.indexOf(':');
                if (colon > 0) {
                    oldKeys.add(new OldKey(Integer.parseInt(entry.substring(0, colon)), keyOf(entry.substring(colon + 1))));
                }
            }
            List<Member> memberList = new ArrayList<>();
            for (String entry : members.trim().split(" ")) {
                String[] m = entry.split(":");
                if (m.length == 6 && FriendCrypto.isPublicKey(m[0])) {
                    memberList.add(new Member(m[0], m[1], MemberState.valueOf(m[2].toUpperCase(Locale.ROOT)), Integer.parseInt(m[3]),
                        Integer.parseInt(m[4]), Long.parseLong(m[5])));
                }
            }
            return new Group(f[0], f[3], f[4], Integer.parseInt(f[1]), key, oldKeys.subList(0, Math.min(oldKeys.size(), MAX_OLD_KEYS)), memberList);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static SecretKey keyOf(String base64) {
        byte[] bytes = FriendCrypto.decode(base64);
        if (bytes == null || bytes.length != FriendCrypto.KEY_BYTES) {
            throw new IllegalArgumentException("Not a key");
        }
        return FriendCrypto.aesKey(bytes);
    }

    private static int parseColor(String text) {
        try {
            return text.isBlank() ? DEFAULT_COLOR : Integer.parseInt(text.trim().replaceFirst("^#", ""), 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return DEFAULT_COLOR;
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String text, E fallback) {
        try {
            return Enum.valueOf(type, text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // Properties escaping for values: backslashes, and leading spaces that load() would drop
    private static String escape(String value) {
        String escaped = value.replace("\\", "\\\\");
        return escaped.startsWith(" ") ? "\\" + escaped : escaped;
    }
}
