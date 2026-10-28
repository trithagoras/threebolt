package dev.threebolt.resolve;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The entry module, every module reachable from it through `use`, and the std modules (§2.1),
 * across the packages of the program. {@code entry} is null when a library package is checked as
 * a whole (every module of the application package, and no `main`).
 */
public final class Program {
    private final Module entry;
    private final Package application;
    private final List<Package> packages;
    private final List<Module> modules;
    private final Map<Package, Map<ModulePath, Module>> byPackage;

    Program(Module entry, Package application, List<Package> packages, List<Module> modules,
            Map<Package, Map<ModulePath, Module>> byPackage) {
        this.entry = entry;
        this.application = application;
        this.packages = List.copyOf(packages);
        this.modules = List.copyOf(modules);
        this.byPackage = byPackage;
    }

    public Module entry() {
        return entry;
    }

    public Package application() {
        return application;
    }

    /** The application package and its dependencies, transitively (not std). */
    public List<Package> packages() {
        return packages;
    }

    public Collection<Module> all() {
        return modules;
    }

    /** The module {@code path} names in the application package's code, e.g. `std::io` or `game::entity`. */
    public Module module(ModulePath path) {
        var loc = application.locate(path);
        return loc == null ? null : module(loc.pkg(), loc.local());
    }

    /** The module {@code path}, as written in module {@code from}, names (§2.5); null if none. */
    public Module module(Module from, ModulePath path) {
        var loc = from.pkg().locate(path);
        return loc == null ? null : module(loc.pkg(), loc.local());
    }

    public Module module(Package pkg, ModulePath local) {
        var mods = byPackage.get(pkg);
        return mods == null ? null : mods.get(local);
    }

    /** The submodule {@code m::name}. */
    public Module child(Module m, String name) {
        return module(m.pkg(), m.local().child(name));
    }

    /** The loaded modules directly under {@code m}. */
    public List<Module> children(Module m) {
        return under(m.pkg(), m.local());
    }

    /**
     * The loaded modules directly under the prefix {@code path} written in {@code from}, which
     * need not be a module itself: `std`, a dependency key, or a directory.
     */
    public List<Module> children(Module from, ModulePath path) {
        var segments = path.segments();
        if (segments.size() == 1) {
            var first = segments.getFirst();
            if (first.equals("std")) return under(Package.STD, null);
            var dep = from.pkg().dependencies().get(first);
            if (dep != null) return under(dep, null);
        }
        var loc = from.pkg().locate(path);
        return loc == null ? List.of() : under(loc.pkg(), loc.local());
    }

    private List<Module> under(Package pkg, ModulePath parent) {
        var out = new ArrayList<Module>();
        var mods = byPackage.get(pkg);
        if (mods == null) return out;
        for (var e : mods.entrySet()) {
            if (java.util.Objects.equals(parent, e.getKey().parent())) out.add(e.getValue());
        }
        return out;
    }
}
