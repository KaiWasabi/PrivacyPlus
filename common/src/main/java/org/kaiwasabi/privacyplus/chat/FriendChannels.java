package org.kaiwasabi.privacyplus.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Channel;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Group;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Member;
import org.kaiwasabi.privacyplus.chat.ChatSettings.MemberState;
import org.kaiwasabi.privacyplus.chat.EncryptedChat.Control;
import org.kaiwasabi.privacyplus.chat.FriendMessages.ChannelNote;
import org.kaiwasabi.privacyplus.chat.FriendMessages.Invite;
import org.kaiwasabi.privacyplus.chat.FriendMessages.Rekey;
import org.kaiwasabi.privacyplus.chat.Friends.Friend;
import org.jspecify.annotations.Nullable;

// Friend channels: the owner's client makes a random key and sends it to each member by /msg, encrypted
// with that member's friend key. Removing someone (or someone leaving) gives everyone else a new key, one
// epoch up, so they can't read anything new. Only the owner changes membership: members accept keys and
// removals only from the channel's owner, the owner accepts joins, acks and leaves only from members, and
// anything about an epoch already seen is a replay. Keys go out a random while after the member comes online
// (see SendQueue), and are sent again until the member confirms them. Render thread only.
public final class FriendChannels {
    static final long INVITE_LIFETIME_MILLIS = 7 * 24 * 3_600_000L;
    static final long RETRY_MILLIS = 2 * 60_000L;
    static final int MAX_RETRIES = 5;
    private static final long CHECK_EVERY_MILLIS = 2000;
    private static final int MAX_INVITES = 20;

    // An invitation we got and haven't answered
    public record PendingInvite(String channelId, String ownerKey, String ownerName, int epoch, SecretKey key, String name, long receivedAt) {
        // What it's filed under: channel ids are only unique per owner
        public String handle() {
            return this.ownerKey + " " + this.channelId;
        }

        @Override
        public String toString() {
            return "PendingInvite[" + this.name + " from " + this.ownerName + "]";
        }
    }

    // Why a membership message is refused
    enum Verdict { ACCEPT, NOT_OWNER, NOT_MEMBER, OLD_EPOCH }

    private record Attempt(int epoch, int count, long at) {
    }

    private static final Map<String, PendingInvite> INVITES = new LinkedHashMap<>();
    private static final Map<String, Attempt> ATTEMPTS = new HashMap<>();
    // Re-sends after seeing an old key in use, and acks of a key we already had: each at most once per epoch,
    // since a server can replay old lines to trigger them
    private static final Set<String> RESENT = new HashSet<>();
    private static final Set<String> ACKED_AGAIN = new HashSet<>();
    private static long nextCheck;

    private FriendChannels() {
    }

    static void register() {
        EncryptedChat.registerControl(FriendMessages.INVITE, FriendChannels::onInvite);
        EncryptedChat.registerControl(FriendMessages.REKEY, FriendChannels::onRekey);
        for (String verb : FriendMessages.CHANNEL_NOTES) {
            EncryptedChat.registerControl(verb, FriendChannels::onNote);
        }
    }

    // A member hearing from someone about a channel: only its owner may change it, and only forward in epochs
    static Verdict fromOwner(Group group, String senderKey, int epoch) {
        if (!group.ownedBy(senderKey)) {
            return Verdict.NOT_OWNER;
        }
        return epoch > group.epoch() ? Verdict.ACCEPT : Verdict.OLD_EPOCH;
    }

    // The owner hearing from a member (joined, ack, leave): it must be our channel and their membership, and
    // not about an epoch from before they were (last) added
    static Verdict fromMember(Group group, String myKey, String senderKey, int epoch) {
        if (!group.ownedBy(myKey)) {
            return Verdict.NOT_OWNER;
        }
        Member member = group.member(senderKey);
        if (member == null) {
            return Verdict.NOT_MEMBER;
        }
        return epoch >= member.addedEpoch() && epoch <= group.epoch() ? Verdict.ACCEPT : Verdict.OLD_EPOCH;
    }

    // members changed to wanted: newcomers are invited; anyone left out is dropped, and if anyone was, the
    // channel gets a new key so they can't read what comes next
    static Group withMembers(Group group, List<Friend> wanted, SecretKey newKey, long now) {
        Set<String> keys = wanted.stream().map(Friend::publicKey).collect(Collectors.toSet());
        boolean removed = group.members().stream().anyMatch(m -> !keys.contains(m.publicKey()));
        Group updated = removed ? group.rekeyed(group.epoch() + 1, newKey) : group;
        List<Member> members = new ArrayList<>(updated.members().stream().filter(m -> keys.contains(m.publicKey())).toList());
        for (Friend friend : wanted) {
            if (updated.member(friend.publicKey()) == null) {
                members.add(new Member(friend.publicKey(), friend.name(), MemberState.INVITE, 0, updated.epoch(), now));
            }
        }
        return updated.withMembers(members);
    }

    // Makes a channel you own with these friends. Returns why it can't, or null.
    public static @Nullable String create(String name, List<Friend> members) {
        if (!ChatSettings.isValidName(name)) {
            return "Channel names use letters, digits, - and _ (up to 24).";
        }
        if (ChatSettings.get(name) != null) {
            return "There is already a channel called " + name + ".";
        }
        String me = EncryptedChat.selfName();
        String myKey = Friends.identity().publicKey();
        Group group = new Group(FriendMessages.newChannelId(), myKey, me == null ? "you" : me, 1, newKey(), List.of(), List.of());
        ChatSettings.addFriendChannel(name, withMembers(group, members, newKey(), System.currentTimeMillis()));
        nextCheck = 0;
        return null;
    }

    // Owner: the channel's members become exactly these friends
    public static void setMembers(Channel channel, List<Friend> members) {
        Group before = channel.group();
        String myKey = Friends.publicKey();
        if (before == null || myKey == null || !before.ownedBy(myKey)) {
            return;
        }
        Group after = withMembers(before, members, newKey(), System.currentTimeMillis());
        for (Member gone : before.members()) {
            if (after.member(gone.publicKey()) == null) {
                tellRemoved(before, gone.publicKey(), after.epoch());
            }
        }
        ChatSettings.updateGroup(channel.name(), group -> after);
        if (after.epoch() != before.epoch()) {
            keyChanged(after.id());
        }
        nextCheck = 0;
    }

    // Owner: send the invitation again (it expired, or never arrived)
    public static void inviteAgain(Channel channel, String memberKey) {
        ChatSettings.updateGroup(channel.name(), group -> {
            Member member = group.member(memberKey);
            return member == null ? group : group.withMember(member.withState(MemberState.INVITE, System.currentTimeMillis()));
        });
        nextCheck = 0;
    }

    // Removing a friend channel: the owner tells every member it's gone; a member tells the owner they left
    public static void removeChannel(Channel channel) {
        Group group = channel.group();
        String myKey = Friends.publicKey();
        if (group != null && myKey != null && group.ownedBy(myKey)) {
            group.members().forEach(m -> tellRemoved(group, m.publicKey(), group.epoch() + 1));
        } else if (group != null) {
            Friend owner = Friends.byKey(group.ownerKey());
            if (owner != null && owner.confirmed()) {
                ChannelNote leave = new ChannelNote(FriendMessages.LEAVE, group.id(), group.epoch());
                Friends.queue("leave " + group.id(), owner.name(), owner.keys().send(), leave,
                    INVITE_LIFETIME_MILLIS, false, null);
            }
        }
        ChatSettings.removeChannel(channel.name());
    }

    public static List<PendingInvite> invites() {
        long now = System.currentTimeMillis();
        INVITES.values().removeIf(i -> now - i.receivedAt() > INVITE_LIFETIME_MILLIS);
        return List.copyOf(INVITES.values());
    }

    // Member: accept an invitation (by its handle). Returns the channel's name here, or null if the invitation is gone.
    public static @Nullable String join(String handle) {
        PendingInvite invite = INVITES.remove(handle);
        Friend owner = invite == null ? null : Friends.byKey(invite.ownerKey());
        if (invite == null || owner == null || ChatSettings.byGroup(invite.ownerKey(), invite.channelId()) != null) {
            return null;
        }
        String channelId = invite.channelId();
        String name = ChatSettings.freeName(invite.name());
        Group group = new Group(channelId, invite.ownerKey(), owner.name(), invite.epoch(), invite.key(), List.of(), List.of());
        ChatSettings.addFriendChannel(name, group);
        Friends.queue("joined " + channelId, owner.name(), owner.keys().send(), new ChannelNote(FriendMessages.JOINED, channelId, invite.epoch()),
            INVITE_LIFETIME_MILLIS, false, null);
        return name;
    }

    public static void ignore(String handle) {
        INVITES.remove(handle);
    }

    // A friend is gone: out of the channels we run, which get a new key
    static void friendRemoved(String publicKey) {
        String myKey = Friends.publicKey();
        for (Channel channel : ChatSettings.all()) {
            Group group = channel.group();
            if (group != null && myKey != null && group.ownedBy(myKey) && group.member(publicKey) != null) {
                ChatSettings.updateGroup(channel.name(), g -> g.withoutMember(publicKey).rekeyed(g.epoch() + 1, newKey()));
                keyChanged(group.id());
            }
        }
        INVITES.values().removeIf(i -> i.ownerKey().equals(publicKey));
    }

    // Someone wrote a fresh message (first seen, not old) with an older key of a channel we run: if a member, they
    // missed the new key, so send it again: at most once per member and epoch, after a random pause
    static void oldKeyUsed(Channel channel, String writer) {
        Group group = channel.group();
        String myKey = Friends.publicKey();
        if (group == null || myKey == null || !group.ownedBy(myKey)) {
            return;
        }
        for (Member member : group.members()) {
            Friend friend = Friends.byKey(member.publicKey());
            if (friend != null && friend.name().equalsIgnoreCase(writer) && member.state() == MemberState.JOINED
                && member.ackedEpoch() >= group.epoch() && RESENT.add(group.id() + " " + member.publicKey() + " " + group.epoch())) {
                ATTEMPTS.remove(group.id() + " " + member.publicKey());
                nextCheck = 0;
                ChatSettings.updateGroup(channel.name(), g -> g.withMember(new Member(member.publicKey(), member.name(), MemberState.JOINED,
                    g.epoch() - 1, member.addedEpoch(), member.since())));
            }
        }
    }

    // Every couple of seconds while connected: queue invitations and new keys for the members of our channels
    static void queueDeliveries(long now) {
        if (now < nextCheck) {
            return;
        }
        nextCheck = now + CHECK_EVERY_MILLIS;
        String myKey = Friends.publicKey();
        if (myKey == null) {
            return;
        }
        for (Channel channel : ChatSettings.all()) {
            Group group = channel.group();
            if (group == null || !group.ownedBy(myKey)) {
                continue;
            }
            for (Member member : group.members()) {
                Friend friend = Friends.byKey(member.publicKey());
                if (friend == null || !friend.confirmed()) {
                    continue;
                }
                String id = group.id() + " " + member.publicKey();
                switch (member.state()) {
                    case INVITE, INVITED -> {
                        if (now - member.since() > INVITE_LIFETIME_MILLIS) {
                            setState(group.id(), member.publicKey(), MemberState.EXPIRED, now);
                        } else if (member.state() == MemberState.INVITE && !Friends.QUEUE.contains("invite " + id)) {
                            // The invitation goes out with the key current when it is sent
                            Friends.queue("invite " + id, friend.name(), friend.keys().send(), new Invite(group.id(), group.epoch(),
                                FriendCrypto.encode(group.key().getEncoded()), channel.name()), INVITE_LIFETIME_MILLIS, true,
                                () -> setState(group.id(), member.publicKey(), MemberState.INVITED, System.currentTimeMillis()));
                        }
                    }
                    case JOINED -> {
                        // Sent when they're next online; sent again a few times if they don't confirm it
                        Attempt attempt = ATTEMPTS.get(id);
                        int epoch = group.epoch();
                        boolean due = attempt == null || attempt.epoch() != epoch
                            || (attempt.count() < MAX_RETRIES && now - attempt.at() > RETRY_MILLIS);
                        if (member.ackedEpoch() < epoch && due && !Friends.QUEUE.contains("rekey " + id)) {
                            int sent = attempt != null && attempt.epoch() == epoch ? attempt.count() : 0;
                            Rekey rekey = new Rekey(group.id(), epoch, FriendCrypto.encode(group.key().getEncoded()));
                            Friends.queue("rekey " + id, friend.name(), friend.keys().send(), rekey, INVITE_LIFETIME_MILLIS, true,
                                () -> ATTEMPTS.put(id, new Attempt(epoch, sent + 1, System.currentTimeMillis())));
                        }
                    }
                    case EXPIRED -> {
                    }
                }
            }
        }
    }

    // Queued invitations and keys for this channel carry the old key: drop them, the next check queues new ones
    private static void keyChanged(String channelId) {
        Friends.QUEUE.removeIf(item -> (item.id().startsWith("invite ") || item.id().startsWith("rekey ")) && item.id().contains(channelId));
        nextCheck = 0;
    }

    // By channel id: the owner may have renamed the channel since
    private static void setState(String channelId, String memberKey, MemberState state, long now) {
        String myKey = Friends.publicKey();
        Channel channel = myKey == null ? null : ChatSettings.byGroup(myKey, channelId);
        if (channel == null) {
            return;
        }
        ChatSettings.updateGroup(channel.name(), group -> {
            Member member = group.member(memberKey);
            return member == null || member.state() == MemberState.JOINED ? group : group.withMember(member.withState(state, now));
        });
    }

    private static void tellRemoved(Group group, String memberKey, int epoch) {
        Friend friend = Friends.byKey(memberKey);
        if (friend != null && friend.confirmed()) {
            Friends.queue("removed " + group.id() + " " + memberKey, friend.name(), friend.keys().send(),
                new ChannelNote(FriendMessages.REMOVED, group.id(), epoch), INVITE_LIFETIME_MILLIS, true, null);
        }
    }

    // "invite": a friend invites us to their channel
    private static Component onInvite(Control control, Style style) {
        FriendMessages.Message message = FriendMessages.decode(control.verb(), control.body());
        String name = message instanceof Invite invite ? invite.name() : "?";
        if (control.mine()) {
            return Friends.note("[invitation to channel " + name + "]");
        }
        Friend from = sender(control);
        if (from == null || !(message instanceof Invite invite)) {
            return Friends.note("[a channel invitation that doesn't fit: ignored]");
        }
        Channel existing = ChatSettings.byGroup(from.publicKey(), invite.channelId());
        if (existing != null) {
            // Already in it: at most a newer key, like a rekey
            return applyKey(existing, from, invite.epoch(), invite.key());
        }
        String handle = from.publicKey() + " " + invite.channelId();
        PendingInvite pending = INVITES.get(handle);
        if (pending != null && pending.epoch() > invite.epoch()) {
            return Friends.note("[an outdated channel invitation: ignored]");
        }
        if (pending == null && INVITES.size() >= MAX_INVITES) {
            return Friends.note("[a channel invitation from " + from.name() + ": ignored, too many are waiting]");
        }
        SecretKey key = FriendCrypto.aesKey(FriendCrypto.decode(invite.key()));
        INVITES.put(handle, new PendingInvite(invite.channelId(), from.publicKey(), from.name(), invite.epoch(), key, invite.name(),
            System.currentTimeMillis()));
        MutableComponent text = Component.literal(from.name() + " invites you to the channel " + invite.name() + " ")
            .withStyle(ChatFormatting.YELLOW);
        if (!from.verified()) {
            text.append(Component.literal("(" + from.name() + " isn't verified yet) ").withStyle(ChatFormatting.GOLD));
        }
        return text.append(Friends.button("[Join]", ChatFormatting.GREEN, "Join " + invite.name(), () -> {
                String joined = join(handle);
                Friends.notifier.accept(Component.literal(joined == null ? "That invitation is gone."
                    : "Joined " + joined + ". Turn it on with the lock next to the chat box, or /pp " + joined + "."));
            }))
            .append(" ")
            .append(Friends.button("[Ignore]", ChatFormatting.GRAY, "Do nothing", () -> ignore(handle)));
    }

    // "rekey": the owner's new key for a channel we're in
    private static Component onRekey(Control control, Style style) {
        if (control.mine()) {
            return Friends.note("[new channel key]");
        }
        Friend from = sender(control);
        FriendMessages.Message message = FriendMessages.decode(control.verb(), control.body());
        if (from == null || !(message instanceof Rekey rekey)) {
            return Friends.note("[a channel key that doesn't fit: ignored]");
        }
        Channel channel = ChatSettings.byGroup(from.publicKey(), rekey.channelId());
        if (channel == null) {
            PendingInvite pending = INVITES.get(from.publicKey() + " " + rekey.channelId());
            if (ChatSettings.byGroupId(rekey.channelId()) != null && pending == null) {
                return Friends.warning("[" + from.name() + " tried to change the key of a channel they don't own: ignored]");
            }
            if (pending != null && rekey.epoch() > pending.epoch()) {
                INVITES.put(pending.handle(), new PendingInvite(pending.channelId(), pending.ownerKey(), pending.ownerName(), rekey.epoch(),
                    FriendCrypto.aesKey(FriendCrypto.decode(rekey.key())), pending.name(), pending.receivedAt()));
                return Friends.note("[a newer key for the channel " + pending.name() + " you were invited to]");
            }
            return Friends.note("[a key for a channel you're not in: ignored]");
        }
        return applyKey(channel, from, rekey.epoch(), rekey.key());
    }

    private static Component applyKey(Channel channel, Friend from, int epoch, String keyText) {
        Group group = channel.group();
        SecretKey key = FriendCrypto.aesKey(FriendCrypto.decode(keyText));
        Verdict verdict = fromOwner(group, from.publicKey(), epoch);
        if (verdict == Verdict.NOT_OWNER) {
            return Friends.warning("[" + from.name() + " tried to change the key of " + channel.name() + ", which they don't own: ignored]");
        }
        if (verdict == Verdict.OLD_EPOCH) {
            if (epoch == group.epoch() && key.equals(group.key())) {
                // Sent again because our confirmation got lost (or replayed: so only once per epoch)
                if (ACKED_AGAIN.add(group.ownerKey() + " " + group.id() + " " + epoch)) {
                    ackTo(from, group.id(), epoch);
                }
                return Friends.note("[channel " + channel.name() + ": you already have this key]");
            }
            return Friends.note("[an old key for " + channel.name() + ": ignored]");
        }
        ChatSettings.updateGroup(channel.name(), g -> g.rekeyed(epoch, key).withOwnerName(from.name()));
        ackTo(from, group.id(), epoch);
        return Component.literal("New key for the channel " + channel.name() + ": someone left or was removed. Older messages show as [old key].")
            .withStyle(ChatFormatting.YELLOW);
    }

    private static void ackTo(Friend owner, String channelId, int epoch) {
        ChannelNote ack = new ChannelNote(FriendMessages.ACK, channelId, epoch);
        Friends.queue("ack " + channelId, owner.name(), owner.keys().send(), ack, RETRY_MILLIS, true, null);
    }

    // joined, ack and leave (member to owner); removed (owner to member)
    private static Component onNote(Control control, Style style) {
        FriendMessages.Message message = FriendMessages.decode(control.verb(), control.body());
        Friend from = sender(control);
        String myKey = Friends.publicKey();
        Channel channel = null;
        if (message instanceof ChannelNote note && from != null) {
            // A removal comes from the channel's owner; the rest from members of a channel we own
            String owner = note.verb().equals(FriendMessages.REMOVED) ? from.publicKey() : myKey;
            channel = owner == null ? null : ChatSettings.byGroup(owner, note.channelId());
        } else if (message instanceof ChannelNote note && control.mine()) {
            channel = ChatSettings.byGroupId(note.channelId());
        }
        String channelName = channel == null ? "a channel" : "channel " + channel.name();
        if (control.mine()) {
            return Friends.note(switch (control.verb()) {
                case FriendMessages.JOINED -> "[joined " + channelName + "]";
                case FriendMessages.ACK -> "[got the new key of " + channelName + "]";
                case FriendMessages.LEAVE -> "[left " + channelName + "]";
                default -> "[removed you from " + channelName + "]";
            });
        }
        if (from == null || !(message instanceof ChannelNote note)) {
            return Friends.note("[a channel message that doesn't fit: ignored]");
        }
        if (channel == null || channel.group() == null) {
            if (note.verb().equals(FriendMessages.REMOVED)) {
                INVITES.values().removeIf(i -> i.channelId().equals(note.channelId()) && i.ownerKey().equals(from.publicKey()));
            }
            return Friends.note("[about a channel you're not in: ignored]");
        }
        Group group = channel.group();
        if (note.verb().equals(FriendMessages.REMOVED)) {
            Verdict verdict = fromOwner(group, from.publicKey(), note.epoch());
            if (verdict != Verdict.ACCEPT) {
                return Friends.note(verdict == Verdict.NOT_OWNER ? "[" + from.name() + " doesn't own " + channel.name() + ": ignored]"
                    : "[an old removal from " + channel.name() + ": ignored]");
            }
            ChatSettings.removeChannel(channel.name());
            return Component.literal(from.name() + " removed you from the channel " + channel.name() + ".").withStyle(ChatFormatting.YELLOW);
        }
        Verdict verdict = myKey == null ? Verdict.NOT_OWNER : fromMember(group, myKey, from.publicKey(), note.epoch());
        if (verdict != Verdict.ACCEPT) {
            return Friends.note("[" + from.name() + " about " + channel.name() + ": ignored (" + switch (verdict) {
                case NOT_OWNER -> "not your channel";
                case NOT_MEMBER -> "not a member";
                default -> "outdated";
            } + ")]");
        }
        Member member = group.member(from.publicKey());
        switch (note.verb()) {
            case FriendMessages.LEAVE -> {
                ChatSettings.updateGroup(channel.name(), g -> g.withoutMember(from.publicKey()).rekeyed(g.epoch() + 1, newKey()));
                keyChanged(group.id());
                return Component.literal(from.name() + " left the channel " + channel.name() + ". The others get a new key.")
                    .withStyle(ChatFormatting.YELLOW);
            }
            case FriendMessages.JOINED -> {
                ChatSettings.updateGroup(channel.name(), g -> g.withMember(member.withAcked(note.epoch())));
                nextCheck = 0;
                return Component.literal(from.name() + " joined the channel " + channel.name() + ".").withStyle(ChatFormatting.GREEN);
            }
            default -> {
                ChatSettings.updateGroup(channel.name(), g -> g.withMember(member.withAcked(note.epoch())));
                return Friends.note("[" + from.name() + " has the new key of " + channel.name() + "]");
            }
        }
    }

    // The friend whose key opened this control message, if it's a friend message we should act on
    private static @Nullable Friend sender(Control control) {
        if (control.writer() == null || control.stale() || control.mine()) {
            return null;
        }
        return control.extraKey() != null && control.extraKey().owner() instanceof Friend friend ? Friends.byKey(friend.publicKey()) : null;
    }

    static SecretKey newKey() {
        return FriendCrypto.aesKey(FriendCrypto.randomBytes(FriendCrypto.KEY_BYTES));
    }

    // For tests: forget invitations and retries
    static void resetForTests() {
        INVITES.clear();
        ATTEMPTS.clear();
        RESENT.clear();
        ACKED_AGAIN.clear();
        nextCheck = 0;
    }
}
