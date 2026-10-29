package org.kaiwasabi.privacyplus.chat;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import javax.crypto.SecretKey;
import org.jspecify.annotations.Nullable;

// Automatic /msg sends (key exchanges, channel keys, membership notes): held until the recipient is
// online on the server they were queued on (also over a reconnect), dropped when they expire, and spaced
// out so a server's spam filter never kicks the player.
// Sends nobody clicked for (and anything that waited for its recipient to come online) go out after a
// random pause of a few seconds to half a minute, like a person answering, rather than the instant a
// player joins. Plain logic with the clock passed in; Friends drives it from a timer with the player list.
final class SendQueue {
    static final long SPACING_MILLIS = 1500;
    static final long JITTER_MIN_MILLIS = 3_000;
    static final long JITTER_MAX_MILLIS = 30_000;

    // id identifies what the item is about (e.g. "rekey <channel> <member>"), so queuing the same thing
    // again replaces it instead of sending twice. automatic: nobody clicked for it, so it waits a random pause.
    // server: where the recipient is (a ServerAddress key, "local"), null for wherever we are.
    record Item(String id, String recipient, SecretKey key, String verb, String body, long expiresAt, boolean automatic,
                @Nullable Runnable onSent, @Nullable String server) {
        Item(String id, String recipient, SecretKey key, String verb, String body, long expiresAt, boolean automatic, @Nullable Runnable onSent) {
            this(id, recipient, key, verb, body, expiresAt, automatic, onSent, null);
        }

        boolean isFor(@Nullable String currentServer) {
            return this.server == null || this.server.equals(currentServer);
        }

        @Override
        public String toString() {
            return "Item[" + this.verb + " to " + this.recipient + "]";
        }
    }

    private final RandomGenerator random;
    private final List<Item> items = new ArrayList<>();
    // When each item may go, decided once its recipient is seen online
    private final Map<String, Long> due = new HashMap<>();
    // Items whose recipient was offline at some point: they go out a random while after they come back
    private final Set<String> waited = new HashSet<>();
    private long nextSendAt;

    SendQueue() {
        this(new SecureRandom());
    }

    SendQueue(RandomGenerator random) {
        this.random = random;
    }

    synchronized void add(Item item) {
        for (int i = 0; i < this.items.size(); i++) {
            if (this.items.get(i).id().equals(item.id())) {
                this.items.set(i, item);
                return;
            }
        }
        this.items.add(item);
    }

    synchronized boolean contains(String id) {
        return this.items.stream().anyMatch(item -> item.id().equals(id));
    }

    synchronized void removeIf(Predicate<Item> filter) {
        this.items.removeIf(filter);
        this.forgetGone();
    }

    synchronized List<Item> items() {
        return List.copyOf(this.items);
    }

    // Something else was just sent (the player chatting): the next automatic send waits a little longer
    synchronized void delay(long now) {
        this.nextSendAt = Math.max(this.nextSendAt, now + SPACING_MILLIS);
    }

    synchronized @Nullable Item poll(long now, Predicate<String> online) {
        return this.poll(now, null, online);
    }

    // The item to send now on currentServer, if any: the oldest one that is due and whose recipient is online,
    // at most one per SPACING_MILLIS. The caller sends it; it's no longer queued.
    synchronized @Nullable Item poll(long now, @Nullable String currentServer, Predicate<String> online) {
        this.items.removeIf(item -> item.expiresAt() <= now);
        this.forgetGone();
        Item ready = null;
        for (Item item : this.items) {
            if (!item.isFor(currentServer) || !online.test(item.recipient())) {
                // Pick a new moment once they're back
                this.due.remove(item.id());
                this.waited.add(item.id());
                continue;
            }
            long at = this.due.computeIfAbsent(item.id(), id -> item.automatic() || this.waited.contains(id) ? now + this.jitter() : now);
            if (ready == null && at <= now) {
                ready = item;
            }
        }
        if (ready == null || now < this.nextSendAt) {
            return null;
        }
        for (Iterator<Item> it = this.items.iterator(); it.hasNext(); ) {
            if (it.next() == ready) {
                it.remove();
            }
        }
        this.forgetGone();
        this.nextSendAt = now + SPACING_MILLIS;
        return ready;
    }

    private long jitter() {
        return this.random.nextLong(JITTER_MIN_MILLIS, JITTER_MAX_MILLIS + 1);
    }

    private void forgetGone() {
        Set<String> ids = new HashSet<>();
        this.items.forEach(item -> ids.add(item.id()));
        this.due.keySet().retainAll(ids);
        this.waited.retainAll(ids);
    }
}
