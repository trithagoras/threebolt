package dev.threebolt.project;

import dev.threebolt.diag.Code;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Source;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The package or workspace in a directory, as `bolt` finds it: `package.toml` or `workspace.toml`
 * in that directory only, never above it. A workspace's members are packages.
 */
public final class Project {
    /** A package of the project: its root directory and manifest. */
    public record Pkg(Path root, Manifest manifest) {}

    /** A `[[bin]]` of one of the packages, with its entry file resolved. */
    public record Target(Pkg pkg, Manifest.Bin bin, Path entry) {}

    private final Path dir;
    private final boolean workspace;
    private final List<Pkg> packages;

    private Project(Path dir, boolean workspace, List<Pkg> packages) {
        this.dir = dir;
        this.workspace = workspace;
        this.packages = List.copyOf(packages);
    }

    /**
     * The project in {@code dir}, or null if it holds neither manifest. Manifests are named
     * relative to {@code base} in diagnostics.
     */
    public static Project open(Path dir, Path base, Diagnostics diagnostics) {
        dir = dir.toAbsolutePath().normalize();
        var pkgFile = dir.resolve(Manifest.FILE);
        var wsFile = dir.resolve(Manifest.WORKSPACE_FILE);
        boolean isPackage = Files.isRegularFile(pkgFile);
        boolean isWorkspace = Files.isRegularFile(wsFile);
        if (!isPackage && !isWorkspace) return null;
        if (isPackage && isWorkspace) {
            var source = source(wsFile, base);
            diagnostics.error(Code.MANIFEST, new dev.threebolt.diag.Span(source, 0, Math.min(1, source.length())),
                    "a directory cannot hold both " + Manifest.FILE + " and " + Manifest.WORKSPACE_FILE);
            return new Project(dir, false, List.of());
        }
        var packages = new ArrayList<Pkg>();
        if (isPackage) {
            packages.add(new Pkg(dir, Manifest.parse(source(pkgFile, base), diagnostics)));
        } else {
            var ws = Manifest.Workspace.parse(source(wsFile, base), diagnostics);
            for (var member : ws.members()) {
                var root = dir.resolve(member).normalize();
                var file = root.resolve(Manifest.FILE);
                if (!Files.isRegularFile(file)) {
                    diagnostics.error(Code.MANIFEST, ws.span(), "workspace member '" + member + "' has no " + Manifest.FILE);
                    continue;
                }
                packages.add(new Pkg(root, Manifest.parse(source(file, base), diagnostics)));
            }
        }
        var project = new Project(dir, isWorkspace, packages);
        var seen = new HashMap<String, Target>();
        for (var t : project.targets()) {
            var previous = seen.putIfAbsent(t.bin().name(), t);
            if (previous != null) {
                diagnostics.error(Code.MANIFEST, t.bin().span(), "bin '" + t.bin().name() + "' is also defined by package '"
                        + previous.pkg().manifest().name() + "'; bin names are unique in a workspace");
            }
        }
        return project;
    }

    private static Source source(Path file, Path base) {
        try {
            return new Source(base.relativize(file).toString().replace('\\', '/'), Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    public Path dir() {
        return dir;
    }

    public boolean isWorkspace() {
        return workspace;
    }

    public List<Pkg> packages() {
        return packages;
    }

    /** Every bin of every package. */
    public List<Target> targets() {
        var out = new ArrayList<Target>();
        for (var p : packages) {
            for (var b : p.manifest().bins()) out.add(new Target(p, b, p.root().resolve(b.entry()).normalize()));
        }
        return out;
    }

    /** The package whose root holds {@code file} (the innermost, for nested ones); null if none. */
    public Pkg packageOf(Path file) {
        var f = file.toAbsolutePath().normalize();
        Pkg best = null;
        for (var p : packages) {
            if (f.startsWith(p.root()) && (best == null || p.root().startsWith(best.root()))) best = p;
        }
        return best;
    }

    /**
     * The nearest directory strictly above {@code dir} holding a manifest, for the hint after a
     * failed import (it never changes behaviour); null if none.
     */
    public static Path above(Path dir) {
        for (var d = dir.toAbsolutePath().normalize().getParent(); d != null; d = d.getParent()) {
            if (Files.isRegularFile(d.resolve(Manifest.FILE)) || Files.isRegularFile(d.resolve(Manifest.WORKSPACE_FILE))) return d;
        }
        return null;
    }
}
