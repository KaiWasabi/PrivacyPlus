package org.kaiwasabi.privacyplus.devtest;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.crypto.SecretKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.kaiwasabi.privacyplus.chat.ChatCrypto;
import org.kaiwasabi.privacyplus.chat.ChatSettings;
import org.kaiwasabi.privacyplus.chat.ChatSettingsScreen;
import org.kaiwasabi.privacyplus.chat.EncryptedChat;
import org.kaiwasabi.privacyplus.chat.FriendChannels;
import org.kaiwasabi.privacyplus.chat.FriendCrypto;
import org.kaiwasabi.privacyplus.chat.FriendMessages;
import org.kaiwasabi.privacyplus.chat.Friends;
import org.kaiwasabi.privacyplus.chat.LockButton;
import org.jspecify.annotations.Nullable;

// In-game checks for friends, per-friend keys and the chat settings screens; run near the end of SelfTest.
// Only one client runs, so the friend ("FakeFriend") is simulated here: its own identity, and its messages
// shown the way the server shows a whisper. Its replies are read from our queue of automatic sends, which
// keeps them because FakeFriend is never online. FakeFriend asks through the channel "selftest" we share;
// two more simulated players go the friend-code way, one in each direction.
final class ChatChecks {
    private static final String FRIEND = "FakeFriend";
    private static final String CODE_FRIEND = "FakeCodeUser";
    private static final String CODE_MAKER = "FakeCodeMaker";
    private static final String LOCK = EncryptedChat.LOCK + " ";

    private ChatChecks() {
    }

    static void run(SelfTest test, Minecraft mc) throws Exception {
        String me = mc.getUser().getName();
        SelfTest.onRenderThread(mc, () -> cleanUp(mc));
        FriendCrypto.Identity friend = FriendCrypto.generate();
        try {
            SecretKey key = keyExchange(test, mc, friend, me);
            if (key != null) {
                friendCodes(test, mc, me);
                privateMessages(test, mc, friend, key, me);
                joinChannel(test, mc, friend, key, me);
                runChannel(test, mc, friend, key);
                displayOptions(test, mc);
                lockButton(test, mc);
                settingsScreens(test, mc);
            }
        } finally {
            SelfTest.onRenderThread(mc, () -> cleanUp(mc));
        }
        test.check("chat checks left no friend, code, channel or screen behind", Friends.byKey(friend.publicKey()) == null
            && Friends.all().stream().noneMatch(f -> f.name().startsWith("Fake")) && Friends.codes().isEmpty()
            && ChatSettings.get("fakechan") == null && ChatSettings.get("ownedchan") == null && mc.gui.screen() == null);
    }

    // FakeFriend asks through the channel we share, we click Accept, FakeFriend finishes: both sides must end up
    // with the same key and code
    private static @Nullable SecretKey keyExchange(SelfTest test, Minecraft mc, FriendCrypto.Identity friend, String me) throws Exception {
        byte[] nonce = FriendCrypto.randomBytes(FriendCrypto.NONCE_BYTES);
        String commitment = FriendCrypto.encode(FriendCrypto.commitment(friend.publicKey(), nonce));
        SecretKey shared = ChatSettings.get("selftest").readyKey();
        SelfTest.onRenderThread(mc, () -> {
            mc.gui.setScreen(new ChatScreen("", false));
            whisper(mc, FRIEND, control(shared, new FriendMessages.HelloRequest(friend.publicKey(), commitment)));
        });
        Thread.sleep(500);
        SelfTest.onRenderThread(mc, () -> {
            Component prompt = line(mc, "wants to exchange keys with you");
            test.check("a hello through a shared channel shows Accept and Ignore", prompt != null
                && prompt.getString().contains(FRIEND + " wants to exchange keys with you (through channel selftest) [Accept] [Ignore]"));
            test.check("the request is listed under Friends", Friends.requests().stream().anyMatch(r -> r.publicKey().equals(friend.publicKey())));
            if (prompt != null) {
                clickInChat(mc, prompt, "[Accept]");
            }
        });
        Friends.Pending reply = pending("hello");
        test.check("Accept queues our hello reply to FakeFriend (it waits: FakeFriend isn't online)", reply != null);
        test.check("the reply goes back under the shared channel's key",
            pendingKeys(FRIEND).stream().allMatch(k -> k.equals(shared)) && !pendingKeys(FRIEND).isEmpty());
        if (reply == null) {
            return null;
        }
        FriendMessages.HelloReply hello = (FriendMessages.HelloReply) FriendMessages.decode(reply.verb(), reply.body());
        // FakeFriend's keys with us; it writes with its send key
        FriendCrypto.PairKeys pair = FriendCrypto.pairwiseKeys(friend, FRIEND, hello.publicKey(), me);
        SecretKey key = pair.send();
        byte[] expectedTag = FriendCrypto.replyTag(pair.tag(), FriendCrypto.decode(commitment), FriendCrypto.decode(hello.nonce()));
        test.check("the reply carries our key and a tag only the key holder could make", hello.publicKey().equals(Friends.publicKey())
            && Arrays.equals(FriendCrypto.decode(hello.tag()), expectedTag));
        Friends.Friend stored = Friends.byKey(friend.publicKey());
        test.check("FakeFriend is stored with the same key FakeFriend derived",
            stored != null && Arrays.equals(stored.keys().receive().getEncoded(), key.getEncoded())
                && Arrays.equals(stored.keys().send().getEncoded(), pair.receive().getEncoded()));
        test.check("the key isn't used to send before the exchange is finished", stored != null && !stored.confirmed());

        SelfTest.onRenderThread(mc, () -> whisper(mc, FRIEND, control(key, new FriendMessages.Done(FriendCrypto.encode(nonce)))));
        Thread.sleep(500);
        String code = FriendCrypto.safetyCode(friend.publicKey(), nonce, hello.publicKey(), FriendCrypto.decode(hello.nonce()));
        SelfTest.onRenderThread(mc, () -> {
            Component done = line(mc, "finished the key exchange");
            test.check("finishing shows the safety code both sides compute (" + code + ")", done != null && done.getString().contains(code));
            Friends.Friend finished = Friends.byKey(friend.publicKey());
            test.check("FakeFriend is a finished, not yet verified friend", finished != null && finished.confirmed() && !finished.verified()
                && code.equals(finished.safetyCode()));
        });
        return key;
    }

    // Friend codes, for players who share no channel: one of ours used by FakeCodeUser (our client answers by
    // itself), and one FakeCodeMaker gave us
    private static void friendCodes(SelfTest test, Minecraft mc, String me) throws Exception {
        FriendCrypto.Identity user = FriendCrypto.generate();
        String[] code = {null};
        SelfTest.onRenderThread(mc, () -> {
            Friends.Outcome made = Friends.createCode();
            test.check("a friend code can be made (" + made.message() + ")", made.ok());
            code[0] = made.ok() ? made.message() : null;
        });
        if (code[0] == null) {
            return;
        }
        boolean ready = poll(() -> Friends.codes().stream().allMatch(c -> c.key().isDone()), 10_000);
        SecretKey codeKey = FriendCrypto.codeKey(code[0], me);
        byte[] nonce = FriendCrypto.randomBytes(FriendCrypto.NONCE_BYTES);
        String commitment = FriendCrypto.encode(FriendCrypto.commitment(user.publicKey(), nonce));
        SelfTest.onRenderThread(mc, () -> {
            whisper(mc, CODE_FRIEND, token(codeKey, CODE_FRIEND, EncryptedChat.CONTROL + "hello "
                + new FriendMessages.HelloRequest(user.publicKey(), commitment).body()));
            Component used = line(mc, "used your friend code");
            test.check("a hello under our friend code is answered without a click", ready && used != null
                && used.getString().contains(CODE_FRIEND + " used your friend code " + code[0]));
            test.check("and the code is used up", Friends.codes().isEmpty());
        });
        Friends.Pending reply = pendingTo(CODE_FRIEND, "hello");
        test.check("the answer waits in the queue under the code's key", reply != null && pendingKeys(CODE_FRIEND).contains(codeKey));
        if (reply != null) {
            FriendMessages.HelloReply hello = (FriendMessages.HelloReply) FriendMessages.decode(reply.verb(), reply.body());
            SecretKey key = FriendCrypto.pairwiseKeys(user, CODE_FRIEND, hello.publicKey(), me).send();
            SelfTest.onRenderThread(mc, () -> {
                whisper(mc, CODE_FRIEND, token(key, CODE_FRIEND, EncryptedChat.CONTROL + "friend-ok "
                    + new FriendMessages.Done(FriendCrypto.encode(nonce)).body()));
                Friends.Friend friend = Friends.byKey(user.publicKey());
                test.check("the friend-code exchange finishes with the same key on both sides", friend != null && friend.confirmed()
                    && Arrays.equals(friend.keys().receive().getEncoded(), key.getEncoded()));
            });
        }

        // The other way: FakeCodeMaker gave us a code
        FriendCrypto.Identity maker = FriendCrypto.generate();
        String theirCode = FriendCrypto.newFriendCode();
        Friends.Outcome sent = Friends.requestWithCode(CODE_MAKER, theirCode.toLowerCase(Locale.ROOT).replace("-", " ")).join();
        SecretKey theirKey = FriendCrypto.codeKey(theirCode, CODE_MAKER);
        Friends.Pending request = pendingTo(CODE_MAKER, "hello");
        test.check("entering a friend code queues a request under the code's key (" + sent.message() + ")", sent.ok() && request != null
            && pendingKeys(CODE_MAKER).contains(theirKey));
        if (request != null) {
            FriendMessages.HelloRequest asked = (FriendMessages.HelloRequest) FriendMessages.decode(request.verb(), request.body());
            FriendCrypto.PairKeys theirs = FriendCrypto.pairwiseKeys(maker, CODE_MAKER, asked.publicKey(), me);
            SecretKey key = theirs.send();
            byte[] theirNonce = FriendCrypto.randomBytes(FriendCrypto.NONCE_BYTES);
            byte[] tag = FriendCrypto.replyTag(theirs.tag(), FriendCrypto.decode(asked.commitment()), theirNonce);
            SelfTest.onRenderThread(mc, () -> {
                whisper(mc, CODE_MAKER, token(theirKey, CODE_MAKER, EncryptedChat.CONTROL + "hello " + new FriendMessages.HelloReply(
                    maker.publicKey(), FriendCrypto.encode(theirNonce), FriendCrypto.encode(tag)).body()));
                Component accepted = line(mc, CODE_MAKER + " accepted your key exchange");
                Friends.Friend friend = Friends.byKey(maker.publicKey());
                test.check("their answer under the code finishes it on our side", accepted != null && friend != null && friend.confirmed()
                    && Arrays.equals(friend.keys().receive().getEncoded(), key.getEncoded()) && pendingTo(CODE_MAKER, "friend-ok") != null);
            });
        }
    }

    // Private messages with the friend's key, both ways
    private static void privateMessages(SelfTest test, Minecraft mc, FriendCrypto.Identity friend, SecretKey key, String me) throws Exception {
        String incoming = "from the fake friend " + UUID.randomUUID();
        SelfTest.onRenderThread(mc, () -> {
            whisper(mc, FRIEND, token(key, FRIEND, incoming));
            Component shown = line(mc, incoming);
            test.check("a private message under FakeFriend's key is decrypted, marked not verified", shown != null
                && shown.getString().startsWith(LOCK) && hover(shown).contains("friend key with " + FRIEND) && hover(shown).contains("Not verified"));
            String repeated = token(key, FRIEND, "said once " + incoming);
            whisper(mc, FRIEND, repeated);
            whisper(mc, FRIEND, repeated);
            Component replay = line(mc, "said once " + incoming);
            test.check("the same token shown again is marked [replayed]", replay != null && replay.getString().contains("[replayed]"));
            whisper(mc, "q3vk10lb", token(key, FRIEND, "forged " + incoming));
            Component forged = line(mc, "forged " + incoming);
            test.check("a line from another name with FakeFriend's text says it's FakeFriend's, and renames nobody", forged != null
                && forged.getString().contains("[from " + FRIEND + "]") && FRIEND.equals(Friends.byKey(friend.publicKey()).name()));
            Friends.setVerified(friend.publicKey(), true);
            whisper(mc, FRIEND, token(key, FRIEND, incoming + " again"));
            Component verified = line(mc, incoming + " again");
            test.check("and as verified once marked so", verified != null && hover(verified).contains("(verified)"));

            ChatSettings.setActive(null);
            ChatScreen chat = (ChatScreen) mc.gui.screen();
            SelfTest.chatInput(chat).setValue("/msg " + FRIEND + " hi");
            String label = EncryptedChat.lockLabel();
            test.check("typing /msg to a friend says it goes out with the friend key, lock off (" + label + ")",
                label != null && label.contains("with your friend key"));
            test.check("so suggestions for it are held back too", EncryptedChat.wouldEncrypt("msg " + FRIEND + " hi"));
            ChatSettings.setActive(ChatSettings.get("selftest"));
            SelfTest.chatInput(chat).setValue("/say hello everyone");
            String plain = EncryptedChat.lockLabel();
            test.check("with the lock on, a command it doesn't encrypt gets a red warning (" + plain + ")", plain != null && plain.startsWith("§c"));
            ChatSettings.setActive(null);
            SelfTest.chatInput(chat).setValue("");
        });

        // Only one client: send to ourselves, with FakeFriend's record briefly under our own name
        String secret = "friend-key whisper " + UUID.randomUUID();
        SelfTest.onRenderThread(mc, () -> {
            rename(friend.publicKey(), me);
            ((ChatScreen) mc.gui.screen()).handleChatInput("/msg " + me + " " + secret, true);
        });
        Thread.sleep(2500);
        SelfTest.onRenderThread(mc, () -> {
            rename(friend.publicKey(), FRIEND);
            List<Component> lines = SelfTest.chatComponents(mc).stream().filter(c -> c.getString().endsWith(secret)).toList();
            test.check("an encrypted /msg to a friend came back decrypted with the friend key (" + lines.size() + " lines)", !lines.isEmpty()
                && lines.stream().allMatch(c -> c.getString().startsWith(LOCK) && hover(c).contains("friend key with " + me)));
            test.check("our own whisper echo isn't flagged as a sender mismatch",
                lines.stream().noneMatch(c -> c.getString().contains("[sender mismatch]")));
        });
        String log = Files.readString(mc.gameDirectory.toPath().resolve("logs/latest.log"));
        test.check("the friend-key whisper never reached latest.log as plain text", !log.contains(secret) && !log.contains(incoming));
    }

    // FakeFriend invites us to its channel, we join, then it changes the key and finally removes us
    private static void joinChannel(SelfTest test, Minecraft mc, FriendCrypto.Identity friend, SecretKey key, String me) throws Exception {
        String id = FriendMessages.newChannelId();
        SecretKey first = randomKey();
        SecretKey second = randomKey();
        SelfTest.onRenderThread(mc, () -> {
            whisper(mc, FRIEND, control(key, new FriendMessages.Invite(id, 1, FriendCrypto.encode(first.getEncoded()), "fakechan")));
            Component invite = line(mc, "invites you to the channel fakechan");
            test.check("a channel invitation shows Join and Ignore", invite != null && invite.getString().contains("[Join] [Ignore]"));
            if (invite != null) {
                clickInChat(mc, invite, "[Join]");
            }
            ChatSettings.Channel channel = ChatSettings.get("fakechan");
            test.check("Join adds the friend channel with the owner's key", channel != null && first.equals(channel.readyKey())
                && channel.group() != null && channel.group().ownedBy(friend.publicKey()));
            test.check("and tells the owner", hasPending("joined"));
            ChatSettings.setActive(channel);
        });
        String secret = "friend channel chat " + UUID.randomUUID();
        SelfTest.onRenderThread(mc, () -> ((ChatScreen) mc.gui.screen()).handleChatInput(secret, true));
        Thread.sleep(2000);
        SelfTest.onRenderThread(mc, () -> {
            test.check("the lock encrypts to a friend channel like to any channel",
                SelfTest.chatLines(mc).stream().anyMatch(l -> l.equals(LOCK + "<" + me + "> " + secret)));
            ChatSettings.setActive(null);
            whisper(mc, FRIEND, control(key, new FriendMessages.Rekey(id, 2, FriendCrypto.encode(second.getEncoded()))));
            ChatSettings.Channel channel = ChatSettings.get("fakechan");
            test.check("a new key from the owner replaces the old one, which is kept for older messages", channel != null
                && second.equals(channel.readyKey()) && channel.group().epoch() == 2 && channel.group().oldKeys().getFirst().key().equals(first));
            test.check("and is acknowledged", hasPending("ack"));
            String before = "said before the change " + UUID.randomUUID();
            say(mc, FRIEND, Component.literal("<" + FRIEND + "> " + token(first, FRIEND, before)));
            test.check("a message with the old key shows as [old key]",
                SelfTest.chatLines(mc).stream().anyMatch(l -> l.endsWith("[old key] " + before)));

            whisper(mc, FRIEND, control(key, new FriendMessages.Invite(id, 1, FriendCrypto.encode(first.getEncoded()), "fakechan")));
            test.check("a replayed old invitation doesn't bring the old key back", second.equals(ChatSettings.get("fakechan").readyKey()));
            whisper(mc, FRIEND, control(key, new FriendMessages.ChannelNote(FriendMessages.REMOVED, id, 3)));
            test.check("the owner removing us takes the channel away", ChatSettings.get("fakechan") == null);
        });
    }

    // We run a channel with FakeFriend in it: invite, join, then removal with a new key it never gets
    private static void runChannel(SelfTest test, Minecraft mc, FriendCrypto.Identity friend, SecretKey key) throws Exception {
        SelfTest.onRenderThread(mc, () -> test.check("making a friend channel works",
            FriendChannels.create("ownedchan", List.of(Friends.byKey(friend.publicKey()))) == null));
        boolean queued = poll(() -> hasPending("invite"), 10_000);
        Friends.Pending pending = pending("invite");
        ChatSettings.Channel channel = ChatSettings.get("ownedchan");
        test.check("the invitation is queued for FakeFriend with the channel's key", queued && pending != null && channel != null
            && FriendMessages.decode(pending.verb(), pending.body()) instanceof FriendMessages.Invite invite
            && invite.key().equals(FriendCrypto.encode(channel.readyKey().getEncoded())) && invite.epoch() == 1);
        String id = channel.group().id();
        SelfTest.onRenderThread(mc, () -> {
            whisper(mc, FRIEND, control(key, new FriendMessages.ChannelNote(FriendMessages.JOINED, id, 1)));
            ChatSettings.Member member = ChatSettings.get("ownedchan").group().member(friend.publicKey());
            test.check("FakeFriend's join is recorded", member != null && member.state() == ChatSettings.MemberState.JOINED);
            FriendChannels.setMembers(ChatSettings.get("ownedchan"), List.of());
            ChatSettings.Group after = ChatSettings.get("ownedchan").group();
            test.check("removing FakeFriend moves the channel to a new key", after.epoch() == 2 && after.members().isEmpty());
            test.check("FakeFriend is told, and gets no new key", hasPending("removed") && !hasPending("rekey"));
        });
    }

    private static void displayOptions(SelfTest test, Minecraft mc) throws Exception {
        String foreign = ChatCrypto.encrypt(randomKey(), "Stranger", "not for you", 256, Instant.now()).getFirst();
        String marker = "unreadable-" + UUID.randomUUID().toString().substring(0, 8);
        SelfTest.onRenderThread(mc, () -> {
            ChatSettings.setUnreadable(ChatSettings.Unreadable.REPLACE);
            say(mc, "Stranger", Component.literal("<Stranger> " + marker + " " + foreign));
            test.check("unreadable encrypted text can show as [encrypted]", SelfTest.chatLines(mc).contains("<Stranger> " + marker + " [encrypted]"));
            ChatSettings.setUnreadable(ChatSettings.Unreadable.HIDE);
            say(mc, "Stranger", Component.literal("<Stranger> hidden " + marker + " " + foreign));
            test.check("or be hidden", SelfTest.chatLines(mc).stream().noneMatch(l -> l.contains("hidden " + marker)));
            ChatSettings.setUnreadable(ChatSettings.Unreadable.SHOW);

            SecretKey channelKey = ChatSettings.get("selftest").readyKey();
            ChatSettings.setLockStyle(ChatSettings.LockStyle.NAME);
            say(mc, FRIEND, Component.literal("<" + FRIEND + "> " + token(channelKey, FRIEND, marker + " named")));
            test.check("the mark can be the channel name", SelfTest.chatLines(mc).contains("[selftest] <" + FRIEND + "> " + marker + " named"));
            ChatSettings.setLockStyle(ChatSettings.LockStyle.LOCK);
            ChatSettings.setShowChannelName(true);
            say(mc, FRIEND, Component.literal("<" + FRIEND + "> " + token(channelKey, FRIEND, marker + " both")));
            test.check("or the lock with the name", SelfTest.chatLines(mc).contains(LOCK + "selftest <" + FRIEND + "> " + marker + " both"));
            ChatSettings.setShowChannelName(false);
        });
    }

    // Shift-click moves the lock on to the next channel
    private static void lockButton(SelfTest test, Minecraft mc) {
        SelfTest.onRenderThread(mc, () -> {
            ChatScreen chat = new ChatScreen("", false);
            mc.gui.setScreen(chat);
            LockButton lock = SelfTest.lockButton(chat);
            ChatSettings.setActive(null);
            MouseButtonInfo shift = new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 1);
            MouseButtonEvent shiftClick = new MouseButtonEvent(lock.getX() + 6, lock.getY() + 6, shift);
            lock.mouseClicked(shiftClick, false);
            ChatSettings.Channel first = ChatSettings.active();
            lock.mouseClicked(shiftClick, false);
            ChatSettings.Channel second = ChatSettings.active();
            test.check("shift-clicking the lock goes through the channels (" + (first == null ? null : first.name()) + ", "
                + (second == null ? null : second.name()) + ")", first != null && second != null && !first.name().equals(second.name()));
            ChatSettings.setActive(null);
        });
    }

    // Each section of the settings, and the screens behind its buttons, with a screenshot of each
    private static void settingsScreens(SelfTest test, Minecraft mc) throws Exception {
        for (ChatSettingsScreen.Section section : ChatSettingsScreen.Section.values()) {
            SelfTest.onRenderThread(mc, () -> mc.gui.setScreen(new ChatSettingsScreen(null, section)));
            Thread.sleep(700);
            String name = section.name().toLowerCase(Locale.ROOT);
            SelfTest.onRenderThread(mc, () -> {
                boolean shown = mc.gui.screen() instanceof ChatSettingsScreen screen && screen.section() == section;
                String expected = switch (section) {
                    case CHANNELS -> "Edit";
                    case FRIENDS -> "Safety code";
                    case OPTIONS -> "Add command";
                };
                test.check("settings section " + name + " opens with its " + expected + " button", shown && button(mc, expected) != null);
                screenshot(mc, "selftest-chat-" + name + ".png");
            });
        }
        openFrom(test, mc, ChatSettingsScreen.Section.CHANNELS, "Edit", "Channel", "selftest-chat-channel.png");
        openFrom(test, mc, ChatSettingsScreen.Section.CHANNELS, "New channel", "New channel", "selftest-chat-new-channel.png");
        openFrom(test, mc, ChatSettingsScreen.Section.FRIENDS, "Safety code", "Safety code", "selftest-chat-safety-code.png");
        openFrom(test, mc, ChatSettingsScreen.Section.FRIENDS, "Create friend code", "Your friend code", "selftest-chat-friend-code.png");
        openFrom(test, mc, ChatSettingsScreen.Section.FRIENDS, "I have a friend code", "Use a friend code", "selftest-chat-use-friend-code.png");
        SelfTest.onRenderThread(mc, () -> mc.gui.setScreen(null));
    }

    private static void openFrom(SelfTest test, Minecraft mc, ChatSettingsScreen.Section section, String label, String title, String file)
        throws Exception {
        SelfTest.onRenderThread(mc, () -> {
            mc.gui.setScreen(new ChatSettingsScreen(null, section));
            Button button = button(mc, label);
            if (button != null) {
                SelfTest.click(button, InputConstants.MOUSE_BUTTON_LEFT);
            }
        });
        Thread.sleep(700);
        SelfTest.onRenderThread(mc, () -> {
            test.check(label + " opens the " + title + " screen", mc.gui.screen() != null && mc.gui.screen().getTitle().getString().equals(title));
            screenshot(mc, file);
            mc.gui.screen().onClose();
        });
    }

    private static void cleanUp(Minecraft mc) {
        Friends.all().stream().filter(f -> f.name().startsWith("Fake") || f.name().equals(mc.getUser().getName()))
            .forEach(f -> Friends.remove(f.publicKey()));
        Friends.codes().forEach(c -> Friends.cancelCode(c.code()));
        Friends.outgoing().forEach(o -> Friends.cancelRequest(o.name()));
        for (String name : List.of("fakechan", "ownedchan")) {
            if (ChatSettings.get(name) != null) {
                ChatSettings.removeChannel(name);
            }
        }
        ChatSettings.setActive(null);
        ChatSettings.setUnreadable(ChatSettings.Unreadable.SHOW);
        ChatSettings.setLockStyle(ChatSettings.LockStyle.LOCK);
        ChatSettings.setShowChannelName(false);
        mc.gui.setScreen(null);
    }

    // How the server shows a whisper from someone: "<name> whispers to you: <text>" (in the client's language)
    private static void whisper(Minecraft mc, String from, String token) {
        say(mc, from, Component.translatable("commands.message.display.incoming", Component.literal(from), Component.literal(token)));
    }

    // A player chat message from sender, the way ChatListener shows one (with its real sender known)
    private static void say(Minecraft mc, String sender, Component line) {
        EncryptedChat.setPacketSender(sender);
        try {
            mc.gui.hud.getChat().addPlayerMessage(line, null, null);
        } finally {
            EncryptedChat.setPacketSender(null);
        }
    }

    private static String token(SecretKey key, String sender, String text) {
        return ChatCrypto.encrypt(key, sender, text, 256 - "/msg ".length() - 17, Instant.now()).getFirst();
    }

    private static String control(SecretKey key, FriendMessages.Message message) {
        return token(key, FRIEND, EncryptedChat.CONTROL + message.verb() + " " + message.body());
    }

    private static SecretKey randomKey() {
        return new javax.crypto.spec.SecretKeySpec(FriendCrypto.randomBytes(32), "AES");
    }

    // The newest chat line containing text
    private static @Nullable Component line(Minecraft mc, String text) {
        return SelfTest.chatComponents(mc).stream().filter(c -> c.getString().contains(text)).findFirst().orElse(null);
    }

    private static String hover(Component line) {
        if (line.getSiblings().isEmpty() || !(line.getSiblings().getFirst().getStyle().getHoverEvent() instanceof HoverEvent.ShowText text)) {
            return "";
        }
        return text.value().getString();
    }

    // Clicks [label] on a chat line through the chat screen's own click handling, like a player would
    private static void clickInChat(Minecraft mc, Component line, String label) {
        Optional<Style> style = line.visit((s, text) -> text.equals(label) ? Optional.of(s) : Optional.empty(), Style.EMPTY);
        if (style.isPresent() && mc.gui.screen() instanceof ChatScreen chat) {
            SelfTest.invoke(ChatScreen.class, "handleComponentClicked", chat, style.get(), false);
        }
    }

    private static Friends.@Nullable Pending pending(String verb) {
        return pendingTo(FRIEND, verb);
    }

    // The keys of everything queued for recipient
    private static List<SecretKey> pendingKeys(String recipient) {
        return Friends.pendingMessages().stream().filter(p -> p.recipient().equals(recipient)).map(Friends.Pending::key).toList();
    }

    private static Friends.@Nullable Pending pendingTo(String recipient, String verb) {
        return Friends.pendingMessages().stream().filter(p -> p.recipient().equals(recipient) && p.verb().equals(verb)).findFirst().orElse(null);
    }

    private static boolean hasPending(String verb) {
        return pending(verb) != null;
    }

    private static @Nullable Button button(Minecraft mc, String label) {
        try {
            return SelfTest.settingsButton(mc, label);
        } catch (AssertionError e) {
            return null;
        }
    }

    // FakeFriend's record under another name, as if they had renamed (and we had agreed)
    private static void rename(String publicKey, String name) {
        SelfTest.invoke(Friends.class, "rename", null, publicKey, name);
    }

    private static boolean poll(Supplier<Boolean> condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.get()) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(250);
        }
        return true;
    }

    private static void screenshot(Minecraft mc, String file) {
        Screenshot.grab(mc.gameDirectory, file, mc.gameRenderer.mainRenderTarget(), 1, message -> { });
    }
}
