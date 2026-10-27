package dev.threebolt;

import dev.threebolt.diag.Diagnostics;
import dev.threebolt.interp.Interpreter;
import dev.threebolt.resolve.ModuleLoader;
import dev.threebolt.types.CheckedProgram;
import dev.threebolt.types.Checker;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

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
     * Runs {@code main} on a thread with a large stack (the evaluator recurses) and returns the
     * exit code once the process ends: when `main` returns, or on a panic or `exit` on any thread
     * (§2.4, §13.1). Threebolt threads still running are daemons and are told to stop.
     */
    public static int run(CheckedProgram program, List<String> args, PrintStream out, PrintStream err) {
        var interpreter = new Interpreter(program, out, err);
        var thread = new Thread(null, () -> interpreter.run(args), "main", Interpreter.STACK_SIZE);
        thread.setDaemon(true);
        thread.start();
        return interpreter.awaitExit();
    }
}
