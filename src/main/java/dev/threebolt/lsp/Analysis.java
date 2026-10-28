package dev.threebolt.lsp;

import dev.threebolt.diag.Code;
import dev.threebolt.diag.Diagnostic;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Source;
import dev.threebolt.project.Manifest;
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
    /**
     * What to check: the program of {@code entry}, or with a null entry every module of the
     * package at {@code root} (a library); {@code root} is null for a program without a manifest.
     * Without {@code requiresMain}, an entry need not declare `main`.
     */
    record Target(Path entry, Path root, boolean requiresMain) {
        /** Identifies the target's analyses: the entry, else the package root. */
        Path key() {
            return entry != null ? entry : root;
        }

        /** Without a manifest, as before packages: only a `main.3b` must have `main`. */
        static Target script(Path entry) {
            return new Target(entry, null, entry.getFileName().toString().equals("main.3b"));
        }

        Program load(ModuleLoader loader) {
            if (entry == null) return loader.loadLibrary(root);
            return root == null ? loader.load(entry) : loader.load(entry, root);
        }
    }

    final Target target;
    /** {@link Target#key()}. */
    final Path entry;
    final Program program;
    final SymbolIndex index;
    final TypeSystem ts;
    final List<Diagnostic> diagnostics;
    private final Map<Source, Path> files = new IdentityHashMap<>();
    private final Map<Path, Module> modules = new HashMap<>();

    private Analysis(Target target, Program program, SymbolIndex index, TypeSystem ts, List<Diagnostic> diagnostics,
                     StdFiles std) {
        this.target = target;
        this.entry = target.key();
        this.program = program;
        this.index = index;
        this.ts = ts;
        this.diagnostics = diagnostics;
        for (Module m : program.all()) {
            Path file = m.file() != null ? m.file() : std.file(m);
            files.put(m.source(), file);
            modules.put(file, m);
        }
        for (var pkg : program.packages()) {
            if (pkg.manifest() != null) files.put(pkg.manifest().source(), pkg.root().resolve(Manifest.FILE));
        }
    }

    static Analysis run(Target target, Function<Path, String> reader, StdFiles std) {
        return run(target, reader, std, null);
    }

    /** Also answers {@code completion} (if not null) while checking. */
    static Analysis run(Target target, Function<Path, String> reader, StdFiles std, Completion completion) {
        var diagnostics = new Diagnostics();
        var program = target.load(new ModuleLoader(diagnostics, reader));
        var index = completion == null ? new SymbolIndex() : new SymbolIndex(completion);
        var checked = new Checker(program, diagnostics, index).check();
        var ds = diagnostics.sorted();
        if (!target.requiresMain() && program.entry() != null && program.entry().scope().items("main").isEmpty()) {
            // A module checked on its own because no program uses it: it need not have a `main`.
            ds = ds.stream().filter(d -> d.code() != Code.INVALID_MAIN).toList();
        }
        return new Analysis(target, program, index, checked.ts(), ds, std);
    }

    /** The module read from {@code file}, or null if the program does not include it. */
    Module module(Path file) {
        return modules.get(file);
    }

    /** The `package.toml` files of the program's packages. */
    List<Path> manifests() {
        return program.packages().stream().filter(p -> p.manifest() != null).map(p -> p.root().resolve(Manifest.FILE)).toList();
    }

    Iterable<Module> modules() {
        return program.all();
    }

    /** The file {@code source} was read from (or its std copy). */
    Path file(Source source) {
        return files.get(source);
    }
}
