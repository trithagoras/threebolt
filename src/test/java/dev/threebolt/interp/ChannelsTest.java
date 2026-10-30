package dev.threebolt.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.threebolt.interp.Channels.Case;
import dev.threebolt.interp.Channels.Chan;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The channel engine performs exactly one case per wait, also under contention (§7.14, §13.4). */
class ChannelsTest {
    private static final long FOREVER = Long.MAX_VALUE;

    /** A process that never ends; the engine asks nothing else of it. */
    private static final Callbacks RT = (Callbacks) Proxy.newProxyInstance(Callbacks.class.getClassLoader(),
            new Class<?>[] {Callbacks.class}, (proxy, m, args) -> m.getReturnType() == boolean.class ? false : null);

    private final Channels channels = new Channels(RT, new Threads(RT));
    private final AtomicLong ids = new AtomicLong();

    private Chan chan(int capacity) {
        return new Chan(ids.getAndIncrement(), capacity);
    }

    private static Thread run(Runnable r) {
        return Thread.ofVirtual().start(r);
    }

    @Test
    void everyItemIsReceivedExactlyOnceAcrossSelects() throws InterruptedException {
        var a = chan(0);
        var b = chan(2);
        int items = 4000;
        var got = Collections.newSetFromMap(new ConcurrentHashMap<Object, Boolean>());
        var count = new AtomicLong();
        var threads = new ArrayList<Thread>();
        for (int r = 0; r < 8; r++) {
            threads.add(run(() -> {
                while (true) {
                    var out = channels.await(new Case[] {new Case(a, false, null), new Case(b, false, null)}, FOREVER, true);
                    if (out.code() == Channels.OP_CLOSED) return;
                    assertTrue(got.add(out.value()), "received twice: " + out.value());
                    count.incrementAndGet();
                }
            }));
        }
        var senders = new ArrayList<Thread>();
        for (int s = 0; s < 4; s++) {
            int base = s * items;
            senders.add(run(() -> {
                for (int i = 0; i < items; i++) channels.await(new Case[] {new Case(i % 2 == 0 ? a : b, true, base + i)}, FOREVER, true);
            }));
        }
        for (var t : senders) t.join();
        // Close once both are drained: a receiver stops at the first closed case it sees.
        while (count.get() < 4L * items) Thread.onSpinWait();
        Channels.close(a);
        Channels.close(b);
        for (var t : threads) t.join();
        assertEquals(4L * items, count.get());
        assertEquals(4 * items, got.size());
    }

    @Test
    void aSelectThatSendsAndReceivesDoesOnlyOne() throws InterruptedException {
        var in = chan(0);
        var out = chan(0);
        var partner = run(() -> channels.await(new Case[] {new Case(in, true, "x")}, FOREVER, true));
        var outcome = channels.await(new Case[] {new Case(in, false, null), new Case(out, true, "y")}, FOREVER, true);
        partner.join();
        assertEquals(0, outcome.index());
        assertEquals("x", outcome.value());
        // The send case was withdrawn: nobody can receive "y" now.
        assertEquals(Channels.OP_TIMED_OUT, channels.await(new Case[] {new Case(out, false, null)}, 0, true).code());
    }
}
