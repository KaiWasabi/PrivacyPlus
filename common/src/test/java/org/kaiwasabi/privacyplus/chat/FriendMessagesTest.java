package org.kaiwasabi.privacyplus.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.kaiwasabi.privacyplus.chat.FriendMessages.ChannelNote;
import org.kaiwasabi.privacyplus.chat.FriendMessages.Done;
import org.kaiwasabi.privacyplus.chat.FriendMessages.HelloReply;
import org.kaiwasabi.privacyplus.chat.FriendMessages.HelloRequest;
import org.kaiwasabi.privacyplus.chat.FriendMessages.Invite;
import org.kaiwasabi.privacyplus.chat.FriendMessages.Message;
import org.kaiwasabi.privacyplus.chat.FriendMessages.Rekey;
import org.kaiwasabi.privacyplus.chat.FriendMessages.Rename;
import org.junit.jupiter.api.Test;

class FriendMessagesTest {
    private static final String KEY = FriendCrypto.generate().publicKey();
    private static final String NONCE = FriendCrypto.encode(FriendCrypto.randomBytes(16));
    private static final String TAG = FriendCrypto.encode(FriendCrypto.randomBytes(16));
    private static final String CHANNEL = FriendMessages.newChannelId();
    private static final String CHANNEL_KEY = FriendCrypto.encode(FriendCrypto.randomBytes(32));
    private static final List<Message> ALL = List.of(
        new HelloRequest(KEY, TAG),
        new HelloReply(KEY, NONCE, TAG),
        new Done(NONCE),
        new Rename("New_Name_2"),
        new Invite(CHANNEL, 1, CHANNEL_KEY, "squad-2024_x"),
        new Rekey(CHANNEL, 7, CHANNEL_KEY),
        new ChannelNote(FriendMessages.JOINED, CHANNEL, 1),
        new ChannelNote(FriendMessages.ACK, CHANNEL, 7),
        new ChannelNote(FriendMessages.REMOVED, CHANNEL, 8),
        new ChannelNote(FriendMessages.LEAVE, CHANNEL, 7));

    @Test
    void everyMessageRoundTrips() {
        for (Message message : ALL) {
            assertEquals(message, FriendMessages.decode(message.verb(), message.body()), message.toString());
        }
    }

    @Test
    void everyMessageFitsOneTokenInAPrivateMessage() {
        // /msg to the longest possible name leaves the least room
        int limit = 256 - "/msg ".length() - 16 - 1;
        for (Message message : ALL) {
            List<String> tokens = ChatCrypto.encrypt(ChatCryptoTest.KEY, "SixteenCharName_", EncryptedChat.CONTROL + message.verb() + " "
                + message.body(), limit, Instant.now());
            assertEquals(1, tokens.size(), message.verb() + " must be a single part");
        }
        Invite longest = new Invite(CHANNEL, 999_999_999, CHANNEL_KEY, "x".repeat(24));
        assertEquals(1, ChatCrypto.encrypt(ChatCryptoTest.KEY, "SixteenCharName_", EncryptedChat.CONTROL + longest.verb() + " " + longest.body(),
            limit, Instant.now()).size());
    }

    @Test
    void malformedOrForeignBodiesAreRefused() {
        assertNull(FriendMessages.decode(FriendMessages.HELLO, "2 req " + KEY + " " + TAG), "another format version");
        assertNull(FriendMessages.decode(FriendMessages.HELLO, "1 req " + KEY), "missing field");
        assertNull(FriendMessages.decode(FriendMessages.HELLO, "1 req " + KEY + " " + TAG + " extra"), "extra field");
        assertNull(FriendMessages.decode(FriendMessages.HELLO, "1 req " + NONCE + " " + TAG), "a 16-byte public key");
        assertNull(FriendMessages.decode(FriendMessages.HELLO, "1 maybe " + KEY + " " + TAG));
        assertNull(FriendMessages.decode(FriendMessages.INVITE, "1 " + CHANNEL + " 0 " + CHANNEL_KEY + " squad"), "epoch 0");
        assertNull(FriendMessages.decode(FriendMessages.INVITE, "1 " + CHANNEL + " -1 " + CHANNEL_KEY + " squad"), "negative epoch");
        assertNull(FriendMessages.decode(FriendMessages.INVITE, "1 " + CHANNEL + " 99999999999 " + CHANNEL_KEY + " squad"), "epoch too big");
        assertNull(FriendMessages.decode(FriendMessages.INVITE, "1 " + CHANNEL + " 1 " + CHANNEL_KEY + " bad.name"), "invalid channel name");
        assertNull(FriendMessages.decode(FriendMessages.REKEY, "1 " + CHANNEL + " 2 " + NONCE), "16-byte key");
        assertNull(FriendMessages.decode(FriendMessages.LEAVE, "1 notbase64! 2"));
        assertNull(FriendMessages.decode("coords", "1 2 3"), "not a friend message");
        assertNull(FriendMessages.decode(FriendMessages.DONE, ""));
        assertNull(FriendMessages.decode(FriendMessages.RENAME, "1 not a name"));
        assertNull(FriendMessages.decode(FriendMessages.RENAME, "1 " + "x".repeat(17)));
    }

    @Test
    void base64IsCompared() {
        // A different spelling of the same bytes decodes to the canonical spelling
        String key = FriendCrypto.encode(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27,
            28, 29, 30, 31, 32});
        char last = key.charAt(key.length() - 1);
        String other = key.substring(0, key.length() - 1) + (char) (last + 1);
        if (FriendCrypto.decode(other) != null && java.util.Arrays.equals(FriendCrypto.decode(other), FriendCrypto.decode(key))) {
            HelloRequest decoded = (HelloRequest) FriendMessages.decode(FriendMessages.HELLO, "1 req " + other + " " + TAG);
            assertEquals(key, decoded.publicKey());
        }
        assertTrue(FriendMessages.isChannelId(CHANNEL));
    }
}
