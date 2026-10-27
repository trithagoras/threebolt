package dev.threebolt;

import dev.threebolt.diag.Diagnostics;
import dev.threebolt.interp.Interpreter;
import dev.threebolt.resolve.ModuleLoader;
import dev.threebolt.types.CheckedProgram;
import dev.threebolt.types.Checker;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Loads, checks and runs a program; shared by the CLI and the conformance tests. */
public final class Driver {
    private Driver() {}

    /** The checked program, or null if {@code diagnostics} has errors. */
    public static CheckedProgram check(Path entry, Diagnostics diagnostics) {
        var program = new ModuleLoader(diagnostics).load(entry);
        var checked = new Checker(program, diagnostics).check();
        return diagnostics.hasErrors() ? null : checked;
    }

    /**
     * Runs {@code main} on a thread with a large stack (the evaluator recurses); returns the exit
     * code (§2.4, §13.1).
     */
    public static int run(CheckedProgram program, List<String> args, PrintStream out, PrintStream err) {
        var code = new AtomicInteger();
        var thread = new Thread(null, () -> code.set(new Interpreter(program, out, err).run(args)), "main", 1L << 30);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
        return code.get();
    }
}
