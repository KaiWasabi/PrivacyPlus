package org.kaiwasabi.privacyplus.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class SendQueueTest {
    // Always picks the same pause, so the timing below is exact
    private static final long PAUSE = 10_000;
    private static final RandomGenerator FIXED = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public long nextLong(long origin, long bound) {
            return PAUSE;
        }
    };

    private static SendQueue.Item item(String id, String to, long expiresAt) {
        return item(id, to, expiresAt, false);
    }

    private static SendQueue.Item item(String id, String to, long expiresAt, boolean automatic) {
        return new SendQueue.Item(id, to, ChatCryptoTest.KEY, "rekey", "1 x 2 y", expiresAt, automatic, null);
    }

    @Test
    void sendsAtMostOneEveryOneAndAHalfSeconds() {
        SendQueue queue = new SendQueue(FIXED);
        queue.add(item("a", "Alice", Long.MAX_VALUE));
        queue.add(item("b", "Alice", Long.MAX_VALUE));
        queue.add(item("c", "Alice", Long.MAX_VALUE));
        assertEquals("a", queue.poll(1000, name -> true).id());
        assertNull(queue.poll(1000, name -> true), "same moment");
        assertNull(queue.poll(2499, name -> true), "1.499 s later");
        assertEquals("b", queue.poll(2500, name -> true).id());
        assertEquals("c", queue.poll(4000, name -> true).id());
        assertNull(queue.poll(9000, name -> true), "empty");
    }

    @Test
    void waitsForTheRecipientToBeOnlineThenARandomWhile() {
        SendQueue queue = new SendQueue(FIXED);
        queue.add(item("for bob", "Bob", Long.MAX_VALUE));
        queue.add(item("for alice", "Alice", Long.MAX_VALUE));
        assertEquals("for alice", queue.poll(0, Set.of("Alice")::contains).id(), "Bob is offline, so Alice's goes first");
        assertNull(queue.poll(10_000, Set.of("Alice")::contains));
        assertNull(queue.poll(20_000, Set.of("Bob")::contains), "not the moment Bob joins, which would give it away");
        assertNull(queue.poll(20_000 + PAUSE - 1, Set.of("Bob")::contains));
        assertEquals("for bob", queue.poll(20_000 + PAUSE, Set.of("Bob")::contains).id(), "a while after he showed up");
    }

    @Test
    void automaticSendsWaitARandomWhileEvenIfTheRecipientIsOnline() {
        SendQueue queue = new SendQueue(FIXED);
        queue.add(item("clicked", "Alice", Long.MAX_VALUE));
        queue.add(item("automatic", "Alice", Long.MAX_VALUE, true));
        assertEquals("clicked", queue.poll(0, name -> true).id(), "what the player clicked for goes right away");
        assertNull(queue.poll(5000, name -> true));
        assertEquals("automatic", queue.poll(PAUSE, name -> true).id());
    }

    @Test
    void pausesAreBetweenAFewSecondsAndHalfAMinute() {
        Set<Long> seen = new HashSet<>();
        SplittableRandom random = new SplittableRandom(42);
        for (int i = 0; i < 200; i++) {
            SendQueue queue = new SendQueue(random);
            queue.add(item("x", "Alice", Long.MAX_VALUE, true));
            long sentAt = -1;
            for (long now = 0; now <= 40_000 && sentAt < 0; now += 100) {
                if (queue.poll(now, name -> true) != null) {
                    sentAt = now;
                }
            }
            assertTrue(sentAt >= SendQueue.JITTER_MIN_MILLIS && sentAt <= SendQueue.JITTER_MAX_MILLIS + 100, "sent after " + sentAt + " ms");
            seen.add(sentAt / 1000);
        }
        assertTrue(seen.size() > 10, "and they vary: " + seen);
    }

    @Test
    void itemsWaitForTheServerTheyWereQueuedOn() {
        SendQueue queue = new SendQueue(FIXED);
        queue.add(new SendQueue.Item("joined", "Alice", ChatCryptoTest.KEY, "joined", "1 x 1", Long.MAX_VALUE, false, null,
            "play.example.com:25565"));
        assertNull(queue.poll(0, "other.example.net:25565", name -> true), "another server's Alice may be someone else");
        assertEquals(1, queue.items().size(), "kept over a reconnect");
        assertNull(queue.poll(1000, "play.example.com:25565", name -> true), "back on the right server, after a random while");
        assertEquals("joined", queue.poll(1000 + PAUSE, "play.example.com:25565", name -> true).id());
        assertTrue(queue.items().isEmpty());
    }

    @Test
    void itemsDontPrintWhatTheyCarry() {
        SendQueue.Item item = item("rekey", "Alice", Long.MAX_VALUE);
        assertFalse(item.toString().contains("1 x 2 y"), item.toString());
    }

    @Test
    void dropsExpiredItems() {
        SendQueue queue = new SendQueue(FIXED);
        queue.add(item("old", "Bob", 5000));
        assertNull(queue.poll(1000, name -> false));
        assertNull(queue.poll(5000, name -> true), "expired before Bob came online");
        assertTrue(queue.items().isEmpty());
    }

    @Test
    void sameIdReplacesInsteadOfSendingTwice() {
        SendQueue queue = new SendQueue(FIXED);
        queue.add(item("rekey squad bob", "Bob", Long.MAX_VALUE));
        queue.add(new SendQueue.Item("rekey squad bob", "Bob", ChatCryptoTest.KEY, "rekey", "newer", Long.MAX_VALUE, false, null));
        assertEquals(1, queue.items().size());
        assertEquals("newer", queue.poll(0, name -> true).body());
        assertNull(queue.poll(100_000, name -> true));
    }

    @Test
    void thePlayersOwnMessagesDelayIt() {
        SendQueue queue = new SendQueue(FIXED);
        queue.add(item("a", "Alice", Long.MAX_VALUE));
        queue.delay(1000);
        assertNull(queue.poll(2000, name -> true), "the player chatted at 1.0 s");
        assertEquals("a", queue.poll(2500, name -> true).id());
    }
}
