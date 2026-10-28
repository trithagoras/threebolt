package dev.threebolt.lsp;

import dev.threebolt.diag.Diagnostic;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Source;
import dev.threebolt.project.Manifest;
import dev.threebolt.project.Project;
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
 * The open documents and the latest analysis of each program they belong to. A file in a package
 * (§2.5: the nearest `package.toml` at or above it within the editor's folder, or a member of a
 * folder's workspace) is checked with the first of the package's bins whose program includes it,
 * else with the whole package as a library. Other files keep the manifest-less rule: the nearest
 * {@code main.3b} at or above the file, or the file itself when that program does not include it.
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
            Analysis a = null;
            for (var t : targetsFor(file)) {
                a = Analysis.run(t, reader, std, completion);
                if (a.module(file) != null) break;
            }
            return a;
        }).get();
    }

    /** Loads (does not check) {@code file}'s program with {@code text} in place of the file. */
    Program load(Path file, String text) {
        Function<Path, String> reader = p -> p.equals(file) ? text : read(p);
        Program program = null;
        for (var t : targetsFor(file)) {
            program = t.load(new ModuleLoader(new Diagnostics(), reader));
            if (program.all().stream().anyMatch(m -> file.equals(m.file()))) break;
        }
        return program;
    }

    /** The source root of {@code file}'s program: its package's directory, else its entry file's (§2.1, §2.5). */
    Path sourceRoot(Path file) {
        var t = targetsFor(file).getFirst();
        return t.root() != null ? t.root() : t.entry().getParent();
    }

    /** The dependencies of {@code file}'s package (§2.5), by key, with their directories; empty without one. */
    Map<String, Path> dependencies(Path file) {
        var pkg = packageOf(file);
        var out = new java.util.LinkedHashMap<String, Path>();
        if (pkg != null) {
            for (var d : pkg.manifest().dependencies()) out.put(d.key(), pkg.root().resolve(d.path()).normalize());
        }
        return out;
    }

    /** A `package.toml` or `workspace.toml` changed: packages may have, so every open file is checked again. */
    void manifestsChanged() {
        for (var file : open.keySet()) schedule(file, 0);
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
        var targets = targetsFor(file);
        var old = pending.put(targets.getFirst().key(), executor.schedule(() -> analyze(targets, file), delayMs, TimeUnit.MILLISECONDS));
        if (old != null) old.cancel(false);
    }

    /** What to check {@code file} with, in order: the first whose program includes it wins (the last always does). */
    private List<Analysis.Target> targetsFor(Path file) {
        var out = new ArrayList<Analysis.Target>();
        var pkg = packageOf(file);
        if (pkg != null) {
            var entries = pkg.manifest().bins().stream().map(b -> pkg.root().resolve(b.entry()).normalize())
                    .filter(e -> e.equals(file) || open.containsKey(e) || Files.isRegularFile(e)).toList();
            if (entries.contains(file)) out.add(new Analysis.Target(file, pkg.root(), true));
            for (var e : entries) if (!e.equals(file)) out.add(new Analysis.Target(e, pkg.root(), true));
            out.add(new Analysis.Target(null, pkg.root(), false));
            return out;
        }
        var main = nearestMain(file);
        out.add(Analysis.Target.script(main));
        if (!main.equals(file)) out.add(Analysis.Target.script(file));
        return out;
    }

    /**
     * The package {@code file} is in: the nearest directory at or above it, within the editor's
     * folder, holding a `package.toml`, or a member of a folder's workspace; null if none.
     */
    private Project.Pkg packageOf(Path file) {
        var stop = roots.stream().filter(file::startsWith).findFirst().orElse(file.getParent());
        for (Path dir = file.getParent(); dir != null && dir.startsWith(stop); dir = dir.getParent()) {
            var manifest = dir.resolve(Manifest.FILE);
            if (Files.isRegularFile(manifest)) {
                var text = read(manifest);
                return new Project.Pkg(dir, Manifest.parse(new Source(Manifest.FILE, text), new Diagnostics()));
            }
        }
        for (var root : roots) {
            var project = Project.open(root, root, new Diagnostics());
            var pkg = project == null ? null : project.packageOf(file);
            if (pkg != null) return pkg;
        }
        return null;
    }

    /** The nearest `main.3b` at or above {@code file}, within a workspace root; else the file. */
    private Path nearestMain(Path file) {
        var stop = roots.stream().filter(file::startsWith).findFirst().orElse(file.getParent());
        for (Path dir = file.getParent(); dir != null && dir.startsWith(stop); dir = dir.getParent()) {
            var main = dir.resolve("main.3b");
            if (main.equals(file) || open.containsKey(main) || Files.isRegularFile(main)) return main;
        }
        return file;
    }

    private void analyze(List<Analysis.Target> targets, Path file) {
        var target = targets.getFirst();
        try {
            Analysis a = null;
            for (var t : targets) {
                target = t;
                a = Analysis.run(t, this::read, std);
                // A target that does not include the file: try the next.
                if (a.module(file) != null) break;
            }
            record(a);
        } catch (Throwable t) {
            var trace = new StringWriter();
            t.printStackTrace(new PrintWriter(trace));
            log.accept("threebolt: checking " + target.key() + " failed:\n" + trace);
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
        for (var manifest : a.manifests()) byFile.put(manifest, new ArrayList<>());
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
