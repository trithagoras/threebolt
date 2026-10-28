package dev.threebolt.lsp;

import dev.threebolt.diag.Diagnostic;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.resolve.Module;
import dev.threebolt.resolve.ModuleLoader;
import dev.threebolt.resolve.Program;
import dev.threebolt.types.Completion;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The open documents and the latest analysis of each program they belong to. A program is
 * checked from its entry file: the nearest {@code main.3b} at or above the edited file, or the
 * file itself when no such program includes it (§2.1: the source root is the entry's directory).
 *
 * <p>Analyses run one at a time on a thread with a large stack (the checker recurses), shortly
 * after the last edit. Queries read the newest analysis in which the file parsed, so hover and
 * navigation keep working while the file has syntax errors.
 */
final class Workspace {
    private static final long DEBOUNCE_MS = 200;
    private static final long STACK_SIZE = 512L << 20;

    private final Map<Path, String> open = new ConcurrentHashMap<>();
    /** The latest analysis that included each file. */
    private final Map<Path, Analysis> current = new ConcurrentHashMap<>();
    /** The latest analysis in which each file parsed. */
    private final Map<Path, Analysis> lastParsed = new ConcurrentHashMap<>();
    /** Per entry: the files diagnostics were last published for. */
    private final Map<Path, Set<Path>> published = new HashMap<>();
    private final Map<Path, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final List<Path> roots = new ArrayList<>();
    final StdFiles std = new StdFiles();

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(null, r, "threebolt-analysis", STACK_SIZE));
    /** Sends a file's diagnostics (from the given analysis) to the editor. */
    interface Publisher {
        void publish(Analysis a, Path file, List<Diagnostic> diagnostics);
    }

    private final Publisher publish;
    private final Consumer<String> log;

    Workspace(Publisher publish, Consumer<String> log) {
        this.publish = publish;
        this.log = log;
    }

    void addRoot(Path root) {
        roots.add(root);
    }

    void opened(Path file, String text) {
        open.put(file, text);
        schedule(file, 0);
    }

    void changed(Path file, String text) {
        open.put(file, text);
        schedule(file, DEBOUNCE_MS);
    }

    void saved(Path file) {
        schedule(file, 0);
    }

    void closed(Path file) {
        open.remove(file);
        schedule(file, 0);
    }

    /** The analysis to answer questions about {@code file} from, or null if none yet. */
    Analysis forQuery(Path file) {
        var a = lastParsed.get(file);
        return a != null ? a : current.get(file);
    }

    /** The latest analysis of each program, each once. */
    java.util.Collection<Analysis> analyses() {
        var out = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Analysis, Boolean>());
        out.addAll(current.values());
        return out;
    }

    /** The editor's text of {@code file}, else the file on disk; null if it cannot be read. */
    String text(Path file) {
        var text = open.get(file);
        if (text != null) return text;
        try {
            return Files.readString(file);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Whether {@code file} can be checked: a `.3b` file outside std. */
    boolean checkable(Path file) {
        return !std.contains(file) && file.toString().endsWith(".3b");
    }

    /**
     * Checks {@code file}'s program with {@code text} in place of the file, answering
     * {@code completion}. Runs on the analysis thread, ahead of debounced work that is not yet
     * due; the result is not recorded, so it neither publishes diagnostics nor answers queries.
     */
    Analysis check(Path file, String text, Completion completion) throws Exception {
        Function<Path, String> reader = p -> p.equals(file) ? text : read(p);
        return executor.submit(() -> {
            var entry = entryFor(file);
            var a = Analysis.run(entry, reader, std, completion);
            if (a.module(file) == null && !entry.equals(file)) a = Analysis.run(file, reader, std, completion);
            return a;
        }).get();
    }

    /** Loads (does not check) {@code file}'s program with {@code text} in place of the file. */
    Program load(Path file, String text) {
        Function<Path, String> reader = p -> p.equals(file) ? text : read(p);
        var entry = entryFor(file);
        var program = new ModuleLoader(new Diagnostics(), reader).load(entry);
        if (!entry.equals(file) && program.all().stream().noneMatch(m -> file.equals(m.file()))) {
            program = new ModuleLoader(new Diagnostics(), reader).load(file);
        }
        return program;
    }

    /** The source root of {@code file}'s program: its entry file's directory (§2.1). */
    Path sourceRoot(Path file) {
        return entryFor(file).getParent();
    }

    /** Waits for every scheduled analysis to finish (tests). */
    void awaitIdle() throws Exception {
        for (var f : List.copyOf(pending.values())) {
            try {
                f.get();
            } catch (java.util.concurrent.CancellationException e) {
                // Superseded by a later edit, which is also pending.
            }
        }
        executor.submit(() -> {}).get();
    }

    void shutdown() {
        executor.shutdownNow();
    }

    private void schedule(Path file, long delayMs) {
        if (!checkable(file)) return;
        var entry = entryFor(file);
        var old = pending.put(entry, executor.schedule(() -> analyze(entry, file), delayMs, TimeUnit.MILLISECONDS));
        if (old != null) old.cancel(false);
    }

    /**
     * The nearest `main.3b` at or above {@code file}, within a workspace root; else the file.
     * (Checking may still fall back to the file alone when that program does not include it.)
     */
    private Path entryFor(Path file) {
        var stop = roots.stream().filter(file::startsWith).findFirst().orElse(file.getParent());
        for (Path dir = file.getParent(); dir != null && dir.startsWith(stop); dir = dir.getParent()) {
            var main = dir.resolve("main.3b");
            if (main.equals(file) || open.containsKey(main) || Files.isRegularFile(main)) return main;
        }
        return file;
    }

    private void analyze(Path entry, Path file) {
        try {
            var a = Analysis.run(entry, this::read, std);
            if (a.module(file) == null && !entry.equals(file)) {
                // The nearest main.3b does not use this file: check it on its own.
                entry = file;
                a = Analysis.run(file, this::read, std);
            }
            record(a);
        } catch (Throwable t) {
            var trace = new StringWriter();
            t.printStackTrace(new PrintWriter(trace));
            log.accept("threebolt: checking " + entry + " failed:\n" + trace);
        }
    }

    private String read(Path file) {
        var text = open.get(file);
        return text != null ? text : ModuleLoader.read(file);
    }

    private void record(Analysis a) {
        var byFile = new HashMap<Path, List<Diagnostic>>();
        for (Module m : a.modules()) {
            var file = a.file(m.source());
            current.put(file, a);
            if (m.ast() != null) lastParsed.put(file, a);
            if (!m.isStd()) byFile.put(file, new ArrayList<>());
        }
        for (var d : a.diagnostics) {
            var list = byFile.get(a.file(d.span().source()));
            if (list != null) list.add(d);
        }
        var before = published.getOrDefault(a.entry, Set.of());
        for (var file : before) {
            if (!byFile.containsKey(file)) publish.publish(a, file, List.of());
        }
        byFile.forEach((file, ds) -> publish.publish(a, file, ds));
        published.put(a.entry, new HashSet<>(byFile.keySet()));
    }
}
