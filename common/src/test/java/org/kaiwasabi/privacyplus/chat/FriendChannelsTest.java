package org.kaiwasabi.privacyplus.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import javax.crypto.SecretKey;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Group;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Member;
import org.kaiwasabi.privacyplus.chat.ChatSettings.MemberState;
import org.kaiwasabi.privacyplus.chat.FriendChannels.Verdict;
import org.kaiwasabi.privacyplus.chat.Friends.Friend;
import org.junit.jupiter.api.Test;

// Membership rules of friend channels, without a game: who may change what, and replays
class FriendChannelsTest {
    private static final String OWNER = FriendCrypto.generate().publicKey();
    private static final String BOB = FriendCrypto.generate().publicKey();
    private static final String CAROL = FriendCrypto.generate().publicKey();
    private static final String MALLORY = FriendCrypto.generate().publicKey();

    private static Friend friend(String key, String name) {
        FriendCrypto.PairKeys keys = new FriendCrypto.PairKeys(FriendChannels.newKey(), FriendChannels.newKey(), FriendChannels.newKey());
        return new Friend(key, name, "Owner", name, true, "0000 0000 0000 0000", true, 0, null, null, keys, List.of(), "Owner");
    }

    private static Group group() {
        Group empty = new Group(FriendMessages.newChannelId(), OWNER, "Owner", 1, FriendChannels.newKey(), List.of(), List.of());
        return FriendChannels.withMembers(empty, List.of(friend(BOB, "Bob"), friend(CAROL, "Carol")), FriendChannels.newKey(), 1000);
    }

    @Test
    void newMembersAreInvitedWithoutChangingTheKey() {
        Group group = group();
        assertEquals(1, group.epoch());
        assertEquals(2, group.members().size());
        Member bob = group.member(BOB);
        assertEquals(MemberState.INVITE, bob.state());
        assertEquals(1, bob.addedEpoch());
        assertEquals(1000, bob.since());
    }

    @Test
    void removingSomeoneGivesEveryoneElseANewKey() {
        Group before = group();
        SecretKey newKey = FriendChannels.newKey();
        Group after = FriendChannels.withMembers(before, List.of(friend(BOB, "Bob")), newKey, 2000);
        assertEquals(2, after.epoch());
        assertEquals(newKey, after.key());
        assertNull(after.member(CAROL));
        assertNotNull(after.member(BOB));
        assertEquals(before.key(), after.oldKeys().getFirst().key(), "the old key is kept to read older messages");
        assertEquals(1, after.oldKeys().getFirst().epoch());
    }

    @Test
    void addingSomeoneLater() {
        Group group = FriendChannels.withMembers(group(), List.of(friend(BOB, "Bob"), friend(CAROL, "Carol"), friend(MALLORY, "Dave")),
            FriendChannels.newKey(), 3000);
        assertEquals(1, group.epoch(), "nobody left");
        assertEquals(3, group.members().size());
    }

    @Test
    void onlyTheOwnerChangesTheKey() {
        Group group = group();
        assertEquals(Verdict.ACCEPT, FriendChannels.fromOwner(group, OWNER, 2));
        assertEquals(Verdict.NOT_OWNER, FriendChannels.fromOwner(group, BOB, 2), "a member can't rekey");
        assertEquals(Verdict.NOT_OWNER, FriendChannels.fromOwner(group, MALLORY, 5), "nor can an outsider");
    }

    @Test
    void oldEpochsAreReplays() {
        Group group = group().rekeyed(3, FriendChannels.newKey());
        assertEquals(Verdict.OLD_EPOCH, FriendChannels.fromOwner(group, OWNER, 3), "the current epoch again");
        assertEquals(Verdict.OLD_EPOCH, FriendChannels.fromOwner(group, OWNER, 2), "an older one");
        assertEquals(Verdict.ACCEPT, FriendChannels.fromOwner(group, OWNER, 4));
    }

    @Test
    void ownerOnlyHearsFromMembersAboutTheirMembership() {
        Group group = group();
        assertEquals(Verdict.ACCEPT, FriendChannels.fromMember(group, OWNER, BOB, 1));
        assertEquals(Verdict.NOT_MEMBER, FriendChannels.fromMember(group, OWNER, MALLORY, 1));
        assertEquals(Verdict.NOT_OWNER, FriendChannels.fromMember(group, BOB, CAROL, 1), "members don't manage the channel");
        assertEquals(Verdict.OLD_EPOCH, FriendChannels.fromMember(group, OWNER, BOB, 2), "an epoch that doesn't exist yet");

        // Carol is removed (epoch 2) and added again (epoch 2 too: no one left in between, so addedEpoch 2)
        Group removed = FriendChannels.withMembers(group, List.of(friend(BOB, "Bob")), FriendChannels.newKey(), 2000);
        Group readded = FriendChannels.withMembers(removed, List.of(friend(BOB, "Bob"), friend(CAROL, "Carol")), FriendChannels.newKey(), 3000);
        assertEquals(2, readded.member(CAROL).addedEpoch());
        assertEquals(Verdict.OLD_EPOCH, FriendChannels.fromMember(readded, OWNER, CAROL, 1), "a leave or join from her earlier membership");
        assertEquals(Verdict.ACCEPT, FriendChannels.fromMember(readded, OWNER, CAROL, 2));
    }

    @Test
    void onlyAFewOldKeysAreKept() {
        Group group = group();
        for (int epoch = 2; epoch <= 20; epoch++) {
            group = group.rekeyed(epoch, FriendChannels.newKey());
        }
        assertEquals(ChatSettings.MAX_OLD_KEYS, group.oldKeys().size());
        assertEquals(19, group.oldKeys().getFirst().epoch(), "newest first");
        assertNotEquals(group.key(), group.oldKeys().getFirst().key());
    }

    @Test
    void ackingRaisesTheConfirmedEpoch() {
        Member bob = group().member(BOB);
        Member joined = bob.withAcked(1);
        assertEquals(MemberState.JOINED, joined.state());
        assertEquals(1, joined.ackedEpoch());
        assertEquals(3, joined.withAcked(3).withAcked(2).ackedEpoch(), "a late ack for an older epoch doesn't lower it");
    }
}
