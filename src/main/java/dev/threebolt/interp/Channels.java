package dev.threebolt.interp;

import dev.threebolt.interp.Values.Int;
import dev.threebolt.interp.Values.Obj;
import dev.threebolt.interp.Values.Unit;
import dev.threebolt.types.FnInfo;
import dev.threebolt.types.Prim;
import dev.threebolt.types.Type;
import dev.threebolt.types.TypeParamInfo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * `std::sync`'s `Channel` (§13.4) and the engine that waits on several channel operations at once,
 * for `select` (§7.14); a single `send` or `recv` is a wait on one case.
 *
 * <p>Each channel has a lock, a buffer and queues of waiting senders and receivers (Go's design). A
 * thread that must wait enqueues a {@link Waiter} per case, all sharing one {@link Group}; whoever
 * completes a case first wins the group by a compare-and-set of its state, so exactly one case
 * happens. The waiting thread can also win it itself, to give up on a timeout or cancellation.
 */
final class Channels {
    // Keep in step with the `OP_*` constants in std/sync.3b.
    static final int OP_DONE = 0, OP_CLOSED = 1, OP_TIMED_OUT = 2, OP_CANCELLED = 3;

    private final Callbacks rt;
    private final Threads threads;
    private final AtomicLong nextId = new AtomicLong();
    /** The item the current thread's last receive took, until `received` collects it. */
    private final ThreadLocal<Object> received = new ThreadLocal<>();

    Channels(Callbacks rt, Threads threads) {
        this.rt = rt;
        this.threads = threads;
    }

    /** A channel; everything but {@code id} and {@code capacity} is guarded by {@code lock}. */
    static final class Chan {
        final long id;
        final int capacity;
        final ReentrantLock lock = new ReentrantLock();
        final ArrayDeque<Object> buffer = new ArrayDeque<>();
        boolean closed;
        final ArrayDeque<Waiter> senders = new ArrayDeque<>();
        final ArrayDeque<Waiter> receivers = new ArrayDeque<>();

        Chan(long id, int capacity) {
            this.id = id;
            this.capacity = capacity;
        }
    }

    /** One wait: {@code state} is {@link #PENDING} until a case fires (its index) or the waiter gives up. */
    private static final class Group {
        static final int PENDING = -1, ABANDONED = -2;
        final Thread thread = Thread.currentThread();
        final AtomicInteger state = new AtomicInteger(PENDING);

        boolean fire(int index) {
            return state.compareAndSet(PENDING, index);
        }
    }

    /** A thread waiting in one case. The waker writes {@code value}/{@code closed} before firing the group. */
    private static final class Waiter {
        final Group group;
        final int index;
        Object value;
        boolean closed;

        Waiter(Group group, int index, Object value) {
            this.group = group;
            this.index = index;
            this.value = value;
        }
    }

    /** A send of {@code value} (already copied) or a receive, on {@code chan}. */
    record Case(Chan chan, boolean send, Object value) {}

    /** How a wait ended: {@code index} is the case that happened, or -1 if none did ({@code code} says why). */
    record Outcome(int index, int code, Object value) {
        static final Outcome TIMED_OUT = new Outcome(-1, OP_TIMED_OUT, null);
        static final Outcome CANCELLED = new Outcome(-1, OP_CANCELLED, null);
    }

    Object call(String name, Object self, Object[] a, Map<TypeParamInfo, Type> typeEnv, FnInfo fn) {
        return switch (name) {
            case "new" -> {
                long capacity = ((Int) a[0]).v();
                var ch = new Obj((dev.threebolt.types.TypeInfo) fn.owner, List.of(typeEnv.getOrDefault(
                        ((dev.threebolt.types.TypeInfo) fn.owner).typeParams.getFirst(), Type.ERROR)), new Object[0]);
                ch.nat = new Chan(nextId.getAndIncrement(), (int) Math.min(capacity, Integer.MAX_VALUE));
                yield ch;
            }
            case "op_send" -> {
                var c = new Case(chan(self), true, Values.copy(a[0]));
                var out = await(new Case[] {c}, ((Int) a[1]).v(), (Boolean) a[2]);
                yield code(out);
            }
            case "op_recv" -> {
                var out = await(new Case[] {new Case(chan(self), false, null)}, ((Int) a[0]).v(), true);
                if (out.index >= 0 && out.code == OP_DONE) received.set(out.value);
                yield code(out);
            }
            case "received" -> {
                var v = received.get();
                received.remove();
                yield v;
            }
            case "close" -> {
                close(chan(self));
                yield Unit.UNIT;
            }
            case "len" -> locked(chan(self), c -> (long) c.buffer.size());
            case "capacity" -> new Int(Prim.USIZE, chan(self).capacity);
            case "closed" -> locked(chan(self), c -> c.closed);
            default -> throw new IllegalStateException("unknown intrinsic Channel." + name);
        };
    }

    private static Chan chan(Object self) {
        return (Chan) ((Obj) self).nat;
    }

    private static Int code(Outcome out) {
        return new Int(Prim.I32, out.code);
    }

    private static Object locked(Chan c, java.util.function.Function<Chan, Object> f) {
        c.lock.lock();
        try {
            var v = f.apply(c);
            return v instanceof Long n ? new Int(Prim.USIZE, n) : v;
        } finally {
            c.lock.unlock();
        }
    }

    // ---- The engine ----

    /**
     * Performs exactly one of {@code cases}, waiting up to {@code nanos} ({@link Long#MAX_VALUE}: forever;
     * zero or less: no wait) for one to be ready; a ready case is picked at random. {@code cancellable}:
     * a cancellation point (§13.4), which yields {@link Outcome#CANCELLED} on a cancelled thread.
     */
    Outcome await(Case[] cases, long nanos, boolean cancellable) {
        if (cancellable && threads.currentCancelled()) return Outcome.CANCELLED;
        long deadline = System.nanoTime() + nanos;
        var chans = distinct(cases);
        Group group;
        Waiter[] waiters;
        lockAll(chans);
        try {
            var ready = tryEach(cases);
            if (ready != null) return ready;
            if (nanos <= 0) return Outcome.TIMED_OUT;
            group = new Group();
            waiters = new Waiter[cases.length];
            for (int i = 0; i < cases.length; i++) {
                var c = cases[i];
                waiters[i] = new Waiter(group, i, c.value());
                (c.send() ? c.chan().senders : c.chan().receivers).add(waiters[i]);
            }
        } finally {
            unlockAll(chans);
        }
        try {
            while (true) {
                int s = group.state.get();
                if (s >= 0) {
                    var w = waiters[s];
                    return new Outcome(s, w.closed ? OP_CLOSED : OP_DONE, w.value);
                }
                if (Thread.interrupted()) {
                    // Cancellation or the process ending (§13.4); otherwise a stale interrupt, now cleared.
                    if (rt.isTerminating()) {
                        group.state.compareAndSet(Group.PENDING, Group.ABANDONED);
                        throw new Callbacks.Abort();
                    }
                    if (cancellable && threads.currentCancelled() && group.state.compareAndSet(Group.PENDING, Group.ABANDONED)) {
                        return Outcome.CANCELLED;
                    }
                    continue;
                }
                if (nanos == Long.MAX_VALUE) {
                    LockSupport.park(this);
                } else {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        if (group.state.compareAndSet(Group.PENDING, Group.ABANDONED)) return Outcome.TIMED_OUT;
                        continue;
                    }
                    LockSupport.parkNanos(this, left);
                }
            }
        } finally {
            lockAll(chans);
            try {
                for (int i = 0; i < cases.length; i++) {
                    var c = cases[i];
                    (c.send() ? c.chan().senders : c.chan().receivers).remove(waiters[i]);
                }
            } finally {
                unlockAll(chans);
            }
        }
    }

    /** The first ready case, scanning in random order, performed; null if none is ready. Holds every lock. */
    private Outcome tryEach(Case[] cases) {
        int n = cases.length;
        int start = n == 1 ? 0 : ThreadLocalRandom.current().nextInt(n);
        for (int k = 0; k < n; k++) {
            int i = (start + k) % n;
            var c = cases[i];
            var out = c.send() ? trySend(c.chan(), c.value(), i) : tryRecv(c.chan(), i);
            if (out != null) return out;
        }
        return null;
    }

    private static Outcome trySend(Chan ch, Object v, int index) {
        if (ch.closed) return new Outcome(index, OP_CLOSED, null);
        Waiter w;
        while ((w = ch.receivers.poll()) != null) {
            w.value = v;
            if (w.group.fire(w.index)) {
                LockSupport.unpark(w.group.thread);
                return new Outcome(index, OP_DONE, null);
            }
        }
        if (ch.buffer.size() < ch.capacity) {
            ch.buffer.add(v);
            return new Outcome(index, OP_DONE, null);
        }
        return null;
    }

    private static Outcome tryRecv(Chan ch, int index) {
        if (!ch.buffer.isEmpty()) {
            var v = ch.buffer.poll();
            // A sender waiting on the full buffer moves its item in.
            Waiter w;
            while ((w = ch.senders.poll()) != null) {
                if (w.group.fire(w.index)) {
                    ch.buffer.add(w.value);
                    LockSupport.unpark(w.group.thread);
                    break;
                }
            }
            return new Outcome(index, OP_DONE, v);
        }
        Waiter w;
        while ((w = ch.senders.poll()) != null) {
            var v = w.value;
            if (w.group.fire(w.index)) {
                LockSupport.unpark(w.group.thread);
                return new Outcome(index, OP_DONE, v);
            }
        }
        if (ch.closed) return new Outcome(index, OP_CLOSED, null);
        return null;
    }

    /** Closes {@code ch} (§13.4): every waiting sender and receiver gets `ClosedErr`. Idempotent. */
    static void close(Chan ch) {
        ch.lock.lock();
        try {
            if (ch.closed) return;
            ch.closed = true;
            for (var queue : List.of(ch.receivers, ch.senders)) {
                Waiter w;
                while ((w = queue.poll()) != null) {
                    w.closed = true;
                    if (w.group.fire(w.index)) LockSupport.unpark(w.group.thread);
                }
            }
        } finally {
            ch.lock.unlock();
        }
    }

    /** The channels of {@code cases}, each once, in lock order. */
    private static Chan[] distinct(Case[] cases) {
        var list = new ArrayList<Chan>();
        for (var c : cases) if (!list.contains(c.chan())) list.add(c.chan());
        var chans = list.toArray(new Chan[0]);
        Arrays.sort(chans, Comparator.comparingLong(c -> c.id));
        return chans;
    }

    private static void lockAll(Chan[] chans) {
        for (var c : chans) c.lock.lock();
    }

    private static void unlockAll(Chan[] chans) {
        for (int i = chans.length - 1; i >= 0; i--) chans[i].lock.unlock();
    }
}
