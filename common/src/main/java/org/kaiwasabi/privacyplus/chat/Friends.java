package org.kaiwasabi.privacyplus.chat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.crypto.SecretKey;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.kaiwasabi.privacyplus.PrivacyPlus;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Channel;
import org.kaiwasabi.privacyplus.chat.EncryptedChat.Control;
import org.kaiwasabi.privacyplus.chat.EncryptedChat.ExtraKey;
import org.kaiwasabi.privacyplus.chat.FriendCrypto.Identity;
import org.kaiwasabi.privacyplus.chat.FriendCrypto.PairKeys;
import org.kaiwasabi.privacyplus.chat.FriendMessages.Done;
import org.kaiwasabi.privacyplus.chat.FriendMessages.HelloReply;
import org.kaiwasabi.privacyplus.chat.FriendMessages.HelloRequest;
import org.kaiwasabi.privacyplus.chat.FriendMessages.Rename;
import org.jspecify.annotations.Nullable;

// Per-friend keys. Each client has a long-term X25519 identity (config/privacyplus-identity.properties,
// made the first time it's needed). Two players become friends with a key exchange over /msg:
//   1. A -> B  hello request: A's public key and a commitment to a random nonce
//   2. B accepts. B -> A  hello reply: B's public key, B's nonce, and a tag made with the pair's tag key
//   3. A checks the tag and stores B. A -> B  friend-ok, under A's send key: A's nonce, which B checks
//      against the commitment before storing A as a finished friend.
// Steps 1 and 2 travel under a key the server doesn't have, so it can't recognize them (nor link anyone's
// public key across servers or names):
//   - a channel both have: one A has seen B write in, or one A picks. B decides with Accept or Ignore.
//   - a one-time friend code: B made it and gave it to A outside Minecraft. The request goes under a key derived
//     (slowly) from the code and B's name, and B's client answers by itself: holding the code is the consent.
// Both sides then show the same safety code, to compare outside Minecraft: a server (or channel member) that
// swapped keys in the middle would make the codes differ. Friends are identified by public key, and each
// direction has its own key, so who wrote a message is decided by the key that opens it, not by names on the
// line. A new name is only taken on from a "rename" message under the friend's key, after the player confirms.
// The friend list, our friend codes and queued notes are in config/privacyplus-friends.properties.
// Render thread only, except reads.
public final class Friends {
    static final long REQUEST_LIFETIME_MILLIS = 15 * 60_000L;
    static final long CODE_LIFETIME_MILLIS = 24 * 3_600_000L;
    static final int MAX_REQUESTS = 5;
    static final int MAX_CODES = 5;
    // Queued notes to friends kept over a restart
    static final int MAX_SAVED_SENDS = 50;
    private static final String FRIENDS_FILE = PrivacyPlus.MOD_ID + "-friends.properties";
    private static final String IDENTITY_FILE = PrivacyPlus.MOD_ID + "-identity.properties";
    static final SendQueue QUEUE = new SendQueue();

    // A friend: their public key (which identifies them), current name, and the names both sides had when the
    // keys were exchanged (bound into the keys). Until confirmed, the friend-ok of step 3 hasn't arrived and the
    // keys aren't used to send. pendingCommitment and pendingNonce are kept while an exchange is under way, to
    // check its friend-ok (a friend exchanging again with the same keys stays confirmed meanwhile). formerNames:
    // names they had before a confirmed rename. toldName: the name of ours they last heard.
    public record Friend(String publicKey, String name, String myName, String theirName, boolean verified, @Nullable String safetyCode,
                         boolean confirmed, long addedAt, @Nullable String pendingCommitment, @Nullable String pendingNonce, PairKeys keys,
                         List<String> formerNames, String toldName) {
        public Friend {
            formerNames = List.copyOf(formerNames);
        }

        Friend withName(String newName) {
            List<String> former = new ArrayList<>(this.formerNames.stream().filter(n -> !n.equalsIgnoreCase(newName)).toList());
            if (!this.name.equalsIgnoreCase(newName) && former.stream().noneMatch(this.name::equalsIgnoreCase)) {
                former.add(this.name);
            }
            return new Friend(this.publicKey, newName, this.myName, this.theirName, this.verified, this.safetyCode, this.confirmed, this.addedAt,
                this.pendingCommitment, this.pendingNonce, this.keys, former.subList(Math.max(0, former.size() - 8), former.size()), this.toldName);
        }

        Friend withVerified(boolean value) {
            return new Friend(this.publicKey, this.name, this.myName, this.theirName, value, this.safetyCode, this.confirmed, this.addedAt,
                this.pendingCommitment, this.pendingNonce, this.keys, this.formerNames, this.toldName);
        }

        Friend confirmedWith(String code) {
            return new Friend(this.publicKey, this.name, this.myName, this.theirName, this.verified, code, true, this.addedAt, null, null,
                this.keys, this.formerNames, this.toldName);
        }

        Friend withPending(String commitment, String nonce) {
            return new Friend(this.publicKey, this.name, this.myName, this.theirName, this.verified, this.safetyCode, this.confirmed, this.addedAt,
                commitment, nonce, this.keys, this.formerNames, this.toldName);
        }

        Friend withToldName(String told) {
            return new Friend(this.publicKey, this.name, this.myName, this.theirName, this.verified, this.safetyCode, this.confirmed, this.addedAt,
                this.pendingCommitment, this.pendingNonce, this.keys, this.formerNames, told);
        }

        @Override
        public String toString() {
            return "Friend[" + this.name + ", " + FriendCrypto.fingerprint(this.publicKey) + (this.verified ? ", verified" : "") + "]";
        }
    }

    // The send key of a friend, as an extra key: what opens with it is ours (our own message, echoed back)
    public record OwnKey(Friend friend) {
    }

    // Someone asked us to exchange keys. via is the key the request came under (the reply goes under it too);
    // route says what that key is, e.g. "channel squad".
    public record Request(String name, String publicKey, String commitment, long receivedAt, SecretKey via, String route) {
        @Override
        public String toString() {
            return "Request[" + this.name + " through " + this.route + "]";
        }
    }

    // We asked someone; nonce is revealed in step 3. code: via is a friend code's key, which we must try on
    // incoming text until the reply comes.
    public record Outgoing(String name, byte[] nonce, String commitment, long sentAt, SecretKey via, String route, boolean code) {
        @Override
        public String toString() {
            return "Outgoing[" + this.name + " through " + this.route + "]";
        }
    }

    // A friend code we made. ownerName is our name when we made it (part of its key).
    public record FriendCode(String code, String ownerName, long expiresAt, CompletableFuture<SecretKey> key) {
        @Nullable SecretKey readyKey() {
            return this.key.isDone() && !this.key.isCompletedExceptionally() ? this.key.join() : null;
        }

        @Override
        public String toString() {
            return "FriendCode[redacted]";
        }
    }

    // What came of an action: whether it went ahead, and what to tell the player
    public record Outcome(boolean ok, String message) {
    }

    // The channel someone last wrote in, as far as we've seen
    private record Sighting(String channel, long at) {
    }

    private static volatile Map<String, Friend> friends = Map.of();
    private static volatile List<ExtraKey> keys = List.of();
    private static final Map<String, Request> REQUESTS = new LinkedHashMap<>();
    private static final Map<String, Outgoing> OUTGOING = new LinkedHashMap<>();
    private static final Map<String, FriendCode> CODES = new LinkedHashMap<>();
    private static final Map<String, Sighting> SEEN = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Sighting> eldest) {
            return this.size() > 512;
        }
    };
    private static @Nullable Identity identity;
    // Why the identity file can't be used, if it exists but can't be read: then it's never replaced
    private static @Nullable String identityProblem;
    private static @Nullable Path configDir;
    private static long lastTooMany;
    // The server queued messages are for (see SendQueue.Item.server); null while not connected
    private static volatile @Nullable String server;
    private static boolean installed;
    private static boolean tickFailed;
    // Where friend messages show up: chat in game, a list in tests
    static Consumer<Component> notifier = Friends::chatLater;

    private Friends() {
    }

    // Called once at startup: registers local commands, control-message handlers and key sources with EncryptedChat
    public static void register() {
        install(ChatSettings.configDir());
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PrivacyPlus friends");
            thread.setDaemon(true);
            return thread;
        });
        // Minecraft may not exist yet at startup; each tick runs on the render thread. A tick that fails is
        // logged once, not four times a second.
        executor.scheduleWithFixedDelay(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gui != null) {
                mc.execute(() -> {
                    try {
                        tick(mc);
                    } catch (RuntimeException e) {
                        if (!tickFailed) {
                            tickFailed = true;
                            PrivacyPlus.LOG.error("PrivacyPlus friends timer failed", e);
                        }
                    }
                });
            }
        }, 1, 250, TimeUnit.MILLISECONDS);
    }

    // Handlers, key source and saved friends, without the timer (tests)
    static synchronized void install(@Nullable Path dir) {
        configDir = dir;
        if (!installed) {
            installed = true;
            EncryptedChat.registerControl(FriendMessages.HELLO, Friends::onHello);
            EncryptedChat.registerControl(FriendMessages.DONE, Friends::onDone);
            EncryptedChat.registerControl(FriendMessages.RENAME, Friends::onRename);
            EncryptedChat.registerKeySource(() -> keys);
            FriendChannels.register();
        }
        reload();
    }

    public static synchronized void reload() {
        identity = null;
        identityProblem = null;
        Map<String, Friend> loaded = new LinkedHashMap<>();
        Properties props = read(FRIENDS_FILE);
        Identity me = existingIdentity();
        if (me != null) {
            for (String key : props.stringPropertyNames()) {
                String publicKey = key.startsWith("friend.") ? key.substring("friend.".length()) : "";
                Friend friend = FriendCrypto.isPublicKey(publicKey) ? parseFriend(me, publicKey, props) : null;
                if (friend != null) {
                    loaded.put(publicKey, friend);
                }
            }
        }
        CODES.clear();
        long now = System.currentTimeMillis();
        for (String key : props.stringPropertyNames()) {
            String code = key.startsWith("code.") ? FriendCrypto.normalizeCode(key.substring("code.".length())) : null;
            String[] f = props.getProperty(key, "").trim().split(" ");
            if (code != null && f.length == 2 && f[0].matches("[A-Za-z0-9_]{1,16}") && f[1].matches("[0-9]{1,15}") && Long.parseLong(f[1]) > now) {
                addCode(new FriendCode(code, f[0], Long.parseLong(f[1]), CompletableFuture.supplyAsync(() -> FriendCrypto.codeKey(code, f[0]))));
            }
        }
        setFriends(loaded, false);
        // Notes to friends that were still waiting when the game closed
        props.stringPropertyNames().stream().filter(k -> k.startsWith("queued.")).sorted().forEach(k -> {
            String[] f = props.getProperty(k).split("\\|", 7);
            Friend friend = f.length == 7 ? loaded.get(f[1]) : null;
            if (friend != null && f[4].matches("[0-9]{1,15}") && Long.parseLong(f[4]) > now && FriendMessages.decode(f[3], f[6]) != null) {
                QUEUE.add(new SendQueue.Item(f[5], f[2], friend.keys().send(), f[3], f[6], Long.parseLong(f[4]), true, null,
                    f[0].isEmpty() ? null : f[0]));
            }
        });
    }

    public static List<Friend> all() {
        return friends.values().stream().sorted(Comparator.comparing(f -> f.name().toLowerCase(Locale.ROOT))).toList();
    }

    public static @Nullable Friend byKey(String publicKey) {
        return friends.get(publicKey);
    }

    // The finished friend a private message to this player goes to, if any
    public static @Nullable Friend forRecipient(String playerName) {
        return friends.values().stream().filter(f -> f.confirmed() && f.name().equalsIgnoreCase(playerName)).findFirst().orElse(null);
    }

    // Why a private message to this name must not go out as plain text although it can't be encrypted for a
    // friend: it was a friend's name, or the friend's key exchange isn't finished. Null otherwise.
    public static @Nullable String plainTextRefusal(String playerName) {
        for (Friend friend : friends.values()) {
            if (friend.name().equalsIgnoreCase(playerName) && !friend.confirmed()) {
                return "The key exchange with " + friend.name() + " isn't finished, so nothing was sent.";
            }
            if (friend.formerNames().stream().anyMatch(playerName::equalsIgnoreCase)) {
                return playerName + " is a former name of your friend " + friend.name() + "; nothing was sent. Write to " + friend.name()
                    + " instead.";
            }
        }
        return null;
    }

    // The key we send to a finished friend with, e.g. to send them something; null for anyone else
    public static @Nullable SecretKey keyFor(String playerName) {
        Friend friend = forRecipient(playerName);
        return friend == null ? null : friend.keys().send();
    }

    static @Nullable SecretKey keyOf(Friend friend) {
        Friend current = friends.get(friend.publicKey());
        return current == null || !current.confirmed() ? null : current.keys().send();
    }

    public static synchronized List<Request> requests() {
        return List.copyOf(REQUESTS.values());
    }

    public static synchronized List<Outgoing> outgoing() {
        return List.copyOf(OUTGOING.values());
    }

    // Our friend codes nobody has used yet, oldest first
    public static synchronized List<FriendCode> codes() {
        expireCodes(System.currentTimeMillis());
        return List.copyOf(CODES.values());
    }

    // Our own public key, or null if none was made yet
    public static @Nullable String publicKey() {
        Identity me = existingIdentity();
        return me == null ? null : me.publicKey();
    }

    // A message waiting to be sent automatically: a control message's verb and body (see FriendMessages), and the
    // key it will be encrypted with
    public record Pending(String recipient, String verb, String body, SecretKey key) {
        @Override
        public String toString() {
            return "Pending[" + this.verb + " to " + this.recipient + "]";
        }
    }

    // What is waiting to be sent automatically, oldest first (e.g. for the settings screen or the self-test)
    public static List<Pending> pendingMessages() {
        return QUEUE.items().stream().map(item -> new Pending(item.recipient(), item.verb(), item.body(), item.key())).toList();
    }

    public static List<String> pendingSends() {
        return pendingMessages().stream().map(p -> p.verb() + " to " + p.recipient()).toList();
    }

    // name wrote something we could read with channel's key: a channel we share with them
    static void sawInChannel(String name, Channel channel) {
        synchronized (SEEN) {
            SEEN.put(lower(name), new Sighting(channel.name(), System.currentTimeMillis()));
        }
    }

    // The channel we last saw name write in, if we still have it: a key exchange request under it reaches them
    public static @Nullable Channel sharedChannel(String name) {
        Sighting seen;
        synchronized (SEEN) {
            seen = SEEN.get(lower(name));
        }
        Channel channel = seen == null ? null : ChatSettings.get(seen.channel());
        return channel != null && channel.readyKey() != null ? channel : null;
    }

    // Step 1 through a channel: ask name to exchange keys, under through's key, or (null) under the key of the
    // channel we last saw them write in
    public static synchronized Outcome requestExchange(String name, @Nullable Channel through) {
        String problem = cantAsk(name);
        if (problem != null) {
            return new Outcome(false, problem);
        }
        Channel seen = sharedChannel(name);
        Channel channel = through != null ? through : seen;
        if (channel == null) {
            return new Outcome(false, "You haven't seen " + name + " write in any of your channels yet. Pick a channel you both have,"
                + " or use a friend code.");
        }
        SecretKey key = channel.readyKey();
        if (key == null) {
            return new Outcome(false, "The key for " + channel.name() + " is still being prepared. Try again in a moment.");
        }
        startExchange(name, key, "channel " + channel.name(), false);
        boolean known = seen != null && seen.name().equals(channel.name());
        return new Outcome(true, "Asked " + name + " through the channel " + channel.name()
            + (known ? ", where you saw them write." : ". It only reaches them if they have that channel too."));
    }

    // Step 1 with a friend code name gave us: the request goes under the code's key. Slow (the code's key is
    // made to be), so it completes later, off the render thread.
    public static CompletableFuture<Outcome> requestWithCode(String name, String typedCode) {
        String code = FriendCrypto.normalizeCode(typedCode);
        String problem = cantAsk(name);
        if (problem != null || code == null) {
            return CompletableFuture.completedFuture(new Outcome(false, problem != null ? problem
                : "That isn't a friend code. They look like 7K2MQ-X9DPA-4RW3Z: 15 letters and digits."));
        }
        return CompletableFuture.supplyAsync(() -> FriendCrypto.codeKey(code, name)).thenApply(key -> {
            synchronized (Friends.class) {
                String again = cantAsk(name);
                if (again != null) {
                    return new Outcome(false, again);
                }
                startExchange(name, key, "a friend code", true);
                return new Outcome(true, "Sent " + name + " a key exchange request with their friend code. They need to be online within 15"
                    + " minutes; their client answers by itself.");
            }
        });
    }

    // Why we can't ask name to exchange keys right now, or null
    private static synchronized @Nullable String cantAsk(String name) {
        String me = EncryptedChat.selfName();
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            return "That isn't a player name.";
        }
        if (me != null && me.equalsIgnoreCase(name)) {
            return "That's you.";
        }
        if (friends.values().stream().anyMatch(f -> f.name().equalsIgnoreCase(name))) {
            return name + " is already your friend. To exchange new keys, remove them first.";
        }
        if (OUTGOING.containsKey(lower(name))) {
            return "Already waiting for " + OUTGOING.get(lower(name)).name() + " to answer.";
        }
        if (OUTGOING.size() >= MAX_REQUESTS) {
            return "Too many requests waiting. Cancel one under Friends first.";
        }
        existingIdentity();
        return identityProblem;
    }

    private static synchronized void startExchange(String name, SecretKey via, String route, boolean code) {
        Identity mine = identity();
        byte[] nonce = FriendCrypto.randomBytes(FriendCrypto.NONCE_BYTES);
        String commitment = FriendCrypto.encode(FriendCrypto.commitment(mine.publicKey(), nonce));
        OUTGOING.put(lower(name), new Outgoing(name, nonce, commitment, System.currentTimeMillis(), via, route, code));
        rebuildKeys();
        queue("hello " + lower(name), name, via, new HelloRequest(mine.publicKey(), commitment), REQUEST_LIFETIME_MILLIS, false, null);
    }

    public static synchronized void cancelRequest(String name) {
        OUTGOING.remove(lower(name));
        QUEUE.removeIf(item -> item.id().equals("hello " + lower(name)));
        rebuildKeys();
    }

    // A new one-time friend code to give someone outside Minecraft. The Outcome's message is the code.
    public static synchronized Outcome createCode() {
        String me = EncryptedChat.selfName();
        if (me == null) {
            return new Outcome(false, "Join a world or a server first.");
        }
        existingIdentity();
        if (identityProblem != null) {
            return new Outcome(false, identityProblem);
        }
        expireCodes(System.currentTimeMillis());
        if (CODES.size() >= MAX_CODES) {
            return new Outcome(false, "You already have " + MAX_CODES + " unused friend codes. Cancel one first.");
        }
        String code = FriendCrypto.newFriendCode();
        addCode(new FriendCode(code, me, System.currentTimeMillis() + CODE_LIFETIME_MILLIS,
            CompletableFuture.supplyAsync(() -> FriendCrypto.codeKey(code, me))));
        save();
        return new Outcome(true, code);
    }

    public static synchronized void cancelCode(String code) {
        if (CODES.remove(code) != null) {
            rebuildKeys();
            save();
        }
    }

    private static synchronized void addCode(FriendCode code) {
        CODES.put(code.code(), code);
        // Its key takes a moment; it's tried on incoming text from then on
        code.key().whenComplete((key, error) -> rebuildKeys());
    }

    // Step 2: accept a request (from the chat line's [Accept] or the settings)
    public static void accept(String publicKey) {
        acceptRequest(publicKey, false);
    }

    // automatic: a friend code's holder, answered without a click
    private static synchronized void acceptRequest(String publicKey, boolean automatic) {
        Request request = REQUESTS.remove(publicKey);
        if (request == null || System.currentTimeMillis() - request.receivedAt() > REQUEST_LIFETIME_MILLIS) {
            notifier.accept(Component.literal("That key exchange request has expired. Ask them to send a new one."));
            return;
        }
        existingIdentity();
        if (identityProblem != null) {
            notifier.accept(Component.literal(identityProblem).withStyle(ChatFormatting.RED));
            return;
        }
        String me = EncryptedChat.selfName();
        Identity mine = identity();
        PairKeys pair = me == null ? null : FriendCrypto.pairwiseKeys(mine, me, request.publicKey(), request.name());
        if (pair == null) {
            notifier.accept(Component.literal("The key " + request.name() + " sent can't be used.").withStyle(ChatFormatting.RED));
            return;
        }
        byte[] nonce = FriendCrypto.randomBytes(FriendCrypto.NONCE_BYTES);
        byte[] commitment = FriendCrypto.decode(request.commitment());
        String tag = FriendCrypto.encode(FriendCrypto.replyTag(pair.tag(), commitment, nonce));
        Friend existing = friends.get(request.publicKey());
        // Asked again with the same key and names: the keys are the same, so keep using them meanwhile
        Friend friend = existing != null && existing.keys().send().equals(pair.send())
            ? existing.withPending(request.commitment(), FriendCrypto.encode(nonce))
            : new Friend(request.publicKey(), request.name(), me, request.name(), existing != null && existing.verified(), null, false,
                Instant.now().getEpochSecond(), request.commitment(), FriendCrypto.encode(nonce), pair, List.of(), me);
        putFriend(friend);
        HelloReply reply = new HelloReply(mine.publicKey(), FriendCrypto.encode(nonce), tag);
        queue("hello " + lower(request.name()), request.name(), request.via(), reply, REQUEST_LIFETIME_MILLIS, automatic, null);
        if (!automatic) {
            notifier.accept(Component.literal("Sending your key to " + request.name() + ". Waiting for them to finish the exchange..."));
        }
    }

    public static synchronized void ignore(String publicKey) {
        REQUESTS.remove(publicKey);
    }

    public static synchronized void setVerified(String publicKey, boolean verified) {
        Friend friend = friends.get(publicKey);
        if (friend != null) {
            putFriend(friend.withVerified(verified));
        }
    }

    // Takes on a friend's new name (after they told us, and the player agreed)
    static synchronized void rename(String publicKey, String newName) {
        Friend friend = friends.get(publicKey);
        if (friend != null && newName.matches("[A-Za-z0-9_]{1,16}")) {
            putFriend(friend.withName(newName));
        }
    }

    // Deletes the friend and their keys (with anything still queued for them), and takes them out of channels you run
    public static synchronized void remove(String publicKey) {
        Map<String, Friend> updated = new LinkedHashMap<>(friends);
        Friend removed = updated.remove(publicKey);
        if (removed != null) {
            QUEUE.removeIf(item -> item.key().equals(removed.keys().send()));
            setFriends(updated, true);
            FriendChannels.friendRemoved(publicKey);
        }
    }

    // Our identity, made and saved the first time it's needed. Throws if the identity file exists but can't be
    // read: it is never replaced, as that would lose every friend.
    static synchronized Identity identity() {
        Identity existing = existingIdentity();
        if (existing != null) {
            return existing;
        }
        if (identityProblem != null) {
            throw new IllegalStateException(identityProblem);
        }
        Identity made = FriendCrypto.generate();
        if (configDir != null) {
            try {
                SecretFiles.write(configDir.resolve(IDENTITY_FILE), """
                    # Your PrivacyPlus identity key. Keep it secret and don't share this file: anyone with it can read
                    # messages your friends send you. If you lose it, exchange keys with your friends again.
                    privateKey=%s
                    publicKey=%s
                    """.formatted(FriendCrypto.encode(FriendCrypto.privateBytes(made)), made.publicKey()));
            } catch (IOException e) {
                PrivacyPlus.LOG.error("Couldn't save the PrivacyPlus identity key", e);
            }
        }
        identity = made;
        return made;
    }

    private static synchronized @Nullable Identity existingIdentity() {
        Path file = configDir == null ? null : configDir.resolve(IDENTITY_FILE);
        if (identity == null && identityProblem == null && file != null && Files.exists(file)) {
            byte[] scalar = FriendCrypto.decode(read(IDENTITY_FILE).getProperty("privateKey", "").trim());
            if (scalar != null && scalar.length == FriendCrypto.KEY_BYTES) {
                identity = FriendCrypto.fromPrivate(scalar);
            } else {
                identityProblem = "Your identity key file (" + IDENTITY_FILE + ") can't be read. It was left as it is; fix or remove it, then"
                    + " use /pp reload.";
                PrivacyPlus.LOG.error("{} has no usable private key; leaving it untouched", file);
            }
        }
        return identity;
    }

    // Every quarter second on the render thread: expires requests, hands out channel keys, sends what's due
    static void tick(Minecraft mc) {
        EncryptedChat.tick(mc);
        ClientPacketListener connection = mc.getConnection();
        ServerData data = connection == null ? null : connection.getServerData();
        server = connection == null ? null : mc.isLocalServer() || data == null ? "local" : ChatSettings.serverKey(data.ip);
        long now = System.currentTimeMillis();
        expire(now);
        if (connection == null || mc.player == null) {
            return;
        }
        tellNewName();
        FriendChannels.queueDeliveries(now);
        Set<String> online = connection.getOnlinePlayers().stream().map(p -> lower(p.getProfile().name())).collect(Collectors.toSet());
        SendQueue.Item item = QUEUE.poll(now, server, name -> online.contains(lower(name)));
        if (item != null && EncryptedChat.sendControl(mc, item.key(), "msg " + item.recipient() + " ", item.verb(), item.body())) {
            if (item.onSent() != null) {
                item.onSent().run();
            }
            if (savable(item)) {
                save();
            }
        }
    }

    // Our account has a new name: each friend hears it once, under our key (they confirm it by hand)
    private static synchronized void tellNewName() {
        String me = EncryptedChat.selfName();
        if (me == null) {
            return;
        }
        for (Friend friend : friends.values()) {
            if (friend.confirmed() && !friend.toldName().equalsIgnoreCase(me) && !QUEUE.contains("rename " + friend.publicKey())) {
                String key = friend.publicKey();
                queue("rename " + key, friend.name(), friend.keys().send(), new Rename(me), FriendChannels.INVITE_LIFETIME_MILLIS, true, () -> {
                    Friend current = friends.get(key);
                    if (current != null) {
                        putFriend(current.withToldName(me));
                    }
                });
            }
        }
    }

    // The player just sent something: automatic sends wait a little longer, so the two don't add up to spam
    static void delayAutomaticSends() {
        QUEUE.delay(System.currentTimeMillis());
    }

    // automatic: nobody clicked for it, so it goes out after a random pause (see SendQueue). Goes out on the
    // server it was queued on; notes under a friend's key are also kept over a restart.
    static void queue(String id, String recipient, SecretKey key, FriendMessages.Message message, long lifetimeMillis, boolean automatic,
                      @Nullable Runnable onSent) {
        SendQueue.Item item = new SendQueue.Item(id, recipient, key, message.verb(), message.body(), System.currentTimeMillis() + lifetimeMillis,
            automatic, onSent, server);
        QUEUE.add(item);
        if (savable(item)) {
            save();
        }
    }

    // Notes to friends without an action to run once sent (those are made again from saved state anyway)
    private static boolean savable(SendQueue.Item item) {
        return item.onSent() == null && friends.values().stream().anyMatch(f -> f.keys().send().equals(item.key()));
    }

    private static synchronized void expire(long now) {
        REQUESTS.values().removeIf(r -> now - r.receivedAt() > REQUEST_LIFETIME_MILLIS);
        if (OUTGOING.values().removeIf(o -> now - o.sentAt() > REQUEST_LIFETIME_MILLIS)) {
            rebuildKeys();
        }
        expireCodes(now);
    }

    private static synchronized void expireCodes(long now) {
        if (CODES.values().removeIf(c -> c.expiresAt() <= now)) {
            rebuildKeys();
            save();
        }
    }

    // The key a hello came under, if it's one a hello may use: a channel's current key, one of our friend codes,
    // or the friend code of a request we sent. Never a friend's key or an old channel key.
    private static @Nullable SecretKey helloKey(Control control) {
        if (control.channel() != null) {
            return control.oldKey() ? null : control.channel().readyKey();
        }
        Object owner = control.extraKey() == null ? null : control.extraKey().owner();
        return owner instanceof FriendCode || owner instanceof Outgoing ? control.extraKey().key() : null;
    }

    // "hello": a key exchange request or reply
    private static synchronized Component onHello(Control control, Style style) {
        FriendMessages.Message message = FriendMessages.decode(control.verb(), control.body());
        boolean reply = message instanceof HelloReply;
        if (control.mine()) {
            return note(reply ? "[your key exchange reply]" : "[your key exchange request]");
        }
        SecretKey via = helloKey(control);
        if (via == null || message == null) {
            return note("[a key exchange message that doesn't fit: ignored]");
        }
        if (control.writer() == null) {
            return warning("[a key exchange message whose sender doesn't match this line: ignored]");
        }
        if (control.stale()) {
            return note("[an old key exchange message: ignored]");
        }
        Identity mine = existingIdentity();
        String publicKey = reply ? ((HelloReply) message).publicKey() : ((HelloRequest) message).publicKey();
        if (mine != null && mine.publicKey().equals(publicKey)) {
            return note("[a key exchange message with your own key: ignored]");
        }
        Friend sameName = friends.values().stream().filter(f -> f.name().equalsIgnoreCase(control.writer())).findFirst().orElse(null);
        if (sameName != null && !sameName.publicKey().equals(publicKey)) {
            return keyChangedWarning(control.writer());
        }
        if (reply) {
            return onReply(control.writer(), (HelloReply) message, via);
        }
        Object owner = control.extraKey() == null ? null : control.extraKey().owner();
        if (owner instanceof FriendCode code) {
            return onCodeRequest(control.writer(), (HelloRequest) message, code);
        }
        if (owner instanceof Outgoing) {
            return note("[a key exchange message that doesn't fit: ignored]");
        }
        return onRequest(control.writer(), (HelloRequest) message, via, "channel " + control.channel().name());
    }

    private static Component onRequest(String name, HelloRequest request, SecretKey via, String route) {
        long now = System.currentTimeMillis();
        Outgoing ours = OUTGOING.get(lower(name));
        if (ours != null) {
            // Both asked at once. The side with the lower key keeps its own request; the other answers it.
            Identity mine = identity();
            if (Arrays.compareUnsigned(FriendCrypto.decode(mine.publicKey()), FriendCrypto.decode(request.publicKey())) < 0) {
                return note("[" + name + " asked at the same time as you: waiting for them to answer your request]");
            }
            cancelRequest(name);
            REQUESTS.put(request.publicKey(), new Request(name, request.publicKey(), request.commitment(), now, via, route));
            acceptRequest(request.publicKey(), false);
            return note("[" + name + " asked to exchange keys too: accepted]");
        }
        if (!REQUESTS.containsKey(request.publicKey()) && REQUESTS.size() >= MAX_REQUESTS) {
            if (now - lastTooMany < 60_000) {
                return note("[ignored]");
            }
            lastTooMany = now;
            return note("[a key exchange request from " + name + ": ignored, too many are waiting. See settings > Friends]");
        }
        REQUESTS.remove(request.publicKey());
        REQUESTS.put(request.publicKey(), new Request(name, request.publicKey(), request.commitment(), now, via, route));
        Friend known = friends.get(request.publicKey());
        MutableComponent text = Component.literal(name + " wants to exchange keys with you").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(" (through " + route + ")").withStyle(ChatFormatting.GRAY));
        if (known != null) {
            text.append(Component.literal(known.name().equalsIgnoreCase(name) ? " (again)" : " (with the key of your friend " + known.name() + ")")
                .withStyle(ChatFormatting.GRAY));
        }
        return text.append(" ")
            .append(button("[Accept]", ChatFormatting.GREEN, "Exchange keys with " + name, () -> accept(request.publicKey())))
            .append(" ")
            .append(button("[Ignore]", ChatFormatting.GRAY, "Do nothing", () -> {
                ignore(request.publicKey());
                notifier.accept(Component.literal("Ignored " + name + "'s request."));
            }));
    }

    // A request under one of our friend codes: whoever holds the code gets an answer without a click, once
    private static Component onCodeRequest(String name, HelloRequest request, FriendCode code) {
        if (CODES.remove(code.code()) == null) {
            return note("[a friend code that was already used: ignored]");
        }
        rebuildKeys();
        save();
        if (OUTGOING.containsKey(lower(name))) {
            cancelRequest(name);
        }
        REQUESTS.put(request.publicKey(), new Request(name, request.publicKey(), request.commitment(), System.currentTimeMillis(),
            code.readyKey(), "your friend code"));
        acceptRequest(request.publicKey(), true);
        return Component.literal(name + " used your friend code " + code.code() + ". Exchanging keys...").withStyle(ChatFormatting.YELLOW);
    }

    private static Component onReply(String name, HelloReply reply, SecretKey via) {
        Outgoing ours = OUTGOING.get(lower(name));
        if (ours == null || !ours.via().equals(via)) {
            return note("[a key exchange reply from " + name + " you didn't ask for: ignored]");
        }
        String me = EncryptedChat.selfName();
        Identity mine = identity();
        PairKeys pair = me == null ? null : FriendCrypto.pairwiseKeys(mine, me, reply.publicKey(), name);
        if (pair == null) {
            return warning("[" + name + " sent a key that can't be used: ignored]");
        }
        byte[] expected = FriendCrypto.replyTag(pair.tag(), FriendCrypto.decode(ours.commitment()), FriendCrypto.decode(reply.nonce()));
        if (!FriendCrypto.sameBytes(expected, FriendCrypto.decode(reply.tag()))) {
            return warning("[a key exchange reply from " + name + " that doesn't check out: ignored]");
        }
        OUTGOING.remove(lower(name));
        String code = FriendCrypto.safetyCode(mine.publicKey(), ours.nonce(), reply.publicKey(), FriendCrypto.decode(reply.nonce()));
        Friend existing = friends.get(reply.publicKey());
        Friend friend = new Friend(reply.publicKey(), name, me, name, existing != null && existing.verified(), code, true,
            Instant.now().getEpochSecond(), null, null, pair, List.of(), me);
        putFriend(friend);
        queue("done " + lower(name), name, pair.send(), new Done(FriendCrypto.encode(ours.nonce())), REQUEST_LIFETIME_MILLIS, true, null);
        return finished(friend, "accepted your key exchange");
    }

    // "friend-ok": step 3, under the friend's send key (our receive key)
    private static synchronized Component onDone(Control control, Style style) {
        if (control.mine()) {
            return note("[key exchange finished]");
        }
        Friend friend = sender(control);
        FriendMessages.Message message = FriendMessages.decode(control.verb(), control.body());
        if (friend == null || !(message instanceof Done done) || control.stale()) {
            return note("[a key exchange message that doesn't fit: ignored]");
        }
        if (friend.pendingCommitment() == null || friend.pendingNonce() == null) {
            return note("[key exchange with " + friend.name() + " already finished]");
        }
        byte[] theirNonce = FriendCrypto.decode(done.nonce());
        if (!FriendCrypto.sameBytes(FriendCrypto.commitment(friend.publicKey(), theirNonce), FriendCrypto.decode(friend.pendingCommitment()))) {
            return warning("[the key exchange with " + friend.name() + " doesn't check out. Remove them under Friends and try again]");
        }
        Identity mine = identity();
        String code = FriendCrypto.safetyCode(friend.publicKey(), theirNonce, mine.publicKey(), FriendCrypto.decode(friend.pendingNonce()));
        Friend finished = friend.confirmedWith(code);
        putFriend(finished);
        return finished(finished, "finished the key exchange");
    }

    // "rename": a friend says their account has a new name. Taken on only when the player agrees.
    private static synchronized Component onRename(Control control, Style style) {
        FriendMessages.Message message = FriendMessages.decode(control.verb(), control.body());
        if (control.mine()) {
            return note("[your new name, for your friend]");
        }
        Friend friend = sender(control);
        if (friend == null || !(message instanceof Rename rename) || control.stale()) {
            return note("[a name change that doesn't fit: ignored]");
        }
        if (rename.name().equalsIgnoreCase(friend.name())) {
            return note("[" + friend.name() + " confirmed their name]");
        }
        String key = friend.publicKey();
        return Component.literal("Your friend " + friend.name() + " says they're now called " + rename.name() + ". ").withStyle(ChatFormatting.YELLOW)
            .append(button("[Use the new name]", ChatFormatting.GREEN, "Private messages to " + rename.name() + " go to them encrypted", () -> {
                rename(key, rename.name());
                notifier.accept(Component.literal("Your friend is now listed as " + rename.name() + "."));
            }))
            .append(" ")
            .append(button("[Ignore]", ChatFormatting.GRAY, "Keep " + friend.name(), () -> { }));
    }

    // The friend whose key opened this control message (their send key), if any
    static @Nullable Friend sender(Control control) {
        return control.extraKey() != null && control.extraKey().owner() instanceof Friend f ? friends.get(f.publicKey()) : null;
    }

    // "<name> accepted... Safety code: 1234 5678 9012 3456 [Mark verified]"
    private static Component finished(Friend friend, String what) {
        return Component.literal(friend.name() + " " + what + ". ").withStyle(ChatFormatting.GREEN)
            .append(Component.literal("Safety code: " + friend.safetyCode()).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(". Compare it with " + friend.name() + " outside Minecraft (voice, Signal, in person). If it matches, ")
                .withStyle(ChatFormatting.GRAY))
            .append(button("[Mark verified]", ChatFormatting.GREEN, "The codes match", () -> {
                setVerified(friend.publicKey(), true);
                notifier.accept(Component.literal(friend.name() + " is verified."));
            }));
    }

    private static Component keyChangedWarning(String name) {
        return Component.literal("WARNING: " + name + " sent a different key from the one you exchanged before. Someone, possibly the"
            + " server, may be trying to read your messages. If " + name + " really reset their key, remove them under Friends and exchange"
            + " keys again; otherwise ignore this.").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
    }

    static Component button(String label, ChatFormatting color, String hover, Runnable action) {
        return Component.literal(label).setStyle(Style.EMPTY.withColor(color)
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover)))
            .withClickEvent(EncryptedChat.localClick(action)));
    }

    static Component note(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    }

    static Component warning(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GOLD);
    }

    // Shown once the current chat message is done, so it lands below the line that caused it
    private static void chatLater(Component text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> EncryptedChat.feedback(mc, text));
        }
    }

    private static synchronized void putFriend(Friend friend) {
        Map<String, Friend> updated = new LinkedHashMap<>(friends);
        updated.put(friend.publicKey(), friend);
        setFriends(updated, true);
    }

    private static synchronized void setFriends(Map<String, Friend> updated, boolean save) {
        friends = Map.copyOf(updated);
        rebuildKeys();
        if (save) {
            save();
        }
    }

    // The keys EncryptedChat tries besides the channels': each friend's send key (opens what they wrote) and our
    // send key for them (opens our own messages echoed back), our friend codes', and those of friend codes we sent
    // a request with and await the reply to
    private static synchronized void rebuildKeys() {
        List<ExtraKey> extra = new ArrayList<>();
        all().forEach(f -> {
            extra.add(new ExtraKey("your friend key with " + f.name(), f.keys().receive(), f));
            extra.add(new ExtraKey("your friend key with " + f.name() + " (sent by you)", f.keys().send(), new OwnKey(f)));
        });
        CODES.values().forEach(c -> {
            SecretKey key = c.readyKey();
            if (key != null) {
                extra.add(new ExtraKey("your friend code " + c.code(), key, c));
            }
        });
        OUTGOING.values().stream().filter(Outgoing::code).forEach(o -> extra.add(new ExtraKey("the friend code from " + o.name(), o.via(), o)));
        keys = List.copyOf(extra);
    }

    private static synchronized void save() {
        if (configDir == null) {
            return;
        }
        StringBuilder out = new StringBuilder("""
            # PrivacyPlus friends: players you exchanged keys with, by their public key. Easiest to change in game:
            # right-click the lock next to the chat box, then Friends.
            # friend.<key>=<name> <your name then> <their name then> <verified> <finished> <added>
            # .code is the safety code to compare; .pending is kept until the exchange is finished; .former lists their
            # earlier names; .told is the name of yours they last heard.
            # code.<friend code>=<your name> <expires>: friend codes you made that nobody has used yet. Keep them secret.
            # queued.<n>: notes to friends waiting to be sent.

            """);
        for (Friend f : all()) {
            String prefix = "friend." + f.publicKey();
            out.append(prefix).append('=').append(String.join(" ", f.name(), f.myName(), f.theirName(), String.valueOf(f.verified()),
                String.valueOf(f.confirmed()), String.valueOf(f.addedAt()))).append('\n');
            if (f.safetyCode() != null) {
                out.append(prefix).append(".code=").append(f.safetyCode()).append('\n');
            }
            if (f.pendingCommitment() != null && f.pendingNonce() != null) {
                out.append(prefix).append(".pending=").append(f.pendingCommitment()).append(' ').append(f.pendingNonce()).append('\n');
            }
            if (!f.formerNames().isEmpty()) {
                out.append(prefix).append(".former=").append(String.join(" ", f.formerNames())).append('\n');
            }
            out.append(prefix).append(".told=").append(f.toldName()).append('\n');
        }
        CODES.values().forEach(c -> out.append("code.").append(c.code()).append('=').append(c.ownerName()).append(' ').append(c.expiresAt())
            .append('\n'));
        List<SendQueue.Item> saved = QUEUE.items().stream().filter(Friends::savable).limit(MAX_SAVED_SENDS).toList();
        for (int i = 0; i < saved.size(); i++) {
            SendQueue.Item item = saved.get(i);
            Friend to = friends.values().stream().filter(f -> f.keys().send().equals(item.key())).findFirst().orElseThrow();
            out.append(String.format(Locale.ROOT, "queued.%03d=", i)).append(String.join("|", item.server() == null ? "" : item.server(),
                to.publicKey(), item.recipient(), item.verb(), String.valueOf(item.expiresAt()), item.id(), item.body())).append('\n');
        }
        try {
            SecretFiles.write(configDir.resolve(FRIENDS_FILE), out.toString());
        } catch (IOException e) {
            PrivacyPlus.LOG.error("Couldn't save the PrivacyPlus friend list", e);
        }
    }

    private static @Nullable Friend parseFriend(Identity me, String publicKey, Properties props) {
        String[] f = props.getProperty("friend." + publicKey, "").trim().split(" ");
        if (f.length != 6) {
            return null;
        }
        PairKeys pair = FriendCrypto.pairwiseKeys(me, f[1], publicKey, f[2]);
        if (pair == null) {
            return null;
        }
        String code = props.getProperty("friend." + publicKey + ".code", "").trim();
        String[] pending = props.getProperty("friend." + publicKey + ".pending", "").trim().split(" ");
        List<String> former = Stream.of(props.getProperty("friend." + publicKey + ".former", "").trim().split(" "))
            .filter(n -> n.matches("[A-Za-z0-9_]{1,16}")).toList();
        String told = props.getProperty("friend." + publicKey + ".told", f[1]).trim();
        try {
            return new Friend(publicKey, f[0], f[1], f[2], Boolean.parseBoolean(f[3]), code.isEmpty() ? null : code, Boolean.parseBoolean(f[4]),
                Long.parseLong(f[5]), pending.length == 2 ? pending[0] : null, pending.length == 2 ? pending[1] : null, pair, former,
                told.isEmpty() ? f[1] : told);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Properties read(String fileName) {
        Properties props = new Properties();
        Path file = configDir == null ? null : configDir.resolve(fileName);
        if (file != null && Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException | IllegalArgumentException e) {
                PrivacyPlus.LOG.error("Couldn't read {}", file, e);
            }
        }
        return props;
    }

    static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    // For tests: forget everything in memory (the files stay)
    static synchronized void resetForTests() {
        REQUESTS.clear();
        OUTGOING.clear();
        CODES.clear();
        synchronized (SEEN) {
            SEEN.clear();
        }
        QUEUE.removeIf(item -> true);
        identity = null;
        identityProblem = null;
        server = null;
        friends = Map.of();
        keys = List.of();
    }
}
