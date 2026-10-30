package dev.threebolt.interp;

import dev.threebolt.interp.Values.FnVal;
import dev.threebolt.interp.Values.Obj;
import dev.threebolt.types.Type;
import dev.threebolt.types.TypeInfo;
import dev.threebolt.types.TypeSystem;
import java.io.InputStream;
import java.io.PrintStream;

/**
 * Everything an intrinsic ({@link Natives}, {@link Threads}, {@link Ffi}, {@link Io}) may ask of the
 * evaluator. Intrinsics are value-in/value-out: they never see the IR, frames or environments, and
 * reach Threebolt code only through {@link #call} and {@link #callTrait}. A new backend provides this
 * interface and the intrinsics carry over.
 */
interface Callbacks {
    TypeSystem ts();

    // ---- Calling Threebolt code ----

    /** Calls a function value: a closure, a named function, or a C function pointer. */
    Object call(FnVal f, Object... args);

    /** Calls the member {@code name} of {@code trait} on {@code recv}, dispatched on its runtime type (§8.3). */
    Object callTrait(Object recv, TypeInfo trait, String name, Object... args);

    // ---- Value semantics that may run user code (`Eq`, `Hash`, `Display`, `Iterator`) ----

    /** {@code a == b} (§7.3). */
    boolean equal(Object a, Object b);

    int hash(Object v);

    /** {@code Display} (§16), as f-strings use it. */
    String display(Object v);

    /** {@link #display}, falling back to the type's name; for panic messages. */
    String debug(Object v);

    /** One step of an iterator; {@link #done()} at the end. */
    Object next(Object iterator);

    /** The `Done` sentinel (§16). */
    Obj done();

    /** The runtime type of a (unboxed) value (§15). */
    Type typeOf(Object v);

    // ---- The process (§13) ----

    PrintStream out();

    PrintStream err();

    /** UTF-8; read under its own lock. */
    InputStream stdin();

    /** Stops the calling thread if the process is ending. */
    void checkAbort();

    boolean isTerminating();

    /** Starts a Threebolt thread running {@code body}; a panic in it ends the process. */
    Thread startThread(Runnable body);

    /** Whether the current Threebolt thread is cancelled (§13.4). */
    boolean cancelled();

    /**
     * Runs {@code op} so that cancelling the current thread meanwhile does not interrupt it (a file's
     * system call in progress finishes, §13.4); false, without running it, if the thread is cancelled.
     */
    boolean uninterruptibly(Runnable op);

    /** Reports a panic that cannot unwind (a C upcall) and ends the process. */
    void panicked(Panic p);

    /** Ends the process with {@code code}. */
    void terminate(int code);

    /**
     * Unwinds a thread once the process is ending (a panic or `exit` on another thread, or
     * `main` returned, §13.1); no defers run.
     */
    final class Abort extends RuntimeException {
        Abort() {
            super(null, null, false, false);
        }
    }

    /** {@code std::process::exit}: leaves immediately, no defers (§2.4). */
    final class ExitSignal extends RuntimeException {
        final int code;

        ExitSignal(int code) {
            super(null, null, false, false);
            this.code = code;
        }
    }
}
