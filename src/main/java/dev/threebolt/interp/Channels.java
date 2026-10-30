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
 * `std::sync`'s `Channel` (§13.4) and the engine that waits on several operations at once, for
 * `select` (§7.14): channel sends and receives, and threads finishing. A single `send` or `recv`
 * is a wait on one case.
 *
 * <p>Each channel has a lock, a buffer and queues of waiting senders and receivers (Go's design). A
 * thread that must wait enqueues a {@link Waiter} per case, all sharing one {@link Group}; whoever
 * completes a case first wins the group by a compare-and-set of its state, so exactly one case
 * happens. The waiting thread can also win it itself, to give up on a timeout or cancellation.
 */
final class Channels {
    // Keep in step with the `OP_*` constants in std/sync.3b.
    static final int OP_DONE = 0, OP_CLOSED = 1, OP_TIMED_OUT = 2, OP_CANCELLED = 3;
    /** A `select`'s `after(d)` whose time had already come was chosen (internal; not in std/sync.3b). */
    static final int OP_AFTER = 4;
    private static final AtomicLong IDS = new AtomicLong();

    private final Callbacks rt;
    private final Threads threads;
    /** The item the current thread's last receive took, until `received` collects it. */
    private final ThreadLocal<Object> received = new ThreadLocal<>();

    Channels(Callbacks rt, Threads threads) {
        this.rt = rt;
        this.threads = threads;
    }

    /** Something a wait can wait on; {@code id} orders the locks of a wait on several. */
    abstract static class Source {
        final long id = IDS.getAndIncrement();
        final ReentrantLock lock = new ReentrantLock();
    }

    /** A channel; everything but {@code capacity} is guarded by {@code lock}. */
    static final class Chan extends Source {
        final int capacity;
        final ArrayDeque<Object> buffer = new ArrayDeque<>();
        boolean closed;
        final ArrayDeque<Waiter> senders = new ArrayDeque<>();
        final ArrayDeque<Waiter> receivers = new ArrayDeque<>();

        Chan(int capacity) {
            this.capacity = capacity;
        }
    }

    /** A thread's completion, for `select`'s `join` case; guarded by {@code lock}. */
    static final class Completion extends Source {
        boolean finished;
        Object result;
        final ArrayDeque<Waiter> joiners = new ArrayDeque<>();
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

    enum Op { SEND, RECV, JOIN }

    /** A send of {@code value} (already copied) or a receive on a {@link Chan}, or a join of a {@link Completion}. */
    record Case(Source source, Op op, Object value) {
        static Case send(Chan ch, Object value) {
            return new Case(ch, Op.SEND, value);
        }

        static Case recv(Chan ch) {
            return new Case(ch, Op.RECV, null);
        }

        static Case join(Completion c) {
            return new Case(c, Op.JOIN, null);
        }

        ArrayDeque<Waiter> queue() {
            return switch (op) {
                case SEND -> ((Chan) source).senders;
                case RECV -> ((Chan) source).receivers;
                case JOIN -> ((Completion) source).joiners;
            };
        }
    }

    /** How a wait ended: {@code index} is the case that happened, or -1 if none did ({@code code} says why). */
    record Outcome(int index, int code, Object value) {
        static final Outcome TIMED_OUT = new Outcome(-1, OP_TIMED_OUT, null);
        static final Outcome CANCELLED = new Outcome(-1, OP_CANCELLED, null);
        static final Outcome AFTER = new Outcome(-1, OP_AFTER, null);
    }

    Object call(String name, Object self, Object[] a, Map<TypeParamInfo, Type> typeEnv, FnInfo fn) {
        return switch (name) {
            case "new" -> {
                long capacity = ((Int) a[0]).v();
                var ch = new Obj((dev.threebolt.types.TypeInfo) fn.owner, List.of(typeEnv.getOrDefault(
                        ((dev.threebolt.types.TypeInfo) fn.owner).typeParams.getFirst(), Type.ERROR)), new Object[0]);
                ch.nat = new Chan((int) Math.min(capacity, Integer.MAX_VALUE));
                yield ch;
            }
            case "op_send" -> {
                var out = await(new Case[] {Case.send(chan(self), Values.copy(a[0]))}, ((Int) a[1]).v(), (Boolean) a[2], false);
                yield code(out);
            }
            case "op_recv" -> {
                var out = await(new Case[] {Case.recv(chan(self))}, ((Int) a[0]).v(), true, false);
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
     * zero or less: no wait) for one to be ready; a ready case is picked uniformly at random.
     * {@code cancellable}: a cancellation point (§13.4), which yields {@link Outcome#CANCELLED} on a
     * cancelled thread. {@code after}: one more case, always ready, that performs nothing
     * ({@link Outcome#AFTER}; a `select`'s `after(d)` whose time has come).
     */
    Outcome await(Case[] cases, long nanos, boolean cancellable, boolean after) {
        if (cancellable && threads.currentCancelled()) return Outcome.CANCELLED;
        long deadline = System.nanoTime() + nanos;
        var chans = distinct(cases);
        Group group;
        Waiter[] waiters;
        lockAll(chans);
        try {
            var ready = tryEach(cases, after);
            if (ready != null) return ready;
            if (nanos <= 0) return Outcome.TIMED_OUT;
            group = new Group();
            waiters = new Waiter[cases.length];
            for (int i = 0; i < cases.length; i++) {
                var c = cases[i];
                waiters[i] = new Waiter(group, i, c.value());
                c.queue().add(waiters[i]);
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
                for (int i = 0; i < cases.length; i++) cases[i].queue().remove(waiters[i]);
            } finally {
                unlockAll(chans);
            }
        }
    }

    /**
     * The first ready case in a random order, performed; null if none is ready. With {@code after}, a
     * further case that is always ready. Holds every lock.
     */
    private Outcome tryEach(Case[] cases, boolean after) {
        int n = cases.length + (after ? 1 : 0);
        var order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        var random = ThreadLocalRandom.current();
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        for (int i : order) {
            if (i == cases.length) return Outcome.AFTER;
            var c = cases[i];
            var out = switch (c.op()) {
                case SEND -> trySend((Chan) c.source(), c.value(), i);
                case RECV -> tryRecv((Chan) c.source(), i);
                case JOIN -> tryJoin((Completion) c.source(), i);
            };
            if (out != null) return out;
        }
        return null;
    }

    private static Outcome tryJoin(Completion c, int index) {
        return c.finished ? new Outcome(index, OP_DONE, c.result) : null;
    }

    /** Records that a thread finished with {@code result}, waking the selects that join it. */
    static void complete(Completion c, Object result) {
        c.lock.lock();
        try {
            c.finished = true;
            c.result = result;
            Waiter w;
            while ((w = c.joiners.poll()) != null) {
                w.value = result;
                if (w.group.fire(w.index)) LockSupport.unpark(w.group.thread);
            }
        } finally {
            c.lock.unlock();
        }
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

    /** The sources of {@code cases}, each once, in lock order. */
    private static Source[] distinct(Case[] cases) {
        var list = new ArrayList<Source>();
        for (var c : cases) if (!list.contains(c.source())) list.add(c.source());
        var sources = list.toArray(new Source[0]);
        Arrays.sort(sources, Comparator.comparingLong(c -> c.id));
        return sources;
    }

    private static void lockAll(Source[] chans) {
        for (var c : chans) c.lock.lock();
    }

    private static void unlockAll(Source[] chans) {
        for (int i = chans.length - 1; i >= 0; i--) chans[i].lock.unlock();
    }
}
