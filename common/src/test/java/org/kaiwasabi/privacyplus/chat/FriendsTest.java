package org.kaiwasabi.privacyplus.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.crypto.SecretKey;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.kaiwasabi.privacyplus.chat.ChatSettings.MemberState;
import org.kaiwasabi.privacyplus.chat.FriendCrypto.Identity;
import org.kaiwasabi.privacyplus.chat.FriendCrypto.PairKeys;
import org.kaiwasabi.privacyplus.chat.FriendMessages.ChannelNote;
import org.kaiwasabi.privacyplus.chat.FriendMessages.Done;
import org.kaiwasabi.privacyplus.chat.FriendMessages.HelloReply;
import org.kaiwasabi.privacyplus.chat.FriendMessages.HelloRequest;
import org.kaiwasabi.privacyplus.chat.FriendMessages.Invite;
import org.kaiwasabi.privacyplus.chat.FriendMessages.Message;
import org.kaiwasabi.privacyplus.chat.FriendMessages.Rekey;
import org.kaiwasabi.privacyplus.chat.FriendMessages.Rename;
import org.kaiwasabi.privacyplus.chat.Friends.Friend;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Key exchanges and friend channels end to end: we are "Me"; the other players are simulated here with
// their own identities, and their messages go through the same decryption as chat lines in game, sent by
// the player whose name starts the line (as the game knows from player chat packets)
class FriendsTest {
    private static final String LOCK = EncryptedChat.LOCK + " ";
    // A channel we share with the simulated players, and one we don't
    private static final SecretKey TOWN = FriendChannels.newKey();
    private static final SecretKey OTHER = FriendChannels.newKey();
    private final List<Component> notes = new ArrayList<>();
    private Path dir;

    private record Peer(String name, Identity identity) {
        Peer(String name) {
            this(name, FriendCrypto.generate());
        }

        String key() {
            return this.identity.publicKey();
        }

        String token(SecretKey key, String text, Instant at) {
            return ChatCrypto.encrypt(key, this.name, text, 256, at).getFirst();
        }

        String control(SecretKey key, Message message) {
            return this.token(key, EncryptedChat.CONTROL + message.verb() + " " + message.body(), Instant.now());
        }

        // The keys this player shares with us, as they see them
        PairKeys keysWith(String ourKey) {
            return FriendCrypto.pairwiseKeys(this.identity, this.name, ourKey, "Me");
        }
    }

    @BeforeEach
    void setUp(@TempDir Path dir) {
        this.dir = dir;
        ChatSettings.load(dir);
        Friends.resetForTests();
        FriendChannels.resetForTests();
        Friends.install(dir);
        Friends.notifier = this.notes::add;
        EncryptedChat.setSelfName("Me");
        ChatSettings.put("town", TOWN);
    }

    @AfterAll
    static void tearDown() {
        Friends.resetForTests();
        FriendChannels.resetForTests();
        EncryptedChat.setSelfName(null);
    }

    private static Component whisper(String from, String token) {
        return Component.literal(from + " whispers to you: " + token);
    }

    // As a player chat message from the player whose name starts the line ("<Bob> ..." or "Bob whispers ...")
    private static Component show(Component line) {
        String text = line.getString();
        String sender = text.startsWith("<") ? text.substring(1, text.indexOf('>')) : text.substring(0, text.indexOf(' '));
        return EncryptedChat.decryptForDisplay(line, sender);
    }

    // Clicks the [label] button on a shown line, as ChatScreenMixin would
    private static void click(Component shown, String label) {
        Optional<Style> style = shown.visit((s, text) -> text.equals(label) ? Optional.of(s) : Optional.empty(), Style.EMPTY);
        assertTrue(style.isPresent(), "no " + label + " on: " + shown.getString());
        assertTrue(EncryptedChat.runLocalClick(null, style.get()));
    }

    // The one queued message to recipient with this verb, decoded
    private static Message queued(String recipient, String verb) {
        SendQueue.Item item = queuedItem(recipient, verb);
        return FriendMessages.decode(verb, item.body());
    }

    // The single queued item to recipient with this verb
    private static SendQueue.Item queuedItem(String recipient, String verb) {
        List<SendQueue.Item> items = Friends.QUEUE.items().stream().filter(i -> i.recipient().equals(recipient) && i.verb().equals(verb)).toList();
        assertEquals(1, items.size(), verb + " to " + recipient + " in " + Friends.pendingSends());
        return items.getFirst();
    }

    // Peer asks through the channel we share, we accept, peer finishes: returns the keys as the peer has them
    private PairKeys befriend(Peer peer) {
        byte[] nonce = FriendCrypto.randomBytes(FriendCrypto.NONCE_BYTES);
        byte[] commitment = FriendCrypto.commitment(peer.key(), nonce);
        String hello = peer.control(TOWN, new HelloRequest(peer.key(), FriendCrypto.encode(commitment)));
        Component shown = show(whisper(peer.name(), hello));
        click(shown, "[Accept]");
        HelloReply reply = (HelloReply) queued(peer.name(), FriendMessages.HELLO);
        PairKeys keys = peer.keysWith(reply.publicKey());
        assertArrayEquals(FriendCrypto.decode(reply.tag()), FriendCrypto.replyTag(keys.tag(), commitment, FriendCrypto.decode(reply.nonce())));
        Friends.QUEUE.removeIf(item -> true);
        show(whisper(peer.name(), peer.control(keys.send(), new Done(FriendCrypto.encode(nonce)))));
        assertTrue(Friends.byKey(peer.key()).confirmed());
        return keys;
    }

    @Test
    void acceptingARequestThroughASharedChannel() {
        Peer alice = new Peer("Alice");
        byte[] nonce = FriendCrypto.randomBytes(FriendCrypto.NONCE_BYTES);
        byte[] commitment = FriendCrypto.commitment(alice.key(), nonce);
        String hello = alice.control(TOWN, new HelloRequest(alice.key(), FriendCrypto.encode(commitment)));
        Component shown = show(whisper("Alice", hello));
        assertTrue(shown.getString().contains("Alice wants to exchange keys with you (through channel town) [Accept] [Ignore]"), shown.getString());
        assertEquals(1, Friends.requests().size());
        assertNull(Friends.publicKey(), "no identity made just for receiving a request");

        click(shown, "[Accept]");
        SendQueue.Item item = queuedItem("Alice", FriendMessages.HELLO);
        assertEquals(TOWN, item.key(), "the reply goes back under the channel's key");
        assertFalse(item.automatic(), "clicked for, so it goes right away");
        HelloReply reply = (HelloReply) FriendMessages.decode(item.verb(), item.body());
        assertEquals(Friends.publicKey(), reply.publicKey());
        PairKeys aliceKeys = alice.keysWith(reply.publicKey());
        assertArrayEquals(FriendCrypto.decode(reply.tag()), FriendCrypto.replyTag(aliceKeys.tag(), commitment, FriendCrypto.decode(reply.nonce())),
            "the tag proves we hold our key");
        Friend pending = Friends.byKey(alice.key());
        assertArrayEquals(aliceKeys.send().getEncoded(), pending.keys().receive().getEncoded(), "both sides have the same keys");
        assertArrayEquals(aliceKeys.receive().getEncoded(), pending.keys().send().getEncoded());
        assertFalse(pending.confirmed());
        assertNull(EncryptedChat.route("/msg Alice hi"), "not used to send before Alice finishes");

        Component done = show(whisper("Alice", alice.control(aliceKeys.send(), new Done(FriendCrypto.encode(nonce)))));
        String code = FriendCrypto.safetyCode(alice.key(), nonce, reply.publicKey(), FriendCrypto.decode(reply.nonce()));
        assertTrue(done.getString().contains("Alice finished the key exchange. Safety code: " + code), done.getString());
        Friend friend = Friends.byKey(alice.key());
        assertTrue(friend.confirmed());
        assertFalse(friend.verified());
        assertEquals(code, friend.safetyCode());
        assertFalse(friend.toString().contains("SecretKeySpec"), "no keys in logs: " + friend);

        assertEquals(LOCK + "Alice whispers to you: see you at the base",
            show(whisper("Alice", alice.token(aliceKeys.send(), "see you at the base", Instant.now()))).getString());
        EncryptedChat.Route route = EncryptedChat.route("/msg alice meet me");
        assertEquals(friend.publicKey(), route.friend().publicKey(), "private messages to Alice use her key, even with the lock off");
        assertEquals("msg alice ", route.commandPrefix());
        assertNull(EncryptedChat.route("/msg Bob meet me"), "not a friend, lock off: sent as it is");
        assertNull(EncryptedChat.route("/r meet me"), "reply doesn't name anyone");

        click(done, "[Mark verified]");
        assertTrue(Friends.byKey(alice.key()).verified());
        Friends.reload();
        Friend reloaded = Friends.byKey(alice.key());
        assertArrayEquals(aliceKeys.send().getEncoded(), reloaded.keys().receive().getEncoded(), "saved and derived again from the stored names");
        assertTrue(reloaded.verified());
        assertEquals(code, reloaded.safetyCode());

        Friends.remove(alice.key());
        assertNull(Friends.byKey(alice.key()));
        assertEquals("Alice whispers to you: ", show(whisper("Alice", alice.token(aliceKeys.send(), "x", Instant.now()))).getString()
            .substring(0, "Alice whispers to you: ".length()), "her key is gone");
    }

    @Test
    void sendingARequestThroughTheChannelWeSawThemIn() {
        Peer bob = new Peer("Bob");
        assertFalse(Friends.requestExchange("Bob", null).ok(), "no channel known to share with Bob");
        assertEquals(LOCK + "<Bob> hi all", show(Component.literal("<Bob> " + bob.token(TOWN, "hi all", Instant.now()))).getString());
        assertEquals("town", Friends.sharedChannel("bob").name(), "seen writing in town");

        Friends.Outcome outcome = Friends.requestExchange("Bob", null);
        assertTrue(outcome.ok(), outcome.message());
        assertTrue(outcome.message().contains("town, where you saw them write"), outcome.message());
        assertEquals("Already waiting for Bob to answer.", Friends.requestExchange("bob", null).message());
        assertEquals("That's you.", Friends.requestExchange("me", null).message());
        SendQueue.Item item = queuedItem("Bob", FriendMessages.HELLO);
        assertEquals(TOWN, item.key(), "the request goes under town's key");
        HelloRequest request = (HelloRequest) FriendMessages.decode(item.verb(), item.body());
        assertEquals(Friends.publicKey(), request.publicKey());

        PairKeys bobKeys = bob.keysWith(request.publicKey());
        byte[] nonce = FriendCrypto.randomBytes(FriendCrypto.NONCE_BYTES);
        byte[] tag = FriendCrypto.replyTag(bobKeys.tag(), FriendCrypto.decode(request.commitment()), nonce);
        HelloReply reply = new HelloReply(bob.key(), FriendCrypto.encode(nonce), FriendCrypto.encode(tag));
        Friends.QUEUE.removeIf(i -> true);
        ChatSettings.put("guild", OTHER);
        String wrongKey = show(whisper("Bob", bob.control(OTHER, reply))).getString();
        assertTrue(wrongKey.contains("didn't ask for"), "a reply under another key than the request's: " + wrongKey);

        Component shown = show(whisper("Bob", bob.control(TOWN, reply)));
        assertTrue(shown.getString().contains("Bob accepted your key exchange"), shown.getString());
        Friend friend = Friends.byKey(bob.key());
        assertTrue(friend.confirmed());
        assertArrayEquals(bobKeys.send().getEncoded(), friend.keys().receive().getEncoded());

        SendQueue.Item done = queuedItem("Bob", FriendMessages.DONE);
        assertTrue(done.automatic(), "nobody clicked for it: it waits a random while");
        assertEquals(friend.keys().send(), done.key(), "under our send key");
        byte[] ourNonce = FriendCrypto.decode(((Done) FriendMessages.decode(done.verb(), done.body())).nonce());
        assertArrayEquals(FriendCrypto.decode(request.commitment()), FriendCrypto.commitment(request.publicKey(), ourNonce),
            "the nonce we reveal is the one we committed to");
        assertEquals(FriendCrypto.safetyCode(bob.key(), nonce, request.publicKey(), ourNonce), friend.safetyCode(), "Bob sees the same code");
        assertTrue(Friends.outgoing().isEmpty());
    }

    @Test
    void askingThroughAChannelWePick() {
        ChatSettings.put("guild", OTHER);
        Friends.Outcome outcome = Friends.requestExchange("Carol", ChatSettings.get("guild"));
        assertTrue(outcome.ok());
        assertTrue(outcome.message().contains("only reaches them if they have that channel too"), outcome.message());
        assertEquals(OTHER, queuedItem("Carol", FriendMessages.HELLO).key());
        assertEquals("channel guild", Friends.outgoing().getFirst().route());
    }

    @Test
    void usingSomeonesFriendCode() {
        Peer carol = new Peer("Carol");
        String code = "7K2MQ-X9DPA-4RW3Z";
        assertFalse(Friends.requestWithCode("Carol", "not a code").join().ok());
        Friends.Outcome outcome = Friends.requestWithCode("Carol", "7k2mq x9dpa 4rw3z").join();
        assertTrue(outcome.ok(), outcome.message());
        SecretKey codeKey = FriendCrypto.codeKey(code, "Carol");
        SendQueue.Item item = queuedItem("Carol", FriendMessages.HELLO);
        assertEquals(codeKey, item.key(), "the request goes under a key only the code gives");
        HelloRequest request = (HelloRequest) FriendMessages.decode(item.verb(), item.body());

        // Carol's client answers under the same key
        PairKeys carolKeys = carol.keysWith(request.publicKey());
        byte[] nonce = FriendCrypto.randomBytes(FriendCrypto.NONCE_BYTES);
        byte[] tag = FriendCrypto.replyTag(carolKeys.tag(), FriendCrypto.decode(request.commitment()), nonce);
        Friends.QUEUE.removeIf(i -> true);
        Component shown = show(whisper("Carol", carol.control(codeKey,
            new HelloReply(carol.key(), FriendCrypto.encode(nonce), FriendCrypto.encode(tag)))));
        assertTrue(shown.getString().contains("Carol accepted your key exchange"), shown.getString());
        assertArrayEquals(carolKeys.send().getEncoded(), Friends.byKey(carol.key()).keys().receive().getEncoded());
        queuedItem("Carol", FriendMessages.DONE);
    }

    @Test
    void someoneUsingOurFriendCode() {
        Friends.Outcome made = Friends.createCode();
        assertTrue(made.ok(), made.message());
        String code = made.message();
        Friends.codes().getFirst().key().join();
        assertEquals(List.of(code), Friends.codes().stream().map(Friends.FriendCode::code).toList());
        assertFalse(Friends.codes().getFirst().toString().contains(code), "codes stay out of logs");

        Peer dave = new Peer("Dave");
        SecretKey codeKey = FriendCrypto.codeKey(code, "Me");
        byte[] nonce = FriendCrypto.randomBytes(FriendCrypto.NONCE_BYTES);
        byte[] commitment = FriendCrypto.commitment(dave.key(), nonce);
        Component shown = show(whisper("Dave", dave.control(codeKey, new HelloRequest(dave.key(), FriendCrypto.encode(commitment)))));
        assertTrue(shown.getString().contains("Dave used your friend code " + code), shown.getString());
        assertTrue(Friends.codes().isEmpty(), "a code works once");

        SendQueue.Item item = queuedItem("Dave", FriendMessages.HELLO);
        assertEquals(codeKey, item.key());
        assertTrue(item.automatic(), "answered without a click, after a random while");
        HelloReply reply = (HelloReply) FriendMessages.decode(item.verb(), item.body());
        PairKeys daveKeys = dave.keysWith(reply.publicKey());
        assertArrayEquals(FriendCrypto.decode(reply.tag()), FriendCrypto.replyTag(daveKeys.tag(), commitment, FriendCrypto.decode(reply.nonce())));
        String again = dave.control(codeKey, new HelloRequest(dave.key(), FriendCrypto.encode(commitment)));
        assertEquals("Dave whispers to you: " + again, show(whisper("Dave", again)).getString(), "used up: its key is gone");

        String done = show(whisper("Dave", dave.control(daveKeys.send(), new Done(FriendCrypto.encode(nonce))))).getString();
        assertTrue(done.contains("Dave finished the key exchange. Safety code: "
            + FriendCrypto.safetyCode(dave.key(), nonce, reply.publicKey(), FriendCrypto.decode(reply.nonce()))), done);
    }

    @Test
    void friendCodesAreSavedAndExpire() throws Exception {
        String code = Friends.createCode().message();
        Friends.reload();
        assertEquals(List.of(code), Friends.codes().stream().map(Friends.FriendCode::code).toList(), "kept over a restart");
        assertEquals(FriendCrypto.codeKey(code, "Me"), Friends.codes().getFirst().key().join());
        String saved = Files.readString(this.dir.resolve("privacyplus-friends.properties"));
        Files.writeString(this.dir.resolve("privacyplus-friends.properties"), saved + "code.AAAAA-BBBBB-CCCCC=Me 1000\n");
        Friends.reload();
        assertEquals(List.of(code), Friends.codes().stream().map(Friends.FriendCode::code).toList(), "an expired code is dropped");
        Friends.cancelCode(code);
        assertTrue(Friends.codes().isEmpty());
        for (int i = 0; i < Friends.MAX_CODES; i++) {
            assertTrue(Friends.createCode().ok());
        }
        assertFalse(Friends.createCode().ok(), "a few at a time");
    }

    @Test
    void suspiciousKeyExchangeMessagesAreIgnored() {
        Peer alice = new Peer("Alice");
        PairKeys aliceKeys = befriend(alice);
        Peer impostor = new Peer("Alice");
        String commitment = FriendCrypto.encode(FriendCrypto.randomBytes(16));
        String warning = show(whisper("Alice", impostor.control(TOWN, new HelloRequest(impostor.key(), commitment)))).getString();
        assertTrue(warning.contains("WARNING: Alice sent a different key"), warning);
        assertTrue(Friends.requests().isEmpty());

        Peer carol = new Peer("Carol");
        String underFriendKey = show(whisper("Alice", alice.control(aliceKeys.send(), new HelloRequest(carol.key(), commitment)))).getString();
        assertTrue(underFriendKey.contains("doesn't fit"), "hellos never travel under a friend's key: " + underFriendKey);
        String hello = carol.control(TOWN, new HelloRequest(carol.key(), commitment));
        assertTrue(show(whisper("Mallory", hello)).getString().contains("sender doesn't match"), "shown under someone else's name");
        String old = carol.token(TOWN, EncryptedChat.CONTROL + "hello " + new HelloRequest(carol.key(), commitment).body(),
            Instant.now().minus(Duration.ofHours(1)));
        assertTrue(show(whisper("Carol", old)).getString().contains("old key exchange message"));
        String nonce = FriendCrypto.encode(FriendCrypto.randomBytes(16));
        assertTrue(show(whisper("Carol", carol.control(TOWN, new HelloReply(carol.key(), nonce, commitment)))).getString()
            .contains("didn't ask for"), "an unsolicited reply");
        assertTrue(Friends.requests().isEmpty());

        assertTrue(Friends.requestExchange("Dave", ChatSettings.get("town")).ok());
        Peer dave = new Peer("Dave");
        String forged = show(whisper("Dave", dave.control(TOWN, new HelloReply(dave.key(), nonce, commitment)))).getString();
        assertTrue(forged.contains("doesn't check out"), "a reply with a tag not made with the key: " + forged);
        assertNull(Friends.byKey(dave.key()));

        for (int i = 0; i < Friends.MAX_REQUESTS; i++) {
            Peer someone = new Peer("Player" + i);
            show(whisper(someone.name(), someone.control(TOWN, new HelloRequest(someone.key(), commitment))));
        }
        Peer oneTooMany = new Peer("Spammer");
        assertTrue(show(whisper("Spammer", oneTooMany.control(TOWN, new HelloRequest(oneTooMany.key(), commitment)))).getString()
            .contains("too many"));
        assertEquals(Friends.MAX_REQUESTS, Friends.requests().size());
    }

    @Test
    void withoutTheChannelOrCodeAHelloIsJustUnreadableText() {
        Peer alice = new Peer("Alice");
        String hello = alice.control(OTHER, new HelloRequest(alice.key(), FriendCrypto.encode(FriendCrypto.randomBytes(16))));
        Component line = whisper("Alice", hello);
        assertSame(line, show(line), "no key opens it: nothing marks it as PrivacyPlus");
        assertTrue(Friends.requests().isEmpty());
    }

    @Test
    void theKeyThatOpensAMessageDecidesWhoWroteIt() {
        Peer alice = new Peer("Alice");
        PairKeys aliceKeys = befriend(alice);
        // A name made up to match Alice's sender check can't change who wrote it: her key says it's Alice's
        String renamed = show(whisper("q3vk10lb", alice.token(aliceKeys.send(), "hi", Instant.now()))).getString();
        assertEquals(LOCK + "q3vk10lb whispers to you: [from Alice] hi", renamed);
        assertEquals("Alice", Friends.byKey(alice.key()).name(), "and nobody is renamed by it");

        // Our own message bounced back as if from Alice opens with our key, not hers: it shows as ours
        String ours = new Peer("Me", alice.identity()).token(aliceKeys.receive(), "my own words", Instant.now());
        assertEquals(LOCK + "Alice whispers to you: [from you] my own words", show(whisper("Alice", ours)).getString());
    }

    @Test
    void aNewNameNeedsTheFriendsMessageAndTheirConfirmation() {
        Peer alice = new Peer("Alice");
        PairKeys aliceKeys = befriend(alice);
        Component shown = show(whisper("Alicia", alice.control(aliceKeys.send(), new Rename("Alicia"))));
        assertTrue(shown.getString().contains("Your friend Alice says they're now called Alicia"), shown.getString());
        assertEquals("Alice", Friends.byKey(alice.key()).name(), "not until the player agrees");
        click(shown, "[Use the new name]");
        assertEquals("Alicia", Friends.byKey(alice.key()).name());
        assertEquals(List.of("Alice"), Friends.byKey(alice.key()).formerNames());
        assertNotNull(EncryptedChat.route("/msg Alicia hi").friend());

        // The old name may be someone else's now, but a private message to it never goes out as plain text
        assertNull(EncryptedChat.route("/msg Alice hi"));
        assertTrue(EncryptedChat.plainTextRefusal("/msg Alice hi").contains("former name of your friend Alicia"));
        assertTrue(EncryptedChat.lockLabel("/msg Alice hi").startsWith("§cWon't be sent"));
        Friends.reload();
        assertEquals(List.of("Alice"), Friends.byKey(alice.key()).formerNames(), "kept");

        // A rename can't come from anyone but the friend
        Peer mallory = new Peer("Mallory");
        String forged = show(whisper("Mallory", mallory.control(TOWN, new Rename("Mallory")))).getString();
        assertTrue(forged.contains("doesn't fit"), forged);
    }

    @Test
    void neverPlainTextToAFriendWhoseExchangeIsUnfinished() {
        Peer bob = new Peer("Bob");
        byte[] commitment = FriendCrypto.commitment(bob.key(), FriendCrypto.randomBytes(16));
        click(show(whisper("Bob", bob.control(TOWN, new HelloRequest(bob.key(), FriendCrypto.encode(commitment))))), "[Accept]");
        assertNull(EncryptedChat.route("/msg Bob hi"));
        assertTrue(EncryptedChat.plainTextRefusal("/msg Bob hi").contains("isn't finished"));
        ChatSettings.setEncryptFriendMessages(false);
        assertNull(EncryptedChat.plainTextRefusal("/msg Bob hi"), "unless the player turned friend encryption off");
        ChatSettings.setEncryptFriendMessages(true);
    }

    @Test
    void anIdentityFileThatCantBeReadIsNeverReplaced() throws Exception {
        Path file = this.dir.resolve("privacyplus-identity.properties");
        Files.writeString(file, "privateKey=garbled\n");
        Friends.reload();
        Friends.Outcome outcome = Friends.requestExchange("Bob", ChatSettings.get("town"));
        assertFalse(outcome.ok());
        assertTrue(outcome.message().contains("identity key file"), outcome.message());
        assertThrows(IllegalStateException.class, Friends::identity);
        assertEquals("privateKey=garbled\n", Files.readString(file), "left as it was");
    }

    @Test
    void queuedNotesSurviveAReconnectAndARestart() {
        Peer alice = new Peer("Alice");
        PairKeys aliceKeys = befriend(alice);
        String id = FriendMessages.newChannelId();
        Component invite = show(whisper("Alice", alice.control(aliceKeys.send(), new Invite(id, 1, FriendCrypto.encode(new byte[32]), "squad"))));
        click(invite, "[Join]");
        queued("Alice", FriendMessages.JOINED);
        Friends.QUEUE.removeIf(item -> true);
        Friends.reload();
        assertEquals(new ChannelNote(FriendMessages.JOINED, id, 1), queued("Alice", FriendMessages.JOINED), "read back from the friends file");
        assertEquals(Friends.byKey(alice.key()).keys().send(), queuedItem("Alice", FriendMessages.JOINED).key());
    }

    @Test
    void repeatsAreMarkedAndNeverActAgain() {
        Peer alice = new Peer("Alice");
        PairKeys aliceKeys = befriend(alice);
        String id = FriendMessages.newChannelId();
        String token = alice.control(aliceKeys.send(), new Invite(id, 1, FriendCrypto.encode(new byte[32]), "squad"));
        click(show(whisper("Alice", token)), "[Join]");
        assertNotNull(ChatSettings.get("squad"));
        ChatSettings.removeChannel("squad");
        String replay = show(whisper("Alice", token)).getString();
        assertTrue(replay.contains("[replayed]"), replay);
        assertTrue(FriendChannels.invites().isEmpty(), "the handler didn't run again");
    }

    @Test
    void joiningAFriendsChannel() {
        Peer alice = new Peer("Alice");
        PairKeys aliceKeys = befriend(alice);
        Peer bob = new Peer("Bob");
        PairKeys bobKeys = befriend(bob);
        String id = FriendMessages.newChannelId();
        SecretKey first = FriendChannels.newKey();

        // Bob reusing the id of Alice's channel can't get in the way of her invitation
        show(whisper("Bob", bob.control(bobKeys.send(), new Invite(id, 1, FriendCrypto.encode(FriendChannels.newKey().getEncoded()), "squat"))));
        Invite invited = new Invite(id, 1, FriendCrypto.encode(first.getEncoded()), "squad");
        Component invite = show(whisper("Alice", alice.control(aliceKeys.send(), invited)));
        assertTrue(invite.getString().contains("Alice invites you to the channel squad"), invite.getString());
        assertEquals(2, FriendChannels.invites().size());
        click(invite, "[Join]");
        FriendChannels.invites().forEach(i -> FriendChannels.ignore(i.handle()));
        ChatSettings.Channel squad = ChatSettings.get("squad");
        assertEquals(first, squad.readyKey());
        assertTrue(squad.group().ownedBy(alice.key()));
        assertEquals(new ChannelNote(FriendMessages.JOINED, id, 1), queued("Alice", FriendMessages.JOINED));
        String channelLine = "<Alice> hello squad";
        assertEquals(LOCK + channelLine, show(Component.literal("<Alice> " + alice.token(first, "hello squad", Instant.now()))).getString());

        SecretKey second = FriendChannels.newKey();
        String notOwner = show(whisper("Bob", bob.control(bobKeys.send(), new Rekey(id, 2, FriendCrypto.encode(second.getEncoded()))))).getString();
        assertTrue(notOwner.contains("they don't own"), notOwner);
        assertEquals(first, ChatSettings.get("squad").readyKey());

        Friends.QUEUE.removeIf(item -> true);
        show(whisper("Alice", alice.control(aliceKeys.send(), new Rekey(id, 2, FriendCrypto.encode(second.getEncoded())))));
        assertEquals(second, ChatSettings.get("squad").readyKey());
        assertEquals(new ChannelNote(FriendMessages.ACK, id, 2), queued("Alice", FriendMessages.ACK));
        assertEquals(LOCK + "<Alice> [old key] before the change",
            show(Component.literal("<Alice> " + alice.token(first, "before the change", Instant.now()))).getString());

        // The same key again (her re-send, or a replay of it) is acknowledged once per epoch at most
        Friends.QUEUE.removeIf(item -> true);
        show(whisper("Alice", alice.control(aliceKeys.send(), new Rekey(id, 2, FriendCrypto.encode(second.getEncoded())))));
        queued("Alice", FriendMessages.ACK);
        Friends.QUEUE.removeIf(item -> true);
        show(whisper("Alice", alice.control(aliceKeys.send(), new Rekey(id, 2, FriendCrypto.encode(second.getEncoded())))));
        assertTrue(Friends.QUEUE.items().isEmpty(), "no more acks for the same epoch");

        Rekey older = new Rekey(id, 1, FriendCrypto.encode(first.getEncoded()));
        String replay = show(whisper("Alice", alice.control(aliceKeys.send(), older))).getString();
        assertTrue(replay.contains("old key for squad: ignored"), replay);
        assertEquals(second, ChatSettings.get("squad").readyKey());

        String removed = show(whisper("Alice", alice.control(aliceKeys.send(), new ChannelNote(FriendMessages.REMOVED, id, 3)))).getString();
        assertTrue(removed.contains("Alice removed you from the channel squad"), removed);
        assertNull(ChatSettings.get("squad"));
    }

    @Test
    void runningAFriendsChannel() {
        Peer alice = new Peer("Alice");
        PairKeys aliceKeys = befriend(alice);
        Peer bob = new Peer("Bob");
        PairKeys bobKeys = befriend(bob);
        Peer carol = new Peer("Carol");
        PairKeys carolKeys = befriend(carol);

        assertNull(FriendChannels.create("crew", List.of(Friends.byKey(alice.key()), Friends.byKey(bob.key()))));
        ChatSettings.Channel crew = ChatSettings.get("crew");
        String id = crew.group().id();
        FriendChannels.queueDeliveries(System.currentTimeMillis());
        Invite invite = (Invite) queued("Alice", FriendMessages.INVITE);
        assertEquals(new Invite(id, 1, FriendCrypto.encode(crew.readyKey().getEncoded()), "crew"), invite);
        assertTrue(queuedItem("Alice", FriendMessages.INVITE).automatic(), "goes out a random while after she's online");
        queued("Bob", FriendMessages.INVITE);
        Friends.QUEUE.items().forEach(item -> item.onSent().run());
        assertEquals(MemberState.INVITED, ChatSettings.get("crew").group().member(alice.key()).state());

        String joined = show(whisper("Alice", alice.control(aliceKeys.send(), new ChannelNote(FriendMessages.JOINED, id, 1)))).getString();
        assertTrue(joined.contains("Alice joined"), joined);
        assertEquals(MemberState.JOINED, ChatSettings.get("crew").group().member(alice.key()).state());
        String outsider = show(whisper("Carol", carol.control(carolKeys.send(), new ChannelNote(FriendMessages.JOINED, id, 1)))).getString();
        assertTrue(outsider.contains("about a channel you're not in") || outsider.contains("not a member"), outsider);
        String bobJoined = show(whisper("Bob", bob.control(bobKeys.send(), new ChannelNote(FriendMessages.JOINED, id, 1)))).getString();
        assertTrue(bobJoined.contains("Bob joined"), bobJoined);

        Friends.QUEUE.removeIf(item -> true);
        FriendChannels.setMembers(ChatSettings.get("crew"), List.of(Friends.byKey(alice.key())));
        ChatSettings.Group after = ChatSettings.get("crew").group();
        assertEquals(2, after.epoch());
        assertNull(after.member(bob.key()));
        assertEquals(new ChannelNote(FriendMessages.REMOVED, id, 2), queued("Bob", FriendMessages.REMOVED));
        FriendChannels.queueDeliveries(System.currentTimeMillis());
        assertEquals(new Rekey(id, 2, FriendCrypto.encode(after.key().getEncoded())), queued("Alice", FriendMessages.REKEY));
        assertTrue(Friends.QUEUE.items().stream().noneMatch(i -> i.recipient().equals("Bob") && i.verb().equals(FriendMessages.REKEY)),
            "Bob doesn't get the new key");

        // Alice confirms, then writes with the old key: she gets it again, once; replays of that line change nothing
        show(whisper("Alice", alice.control(aliceKeys.send(), new ChannelNote(FriendMessages.ACK, id, 2))));
        Friends.QUEUE.removeIf(item -> true);
        String oldLine = alice.token(crew.readyKey(), "still the old key", Instant.now());
        show(Component.literal("<Alice> " + oldLine));
        FriendChannels.queueDeliveries(System.currentTimeMillis());
        queued("Alice", FriendMessages.REKEY);
        show(whisper("Alice", alice.control(aliceKeys.send(), new ChannelNote(FriendMessages.ACK, id, 2))));
        Friends.QUEUE.removeIf(item -> true);
        assertTrue(show(Component.literal("<Alice> " + oldLine)).getString().contains("[replayed]"));
        show(Component.literal("<Alice> " + alice.token(crew.readyKey(), "and again", Instant.now())));
        FriendChannels.queueDeliveries(System.currentTimeMillis());
        assertTrue(Friends.QUEUE.items().isEmpty(), "at most once per member and epoch: " + Friends.pendingSends());

        String leave = show(whisper("Alice", alice.control(aliceKeys.send(), new ChannelNote(FriendMessages.LEAVE, id, 2)))).getString();
        assertTrue(leave.contains("Alice left the channel crew"), leave);
        assertEquals(3, ChatSettings.get("crew").group().epoch());
        assertTrue(ChatSettings.get("crew").group().members().isEmpty());
    }
}
