package dev.threebolt.lsp;

import dev.threebolt.diag.Code;
import dev.threebolt.diag.Diagnostic;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Source;
import dev.threebolt.resolve.Module;
import dev.threebolt.resolve.ModuleLoader;
import dev.threebolt.resolve.Program;
import dev.threebolt.types.Checker;
import dev.threebolt.types.Completion;
import dev.threebolt.types.SymbolIndex;
import dev.threebolt.types.TypeSystem;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One check of a program, as the editor sees it: its modules, diagnostics and symbol index.
 * Immutable once built. Std modules have no file of their own, so they are given one under
 * {@code stdDir} (see {@link StdFiles}).
 */
final class Analysis {
    final Path entry;
    final Program program;
    final SymbolIndex index;
    final TypeSystem ts;
    final List<Diagnostic> diagnostics;
    private final Map<Source, Path> files = new IdentityHashMap<>();
    private final Map<Path, Module> modules = new HashMap<>();

    private Analysis(Path entry, Program program, SymbolIndex index, TypeSystem ts, List<Diagnostic> diagnostics,
                     StdFiles std) {
        this.entry = entry;
        this.program = program;
        this.index = index;
        this.ts = ts;
        this.diagnostics = diagnostics;
        for (Module m : program.all()) {
            Path file = m.file() != null ? m.file() : std.file(m);
            files.put(m.source(), file);
            modules.put(file, m);
        }
    }

    static Analysis run(Path entry, Function<Path, String> reader, StdFiles std) {
        return run(entry, reader, std, null);
    }

    /** Also answers {@code completion} (if not null) while checking. */
    static Analysis run(Path entry, Function<Path, String> reader, StdFiles std, Completion completion) {
        var diagnostics = new Diagnostics();
        var program = new ModuleLoader(diagnostics, reader).load(entry);
        var index = completion == null ? new SymbolIndex() : new SymbolIndex(completion);
        var checked = new Checker(program, diagnostics, index).check();
        var ds = diagnostics.sorted();
        if (!entry.getFileName().toString().equals("main.3b") && program.entry().scope().items("main").isEmpty()) {
            // A module checked on its own because no program uses it: it need not have a `main`.
            ds = ds.stream().filter(d -> d.code() != Code.INVALID_MAIN).toList();
        }
        return new Analysis(entry, program, index, checked.ts(), ds, std);
    }

    /** The module read from {@code file}, or null if the program does not include it. */
    Module module(Path file) {
        return modules.get(file);
    }

    Iterable<Module> modules() {
        return program.all();
    }

    /** The file {@code source} was read from (or its std copy). */
    Path file(Source source) {
        return files.get(source);
    }
}
