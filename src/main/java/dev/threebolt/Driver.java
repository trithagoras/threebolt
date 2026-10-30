package dev.threebolt;

import dev.threebolt.diag.Diagnostics;
import dev.threebolt.interp.Interpreter;
import dev.threebolt.resolve.ModuleLoader;
import dev.threebolt.types.CheckedProgram;
import dev.threebolt.types.Checker;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/** Loads, checks and runs a program; shared by the CLI and the conformance tests. */
public final class Driver {
    private Driver() {}

    /** The checked program, or null if {@code diagnostics} has errors. */
    public static CheckedProgram check(Path entry, Diagnostics diagnostics) {
        return check(entry, null, diagnostics);
    }

    /**
     * {@link #check(Path, Diagnostics)} in the package rooted at {@code packageRoot} (§2.5); null
     * for a program without a manifest.
     */
    public static CheckedProgram check(Path entry, Path packageRoot, Diagnostics diagnostics) {
        var loader = new ModuleLoader(diagnostics);
        var program = packageRoot == null ? loader.load(entry) : loader.load(entry, packageRoot);
        var checked = new Checker(program, diagnostics).check();
        return diagnostics.hasErrors() ? null : checked;
    }

    /**
     * Runs {@code main} on a Threebolt thread ({@link Interpreter#threads}), and returns the exit code
     * once the process ends: when `main` returns, or on a panic or `exit` on any thread (§2.4,
     * §13.1). Threebolt threads still running are daemons and are told to stop.
     */
    public static int run(CheckedProgram program, List<String> args, InputStream in, PrintStream out, PrintStream err) {
        var interpreter = new Interpreter(program, in, out, err);
        Interpreter.threads("main").start(() -> interpreter.run(args));
        return interpreter.awaitExit();
    }
}
