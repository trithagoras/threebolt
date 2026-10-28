package dev.threebolt.resolve;

import dev.threebolt.project.Manifest;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A package (§2.5): a source root and the dependencies its modules can name, each under its key.
 * The standard library is the package {@link #STD}, which every package sees under `std`.
 */
public final class Package {
    /** The standard library: its modules are resources, not files. */
    public static final Package STD = new Package("std", null, null, false);

    private final String name;
    private final Path root;
    private final Manifest manifest;
    private final boolean application;
    private final Map<String, Package> dependencies = new LinkedHashMap<>();

    Package(String name, Path root, Manifest manifest, boolean application) {
        this.name = name;
        this.root = root;
        this.manifest = manifest;
        this.application = application;
    }

    /** The manifest's name; for a program without a manifest, its entry directory's name. */
    public String name() {
        return name;
    }

    /** The source root, a real path; null for std. */
    public Path root() {
        return root;
    }

    /** The `package.toml` this package was read from; null without one (and for std). */
    public Manifest manifest() {
        return manifest;
    }

    /** Whether this is the package of the entry module (§2.5), which the orphan rule exempts (§4.10). */
    public boolean isApplication() {
        return application;
    }

    public boolean isStd() {
        return this == STD;
    }

    /** Its dependencies by key (not including std). */
    public Map<String, Package> dependencies() {
        return Collections.unmodifiableMap(dependencies);
    }

    void addDependency(String key, Package p) {
        dependencies.put(key, p);
    }

    /** A module path as written in this package: the package it names and the path within it. */
    public record Located(Package pkg, ModulePath local) {}

    /**
     * Where {@code written} leads from this package (§2.5): a first segment `std` or a dependency
     * key names that package's root, anything else this package's own modules. Null for a bare
     * `std` or key, which name no module.
     */
    public Located locate(ModulePath written) {
        var segments = written.segments();
        var first = segments.getFirst();
        Package target = first.equals("std") ? STD : dependencies.get(first);
        if (target == null) return new Located(this, written);
        return segments.size() == 1 ? null : new Located(target, new ModulePath(segments.subList(1, segments.size())));
    }

    /** How a module at {@code local} in this package is displayed: prefixed with the name, but for the application. */
    ModulePath qualified(ModulePath local) {
        if (application) return local;
        var s = new java.util.ArrayList<String>(List.of(name));
        s.addAll(local.segments());
        return new ModulePath(s);
    }

    @Override
    public String toString() {
        return name;
    }
}
