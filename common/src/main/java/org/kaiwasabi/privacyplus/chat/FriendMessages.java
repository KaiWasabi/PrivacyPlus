package org.kaiwasabi.privacyplus.chat;

import java.util.Set;
import org.jspecify.annotations.Nullable;

// The control messages (see EncryptedChat.CONTROL) behind friends and friend channels. Bodies are
// space-separated fields after a format version, so a later version can change a message without older
// clients misreading it. Keys, nonces and ids are unpadded URL-safe base64. Every one of them travels
// encrypted with a key the server doesn't have, so none can be told apart from other encrypted text:
// hellos under a channel key both players have, or under a key derived from a one-time friend code;
// everything else under the pairwise key of two friends.
//
//   hello     "1 req <public key> <commitment>"           please exchange keys
//   hello     "1 reply <public key> <nonce> <tag>"        the answer, under the same key (tag: see FriendCrypto.replyTag)
//   friend-ok "1 <nonce>"                                 pairwise key: reveals the committed nonce, exchange done
//   rename    "1 <new name>"                              pairwise key: the sender's account has a new name (confirmed by hand)
//   invite    "1 <channel id> <epoch> <key> <name>"       pairwise key, owner to member
//   joined    "1 <channel id> <epoch>"                    member to owner, after Join
//   rekey     "1 <channel id> <epoch> <key>"              owner to member: the channel's new key
//   ack       "1 <channel id> <epoch>"                    member to owner: got that key
//   removed   "1 <channel id> <epoch>"                    owner to a removed member
//   leave     "1 <channel id> <epoch>"                    member to owner
public final class FriendMessages {
    public static final String HELLO = "hello";
    public static final String DONE = "friend-ok";
    public static final String RENAME = "rename";
    public static final String INVITE = "invite";
    public static final String JOINED = "joined";
    public static final String REKEY = "rekey";
    public static final String ACK = "ack";
    public static final String REMOVED = "removed";
    public static final String LEAVE = "leave";
    public static final Set<String> CHANNEL_NOTES = Set.of(JOINED, ACK, REMOVED, LEAVE);
    static final int CHANNEL_ID_BYTES = 16;
    private static final String VERSION = "1";

    public sealed interface Message permits HelloRequest, HelloReply, Done, Rename, Invite, Rekey, ChannelNote {
        String verb();

        String body();
    }

    public record HelloRequest(String publicKey, String commitment) implements Message {
        @Override
        public String verb() {
            return HELLO;
        }

        @Override
        public String body() {
            return VERSION + " req " + this.publicKey + " " + this.commitment;
        }
    }

    public record HelloReply(String publicKey, String nonce, String tag) implements Message {
        @Override
        public String verb() {
            return HELLO;
        }

        @Override
        public String body() {
            return VERSION + " reply " + this.publicKey + " " + this.nonce + " " + this.tag;
        }
    }

    public record Done(String nonce) implements Message {
        @Override
        public String verb() {
            return DONE;
        }

        @Override
        public String body() {
            return VERSION + " " + this.nonce;
        }
    }

    public record Rename(String name) implements Message {
        @Override
        public String verb() {
            return RENAME;
        }

        @Override
        public String body() {
            return VERSION + " " + this.name;
        }
    }

    public record Invite(String channelId, int epoch, String key, String name) implements Message {
        @Override
        public String toString() {
            return "Invite[" + this.name + ", epoch " + this.epoch + "]";
        }

        @Override
        public String verb() {
            return INVITE;
        }

        @Override
        public String body() {
            return VERSION + " " + this.channelId + " " + this.epoch + " " + this.key + " " + this.name;
        }
    }

    public record Rekey(String channelId, int epoch, String key) implements Message {
        @Override
        public String toString() {
            return "Rekey[epoch " + this.epoch + "]";
        }

        @Override
        public String verb() {
            return REKEY;
        }

        @Override
        public String body() {
            return VERSION + " " + this.channelId + " " + this.epoch + " " + this.key;
        }
    }

    // joined, ack, removed and leave: which channel, and at which epoch
    public record ChannelNote(String verb, String channelId, int epoch) implements Message {
        public ChannelNote {
            if (!CHANNEL_NOTES.contains(verb)) {
                throw new IllegalArgumentException("Not a channel note: " + verb);
            }
        }

        @Override
        public String body() {
            return VERSION + " " + this.channelId + " " + this.epoch;
        }
    }

    private FriendMessages() {
    }

    // Null for anything malformed, from another format version, or not one of these messages
    public static @Nullable Message decode(String verb, String body) {
        String[] f = body.split(" ", -1);
        if (f.length < 2 || !f[0].equals(VERSION)) {
            return null;
        }
        return switch (verb) {
            case HELLO -> {
                if (f.length == 4 && f[1].equals("req") && isBytes(f[2], FriendCrypto.KEY_BYTES) && isBytes(f[3], FriendCrypto.TAG_BYTES)) {
                    yield new HelloRequest(canonical(f[2]), canonical(f[3]));
                }
                if (f.length == 5 && f[1].equals("reply") && isBytes(f[2], FriendCrypto.KEY_BYTES)
                    && isBytes(f[3], FriendCrypto.NONCE_BYTES) && isBytes(f[4], FriendCrypto.TAG_BYTES)) {
                    yield new HelloReply(canonical(f[2]), canonical(f[3]), canonical(f[4]));
                }
                yield null;
            }
            case DONE -> f.length == 2 && isBytes(f[1], FriendCrypto.NONCE_BYTES) ? new Done(canonical(f[1])) : null;
            case RENAME -> f.length == 2 && f[1].matches("[A-Za-z0-9_]{1,16}") ? new Rename(f[1]) : null;
            case INVITE -> f.length == 5 && isChannel(f[1], f[2]) && isBytes(f[3], FriendCrypto.KEY_BYTES) && ChatSettings.isValidName(f[4])
                ? new Invite(canonical(f[1]), Integer.parseInt(f[2]), canonical(f[3]), f[4]) : null;
            case REKEY -> f.length == 4 && isChannel(f[1], f[2]) && isBytes(f[3], FriendCrypto.KEY_BYTES)
                ? new Rekey(canonical(f[1]), Integer.parseInt(f[2]), canonical(f[3])) : null;
            case JOINED, ACK, REMOVED, LEAVE -> f.length == 3 && isChannel(f[1], f[2])
                ? new ChannelNote(verb, canonical(f[1]), Integer.parseInt(f[2])) : null;
            default -> null;
        };
    }

    public static String newChannelId() {
        return FriendCrypto.encode(FriendCrypto.randomBytes(CHANNEL_ID_BYTES));
    }

    static boolean isChannelId(String text) {
        return isBytes(text, CHANNEL_ID_BYTES);
    }

    // Base64 decoders accept several spellings of the same bytes; ids and keys are compared as text
    static String canonical(String base64) {
        return FriendCrypto.encode(FriendCrypto.decode(base64));
    }

    private static boolean isChannel(String id, String epoch) {
        return isChannelId(id) && epoch.matches("[1-9][0-9]{0,8}");
    }

    private static boolean isBytes(String text, int length) {
        byte[] bytes = FriendCrypto.decode(text);
        return bytes != null && bytes.length == length;
    }
}
