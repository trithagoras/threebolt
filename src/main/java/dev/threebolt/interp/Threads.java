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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongBinaryOperator;

/**
 * `std::thread` and `std::sync` (§13.4) on Java platform threads. `Thread`, `Mutex` and `Atomic`
 * are std classes whose Java state is kept in {@link Obj#nat}.
 */
final class Threads {
    private final Interpreter in;

    Threads(Interpreter in) {
        this.in = in;
    }

    /** A spawned thread and, once it has finished, its result. */
    private static final class Running {
        Thread thread;
        volatile Object result;
    }

    Object call(String owner, String name, Object self, Object[] a, Map<TypeParamInfo, Type> typeEnv, FnInfo fn) {
        return switch (owner + "." + name) {
            case "thread.sleep" -> {
                try {
                    Thread.sleep(((Int) a[0]).v());
                } catch (InterruptedException e) {
                    throw new Interpreter.Abort();
                }
                yield Unit.UNIT;
            }
            case "Thread.spawn" -> spawn((FnVal) a[0], fn, typeEnv);
            case "Thread.join" -> join((Obj) self);
            case "Mutex.new" -> {
                var m = construct(fn, typeEnv, a[0]);
                m.nat = new ReentrantLock();
                yield m;
            }
            case "Mutex.lock" -> lock((Obj) self, (FnVal) a[0], fn);
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

    // ---- Thread ----

    private Obj spawn(FnVal f, FnInfo fn, Map<TypeParamInfo, Type> typeEnv) {
        var thread = construct(fn, typeEnv);
        var running = new Running();
        thread.nat = running;
        running.thread = in.startThread(() -> running.result = in.callFn(f, new Object[0]));
        return thread;
    }

    private Object join(Obj thread) {
        var running = (Running) thread.nat;
        try {
            running.thread.join();
        } catch (InterruptedException e) {
            throw new Interpreter.Abort();
        }
        // The thread may have ended the process (a panic) rather than returned.
        in.checkAbort();
        return Values.copy(running.result);
    }

    // ---- Mutex ----

    /**
     * Runs {@code f} with a `Guard` over the mutex's value while holding its lock, then stores
     * the guard's value back (§13.4). Locking a mutex twice on one thread would deadlock; it panics.
     */
    private Object lock(Obj mutex, FnVal f, FnInfo fn) {
        var lock = (ReentrantLock) mutex.nat;
        if (lock.isHeldByCurrentThread()) throw new Panic("mutex is already locked by this thread");
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            throw new Interpreter.Abort();
        }
        try {
            var guardType = (Type.Nominal) ((Type.Fn) fn.params.getFirst().type()).params().getFirst();
            var guard = new Obj(guardType.decl(), mutex.args, new Object[] {mutex.fields[0]});
            var result = in.callFn(f, new Object[] {guard});
            mutex.fields[0] = guard.fields[0];
            return result;
        } finally {
            lock.unlock();
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
