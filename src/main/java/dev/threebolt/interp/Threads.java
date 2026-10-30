package dev.threebolt.interp;

import dev.threebolt.interp.Values.FnVal;
import dev.threebolt.interp.Values.Int;
import dev.threebolt.interp.Values.Obj;
import dev.threebolt.interp.Values.Unit;
import dev.threebolt.types.FnInfo;
import dev.threebolt.types.Prim;
import dev.threebolt.types.Type;
import dev.threebolt.types.TypeInfo;
import dev.threebolt.types.TypeParamInfo;
import dev.threebolt.types.TypeSystem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongBinaryOperator;

/**
 * `std::thread` and `std::sync` (§13.4). `Thread`, `Scope`, `Atomic` and `sync`'s private `RawLock`
 * are std classes whose Java state is kept in {@link Obj#nat}.
 *
 * <p>Cancellation: each Threebolt thread has a {@link Strand} whose sticky flag is the truth. A Java
 * interrupt only wakes a thread blocked in a cancellation point, which then reads the flag; so code
 * that waits uninterruptibly (a scope's wait, {@code RawLock}) simply retries after an interrupt. The
 * process ending ({@link Callbacks#isTerminating}) interrupts too, and wins.
 */
final class Threads {
    private final Callbacks rt;
    private final Channels channels;

    Threads(Callbacks rt) {
        this.rt = rt;
        this.channels = new Channels(rt, this);
    }

    /** A Threebolt thread: its cancellation state, the scopes it owns and, once finished, its result. */
    private static final class Strand {
        /** Set by the thread itself when it starts (null until then; cancelling it needs no wake-up). */
        volatile Thread thread;
        volatile boolean cancelled;
        /** In a region that cancelling must not interrupt ({@link #uninterruptibly}). */
        volatile boolean shielded;
        volatile Object result;
        final CountDownLatch done = new CountDownLatch(1);
        /** The scope that owns it; null for a detached thread (and `main`). */
        ScopeState scope;
        /** The scopes whose `scope` call it is in; guarded by {@link #tree}. */
        final List<ScopeState> owned = new ArrayList<>();
    }

    /** A `Scope`; guarded by {@link #tree}. */
    private static final class ScopeState {
        final Strand owner;
        boolean cancelled;
        boolean ended;
        final Set<Strand> children = new HashSet<>();

        ScopeState(Strand owner) {
            this.owner = owner;
        }
    }

    /** Guards the scope tree: owned scopes, children, and setting cancelled flags. */
    private final Object tree = new Object();

    /** The current thread's strand; created on first use for `main` (and C threads calling back). */
    private final ThreadLocal<Strand> current = ThreadLocal.withInitial(() -> {
        var s = new Strand();
        s.thread = Thread.currentThread();
        return s;
    });

    // Keep in step with the `WAIT_*` constants in std/thread.3b.
    private static final int WAIT_DONE = 0, WAIT_TIMED_OUT = 1, WAIT_CANCELLED = 2;

    Object call(String owner, String name, Object self, Object[] a, Map<TypeParamInfo, Type> typeEnv, FnInfo fn) {
        if (owner.equals("Channel")) return channels.call(name, self, a, typeEnv, fn);
        return switch (owner + "." + name) {
            case "thread.sleep_nanos" -> sleep(((Int) a[0]).v());
            case "thread.cancelled" -> current.get().cancelled;
            case "Thread.spawn" -> start(new Strand(), (FnVal) a[0], construct(fn, typeEnv));
            case "Thread.wait" -> new Int(Prim.I32, await(strand(self), ((Int) a[0]).v()));
            case "Thread.result" -> {
                // The thread may have ended the process (a panic) rather than returned.
                rt.checkAbort();
                yield Values.copy(strand(self).result);
            }
            case "Thread.cancel" -> {
                synchronized (tree) {
                    cancel(strand(self));
                }
                yield Unit.UNIT;
            }
            case "Thread.finished" -> strand(self).done.getCount() == 0;
            case "Scope.open" -> {
                var scope = new ScopeState(current.get());
                synchronized (tree) {
                    scope.cancelled = scope.owner.cancelled;
                    scope.owner.owned.add(scope);
                }
                var obj = new Obj((TypeInfo) fn.owner, List.of(), new Object[0]);
                obj.nat = scope;
                yield obj;
            }
            case "Scope.close" -> {
                close((ScopeState) ((Obj) self).nat);
                yield Unit.UNIT;
            }
            case "Scope.spawn" -> {
                var scope = (ScopeState) ((Obj) self).nat;
                var child = new Strand();
                synchronized (tree) {
                    if (scope.ended) throw new Panic("scope has ended");
                    child.scope = scope;
                    child.cancelled = scope.cancelled;
                    scope.children.add(child);
                }
                var type = (Type.Nominal) TypeSystem.subst(fn.ret, typeEnv);
                yield start(child, (FnVal) a[0], new Obj(type.decl(), type.args(), new Object[0]));
            }
            case "Scope.cancel" -> {
                synchronized (tree) {
                    cancel((ScopeState) ((Obj) self).nat);
                }
                yield Unit.UNIT;
            }
            case "Scope.cancelled" -> {
                synchronized (tree) {
                    yield ((ScopeState) ((Obj) self).nat).cancelled;
                }
            }
            case "Semaphore.new" -> {
                var sem = new Obj((TypeInfo) fn.owner, List.of(), new Object[0]);
                sem.nat = new java.util.concurrent.Semaphore((int) Math.min(((Int) a[0]).v(), Integer.MAX_VALUE), true);
                yield sem;
            }
            case "Semaphore.acquire" -> acquire((java.util.concurrent.Semaphore) ((Obj) self).nat);
            case "Semaphore.release" -> {
                ((java.util.concurrent.Semaphore) ((Obj) self).nat).release();
                yield Unit.UNIT;
            }
            case "RawLock.new" -> {
                var lock = new Obj((TypeInfo) fn.owner, List.of(), new Object[0]);
                lock.nat = new ReentrantLock();
                yield lock;
            }
            case "RawLock.acquire" -> {
                acquire((ReentrantLock) ((Obj) self).nat);
                yield Unit.UNIT;
            }
            case "RawLock.release" -> {
                ((ReentrantLock) ((Obj) self).nat).unlock();
                yield Unit.UNIT;
            }
            case "sync.guard" -> {
                var guard = (Type.Nominal) TypeSystem.subst(fn.ret, typeEnv);
                yield new Obj(guard.decl(), guard.args(), new Object[] {a[0]});
            }
            case "Atomic.new" -> {
                var m = construct(fn, typeEnv);
                m.nat = new AtomicLong(raw(a[0]));
                yield m;
            }
            default -> atomic(name, (Obj) self, a);
        };
    }

    /** A new instance of {@code fn}'s owner class, its type argument taken from the call. */
    private static Obj construct(FnInfo fn, Map<TypeParamInfo, Type> typeEnv, Object... fields) {
        var owner = (TypeInfo) fn.owner;
        var arg = typeEnv.getOrDefault(owner.typeParams.getFirst(), Type.ERROR);
        return new Obj(owner, List.of(arg), fields);
    }

    // ---- Threads, scopes and cancellation ----

    /** Whether the current thread is cancelled (§13.4). */
    boolean currentCancelled() {
        return current.get().cancelled;
    }

    /** Cancels the current thread when run, from any thread, as `Thread::cancel` does (for tests). */
    Runnable cancellerOfCurrent() {
        var me = current.get();
        return () -> {
            synchronized (tree) {
                cancel(me);
            }
        };
    }

    /** {@link Callbacks#uninterruptibly}. */
    boolean uninterruptibly(Runnable op) {
        var me = current.get();
        me.shielded = true;
        try {
            if (me.cancelled) return false;
            op.run();
            return true;
        } finally {
            me.shielded = false;
        }
    }

    /** Takes a permit, waiting while none is free; false if the current thread is cancelled first or meanwhile. */
    private boolean acquire(java.util.concurrent.Semaphore sem) {
        var me = current.get();
        if (me.cancelled) return false;
        while (true) {
            try {
                sem.acquire();
                return true;
            } catch (InterruptedException e) {
                if (cancelledByInterrupt(me)) return false;
            }
        }
    }

    private static Strand strand(Object thread) {
        return (Strand) ((Obj) thread).nat;
    }

    /** Starts {@code strand} running {@code f}; returns {@code handle} (a `Thread<R>`). */
    private Obj start(Strand strand, FnVal f, Obj handle) {
        handle.nat = strand;
        rt.startThread(() -> {
            current.set(strand);
            strand.thread = Thread.currentThread();
            try {
                strand.result = rt.call(f);
            } finally {
                strand.done.countDown();
                if (strand.scope != null) {
                    synchronized (tree) {
                        strand.scope.children.remove(strand);
                        tree.notifyAll();
                    }
                }
            }
        });
        return handle;
    }

    /** Sets {@code s}'s flag and wakes it, then cancels the scopes it owns (§13.4); holds {@link #tree}. */
    private void cancel(Strand s) {
        if (s.cancelled) return;
        s.cancelled = true;
        // The flag is set before `shielded` is read, and the thread sets `shielded` before reading the
        // flag, so a thread entering a shielded region either sees the flag or is never interrupted.
        var t = s.thread;
        if (t != null && !s.shielded) t.interrupt();
        for (var scope : s.owned) cancel(scope);
    }

    /** Cancels {@code scope}'s threads, and those spawned into it later; holds {@link #tree}. */
    private void cancel(ScopeState scope) {
        if (scope.cancelled) return;
        scope.cancelled = true;
        for (var child : scope.children) cancel(child);
    }

    /** Waits until every thread in {@code scope} has finished, then ends it. Not a cancellation point. */
    private void close(ScopeState scope) {
        synchronized (tree) {
            while (!scope.children.isEmpty()) {
                try {
                    tree.wait();
                } catch (InterruptedException e) {
                    rt.checkAbort();
                }
            }
            scope.ended = true;
            scope.owner.owned.remove(scope);
        }
    }

    /**
     * After an interrupt in a cancellation point: unwinds if the process is ending, and otherwise
     * says whether the current thread is cancelled (if not, the caller waits again).
     */
    private boolean cancelledByInterrupt(Strand me) {
        rt.checkAbort();
        return me.cancelled;
    }

    /** Waits up to {@code nanos} ({@link Long#MAX_VALUE}: forever) for {@code target}; a {@code WAIT_*} code. */
    private int await(Strand target, long nanos) {
        var me = current.get();
        if (me.cancelled) return WAIT_CANCELLED;
        long deadline = System.nanoTime() + nanos;
        while (true) {
            try {
                if (nanos == Long.MAX_VALUE) {
                    target.done.await();
                    return WAIT_DONE;
                }
                return target.done.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) ? WAIT_DONE : WAIT_TIMED_OUT;
            } catch (InterruptedException e) {
                if (cancelledByInterrupt(me)) return WAIT_CANCELLED;
            }
        }
    }

    /** Sleeps for {@code nanos}; false if the current thread is cancelled first or meanwhile. */
    private boolean sleep(long nanos) {
        var me = current.get();
        if (me.cancelled) return false;
        long deadline = System.nanoTime() + nanos;
        while (true) {
            long left = nanos <= 0 ? 0 : deadline - System.nanoTime();
            if (left <= 0) return true;
            try {
                Thread.sleep(java.time.Duration.ofNanos(left));
            } catch (InterruptedException e) {
                if (cancelledByInterrupt(me)) return false;
            }
        }
    }

    // ---- RawLock (std::sync's Mutex is Threebolt over it) ----

    /**
     * Takes the lock, waiting while another thread holds it; taking it twice on one thread would deadlock,
     * so it panics. Not a cancellation point: a cancelled thread keeps waiting.
     */
    private void acquire(ReentrantLock lock) {
        if (lock.isHeldByCurrentThread()) throw new Panic("mutex is already locked by this thread");
        while (true) {
            try {
                lock.lockInterruptibly();
                return;
            } catch (InterruptedException e) {
                rt.checkAbort();
            }
        }
    }

    // ---- Atomic (integers and bool; sequentially consistent) ----

    private static long raw(Object v) {
        return v instanceof Boolean b ? (b ? 1 : 0) : ((Int) v).v();
    }

    private static Object value(Prim kind, long raw) {
        return kind == Prim.BOOL ? raw != 0 : Ops.wrap(kind, raw);
    }

    private Object atomic(String name, Obj atomic, Object[] a) {
        var cell = (AtomicLong) atomic.nat;
        var kind = TypeSystem.prim(atomic.args.getFirst());
        return switch (name) {
            case "load" -> value(kind, cell.get());
            case "store" -> {
                cell.set(raw(a[0]));
                yield Unit.UNIT;
            }
            case "swap" -> value(kind, cell.getAndSet(raw(a[0])));
            case "compare_exchange" -> value(kind, cell.compareAndExchange(raw(a[0]), raw(a[1])));
            case "fetch_add" -> fetch(kind, cell, raw(a[0]), Long::sum, true);
            case "fetch_sub" -> fetch(kind, cell, raw(a[0]), (x, y) -> x - y, true);
            case "fetch_and" -> fetch(kind, cell, raw(a[0]), (x, y) -> x & y, false);
            case "fetch_or" -> fetch(kind, cell, raw(a[0]), (x, y) -> x | y, false);
            case "fetch_xor" -> fetch(kind, cell, raw(a[0]), (x, y) -> x ^ y, false);
            default -> throw new IllegalStateException("unknown intrinsic Atomic." + name);
        };
    }

    /** Applies {@code op} atomically and returns the previous value; arithmetic wraps. */
    private static Object fetch(Prim kind, AtomicLong cell, long operand, LongBinaryOperator op, boolean arithmetic) {
        if (arithmetic && kind == Prim.BOOL) throw new Panic("arithmetic on an `Atomic<bool>`");
        long old = cell.getAndUpdate(x -> kind == Prim.BOOL ? op.applyAsLong(x, operand) : Ops.wrap(kind, op.applyAsLong(x, operand)).v());
        return value(kind, old);
    }
}
