package org.kaiwasabi.privacyplus.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import org.kaiwasabi.privacyplus.chat.ChatSettings.CommandRule;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Group;
import org.kaiwasabi.privacyplus.chat.ChatSettings.LockStyle;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Member;
import org.kaiwasabi.privacyplus.chat.ChatSettings.MemberState;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Unreadable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatSettingsTest {
    private static final Map<String, Integer> RULES = ChatSettings.parseCommands(ChatSettings.DEFAULT_COMMANDS);

    @Test
    void parsesCommandRules() {
        assertEquals(Map.of("msg", 1, "r", 0, "tell", 2), ChatSettings.parseCommands(" /msg:1, r  tell:2 bad:x toomany:9 "));
        assertEquals(1, RULES.get("msg"));
        assertEquals(0, RULES.get("r"));
    }

    @Test
    void splitsCommandsIntoReadableAndEncryptedParts() {
        assertArrayEquals(new String[]{"msg Steve ", "meet at spawn"}, EncryptedChat.splitCommand("msg Steve meet at spawn", RULES).orElseThrow());
        assertArrayEquals(new String[]{"r ", "on my way"}, EncryptedChat.splitCommand("r on my way", RULES).orElseThrow());
        assertArrayEquals(new String[]{"minecraft:tell Alex ", "hi"}, EncryptedChat.splitCommand("minecraft:tell Alex hi", RULES).orElseThrow());
        assertArrayEquals(new String[]{"MSG Steve ", "caps"}, EncryptedChat.splitCommand("MSG Steve caps", RULES).orElseThrow());
    }

    @Test
    void leavesOtherCommandsAlone() {
        assertTrue(EncryptedChat.splitCommand("gamemode creative", RULES).isEmpty(), "not in the list");
        assertTrue(EncryptedChat.splitCommand("msg Steve", RULES).isEmpty(), "no message to encrypt");
        assertTrue(EncryptedChat.splitCommand("msg", RULES).isEmpty());
    }

    @Test
    void serverNamesAreNormalized() {
        assertEquals("play.example.com:25565", ChatSettings.serverKey("Play.Example.com"));
        assertEquals("play.example.com:25565", ChatSettings.serverKey("play.example.com:25565"));
        assertEquals("play.example.com:25570", ChatSettings.serverKey(" play.example.com:25570 "));
    }

    @Test
    void savesAndReloadsEverything(@TempDir Path dir) throws Exception {
        ChatSettings.load(dir);
        assertTrue(Files.readString(dir.resolve("privacyplus-chat.properties")).contains("right-click the lock"), "file created with instructions");

        String tricky = " starts with a space, has a \\ backslash = and : signs";
        ChatSettings.addChannel("friends", tricky);
        ChatSettings.addChannel("family", "another long passphrase");
        ChatSettings.setActive(ChatSettings.get("friends"));
        ChatSettings.setEncryptPublicChat(false);
        ChatSettings.setEncryptCommands("msg:1 r:0");
        ChatSettings.setUnsigned("Play.Example.com", true);

        assertEquals(2, ChatSettings.reload());
        assertEquals(tricky, ChatSettings.get("friends").passphrase());
        assertEquals("friends", ChatSettings.active().name());
        assertFalse(ChatSettings.encryptPublicChat());
        assertEquals(Map.of("msg", 1, "r", 0), ChatSettings.encryptCommands());
        assertTrue(ChatSettings.isUnsigned("play.example.com:25565"));
        assertFalse(ChatSettings.isUnsigned("other.example.com"));

        ChatSettings.removeChannel("friends");
        assertNull(ChatSettings.active(), "removing the channel in use turns the lock off");
        assertEquals("family", ChatSettings.lastUsed().name(), "the lock falls back to the remaining channel");
    }

    @Test
    void savesAndReloadsFriendChannelsAndDisplayOptions(@TempDir Path dir) throws Exception {
        ChatSettings.load(dir);
        String owner = FriendCrypto.generate().publicKey();
        String member = FriendCrypto.generate().publicKey();
        SecretKey key = FriendChannels.newKey();
        SecretKey oldKey = FriendChannels.newKey();
        Group group = new Group(FriendMessages.newChannelId(), owner, "Alice", 3, key, List.of(new ChatSettings.OldKey(2, oldKey)),
            List.of(new Member(member, "Bob", MemberState.JOINED, 2, 1, 12345L)));
        ChatSettings.addFriendChannel("squad", group);
        ChatSettings.addChannel("family", "another long passphrase");
        ChatSettings.setColor("squad", 0xFF55FF);
        ChatSettings.setActive(ChatSettings.get("squad"));
        ChatSettings.setEncryptFriendMessages(false);
        ChatSettings.setLockStyle(LockStyle.NAME);
        ChatSettings.setShowChannelName(true);
        ChatSettings.setUnreadable(Unreadable.HIDE);
        ChatSettings.setOldAfterMinutes(30);
        ChatSettings.setAutoChannel("Play.Example.com", "squad");
        ChatSettings.setCommandRules(List.of(new CommandRule("/MSG", 1), new CommandRule("", 2), new CommandRule("teammsg", 0)));

        assertEquals(2, ChatSettings.reload());
        ChatSettings.Channel squad = ChatSettings.get("squad");
        assertEquals(ChatSettings.Kind.FRIENDS, squad.kind());
        assertEquals(group, squad.group());
        assertEquals(key, squad.readyKey(), "a friend channel's key needs no deriving");
        assertEquals(0xFF55FF, squad.color());
        assertEquals(ChatSettings.DEFAULT_COLOR, ChatSettings.get("family").color());
        assertEquals(ChatSettings.Kind.PASSPHRASE, ChatSettings.get("family").kind());
        assertEquals("squad", ChatSettings.active().name());
        assertSame(squad.name(), ChatSettings.byGroupId(group.id()).name());
        assertFalse(ChatSettings.encryptFriendMessages());
        assertEquals(LockStyle.NAME, ChatSettings.lockStyle());
        assertTrue(ChatSettings.showChannelName());
        assertEquals(Unreadable.HIDE, ChatSettings.unreadable());
        assertEquals(30, ChatSettings.oldAfterMinutes());
        assertEquals("squad", ChatSettings.autoChannel("play.example.com:25565").name());
        assertEquals("msg:1 teammsg:0", ChatSettings.encryptCommandsText(), "same format as before; the empty row is dropped");
        assertEquals(List.of(new CommandRule("msg", 1), new CommandRule("teammsg", 0)), ChatSettings.commandRules());

        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("privacyplus-chat.properties"))),
                "passphrases and keys: owner only");
        }

        assertTrue(ChatSettings.renameChannel("squad", "crew"));
        assertNull(ChatSettings.get("squad"));
        assertEquals("crew", ChatSettings.active().name(), "renaming keeps the lock on it");
        assertEquals("crew", ChatSettings.autoChannel("play.example.com").name());
        assertFalse(ChatSettings.renameChannel("crew", "family"), "taken");
        assertFalse(ChatSettings.renameChannel("crew", "bad name"));
        assertEquals("crew-2", ChatSettings.freeName("crew"));
        assertEquals("family", ChatSettings.next(ChatSettings.get("crew")).name());
        assertEquals("crew", ChatSettings.next(ChatSettings.get("family")).name(), "wraps around");

        ChatSettings.removeChannel("crew");
        assertNull(ChatSettings.autoChannel("play.example.com"), "no auto channel left pointing nowhere");
    }

    @Test
    void readsFilesFromBeforeFriendChannels(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("privacyplus-chat.properties"), """
            # an older version's file
            active=friends
            encryptPublicChat=false
            encryptCommands=msg:1 r:0
            unsignedChatServers=play.example.com:25565
            channel.friends=correct horse battery staple ocean
            channel.bad.name=ignored
            """);
        ChatSettings.load(dir);
        assertEquals(1, ChatSettings.all().size());
        ChatSettings.Channel friends = ChatSettings.get("friends");
        assertEquals(ChatSettings.Kind.PASSPHRASE, friends.kind());
        assertEquals("correct horse battery staple ocean", friends.passphrase());
        assertEquals(ChatSettings.DEFAULT_COLOR, friends.color());
        assertEquals("friends", ChatSettings.active().name());
        assertFalse(ChatSettings.encryptPublicChat());
        assertEquals(Map.of("msg", 1, "r", 0), ChatSettings.encryptCommands());
        assertTrue(ChatSettings.isUnsigned("play.example.com"));
        assertTrue(ChatSettings.encryptFriendMessages(), "new options get their defaults");
        assertEquals(LockStyle.LOCK, ChatSettings.lockStyle());
        assertEquals(Unreadable.SHOW, ChatSettings.unreadable());
        assertEquals(10, ChatSettings.oldAfterMinutes());
        assertArrayEquals(ChatCryptoTest.KEY.getEncoded(), friends.key().get().getEncoded(), "same passphrase, same key");
        assertTrue(Files.readString(dir.resolve("privacyplus-chat.properties")).contains("channel.friends=correct horse battery staple ocean"),
            "and it is written back in the same format");
    }

    @Test
    void unreadableFriendChannelsAreSkipped() {
        assertNull(ChatSettings.parseGroup("not a group", "", ""));
        assertNull(ChatSettings.parseGroup(FriendMessages.newChannelId() + " 1 tooshort " + FriendCrypto.generate().publicKey() + " Alice", "", ""));
    }

    @Test
    void weakPassphrasesAreRecognized() {
        assertTrue(ChatSettings.passphraseBits("correct horse battery staple") < 60, "four common words");
        assertTrue(ChatSettings.passphraseBits("minecraft123456") < 60, "a well-known password with digits");
        assertTrue(ChatSettings.passphraseBits("aaaaaaaaaaaaaaaaaaaa") < 60, "repeats");
        assertTrue(ChatSettings.passphraseBits("abcdefghijklmnop") < 60, "a run");
        assertTrue(ChatSettings.passphraseBits(ChatSettings.randomPassphrase()) >= 80, "Random is strong");
        assertTrue(ChatSettings.passphraseBits("Tq9#mZ2!vL7@pX4$") >= 80);
    }

    @Test
    void secretsStayOutOfLogs() {
        ChatSettings.Channel channel = new ChatSettings.Channel("squad", "a very secret passphrase",
            java.util.concurrent.CompletableFuture.completedFuture(ChatCryptoTest.KEY));
        assertFalse(channel.toString().contains("secret"), channel.toString());
        Group group = new Group(FriendMessages.newChannelId(), FriendCrypto.generate().publicKey(), "Alice", 1, ChatCryptoTest.KEY, List.of(),
            List.of());
        assertFalse(group.toString().contains("SecretKeySpec"), group.toString());
    }
}
