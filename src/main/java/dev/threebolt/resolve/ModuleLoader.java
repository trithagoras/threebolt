package dev.threebolt.resolve;

import dev.threebolt.ast.AstWalk;
import dev.threebolt.ast.Attribute;
import dev.threebolt.ast.Expr;
import dev.threebolt.ast.TypeExpr;
import dev.threebolt.ast.TypeParam;
import dev.threebolt.ast.Item;
import dev.threebolt.ast.Member;
import dev.threebolt.ast.Name;
import dev.threebolt.ast.Visibility;
import dev.threebolt.diag.Code;
import dev.threebolt.diag.Diagnostic;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Source;
import dev.threebolt.diag.Span;
import dev.threebolt.parse.AstParser;
import dev.threebolt.project.Manifest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Linker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Loads a program (§2.1): the entry file, every module reachable from it through `use`, and the
 * std modules behind the prelude. Without a manifest the source root is the entry file's
 * directory; with one, the packages (§2.5) are read first: the application package's manifest,
 * then its dependencies'. `std::` modules come from the interpreter's resources. Then builds each
 * module's scope: its items, its resolved imports (§2.2) and the prelude (§16).
 */
public final class ModuleLoader {
    /** The std modules that exist; `std::a::b` is the resource `std/a/b.3b`. */
    public static final Set<String> STD_MODULES = Set.of(
            "prelude", "collections", "c", "process", "thread", "sync", "math", "random", "io", "time");

    private static final ModulePath PRELUDE = ModulePath.of("prelude");
    private static final ModulePath COLLECTIONS = ModulePath.of("collections");
    private static final ModulePath IO = ModulePath.of("io");
    /** The std::io functions in the prelude. */
    private static final List<String> IO_PRELUDE = List.of("print", "println", "eprint", "eprintln", "readln");
    private static final ModulePath C = ModulePath.of("c");

    private static final Set<String> PRIMITIVES = Set.of(
            "i8", "i16", "i32", "i64", "isize", "u8", "u16", "u32", "u64", "usize", "f32", "f64", "bool", "char", "str");

    private static final Set<String> ATTRIBUTES = Set.of("c", "flags", "link");

    private final Diagnostics diagnostics;
    /** Reads a source file; an editor substitutes its unsaved buffers. */
    private final Function<Path, String> reader;
    private Package app;
    /** The packages read so far, by real path (§2.5: one package per directory). */
    private final Map<Path, Package> packages = new LinkedHashMap<>();
    /** Every module looked up so far, by package and path within it; empty when there is no such module. */
    private final Map<Package, Map<ModulePath, Optional<Module>>> found = new HashMap<>();
    private final List<Module> loaded = new ArrayList<>();
    private final ArrayDeque<Module> pending = new ArrayDeque<>();

    public ModuleLoader(Diagnostics diagnostics) {
        this(diagnostics, ModuleLoader::read);
    }

    public ModuleLoader(Diagnostics diagnostics, Function<Path, String> reader) {
        this.diagnostics = diagnostics;
        this.reader = reader;
    }

    /** Loads the program of {@code entryFile} run without a manifest: one package rooted at its directory. */
    public Program load(Path entryFile) {
        var file = entryFile.toAbsolutePath().normalize();
        app = new Package(String.valueOf(file.getParent().getFileName()), file.getParent(), null, true);
        return loadEntry(file);
    }

    /**
     * Loads the program of {@code entryFile} in the package rooted at {@code packageRoot}, which
     * holds its `package.toml`; the entry file must be under it.
     */
    public Program load(Path entryFile, Path packageRoot) {
        var file = entryFile.toAbsolutePath().normalize();
        readPackages(packageRoot);
        if (!file.startsWith(app.root())) throw new IllegalArgumentException(file + " is not in " + app.root());
        return loadEntry(file);
    }

    /**
     * Loads every module of the package rooted at {@code packageRoot} (and what they reach), for
     * checking a library as a whole: the program has no entry.
     */
    public Program loadLibrary(Path packageRoot) {
        readPackages(packageRoot);
        for (var local : moduleFiles(app.root())) find(app, local);
        return finish(null);
    }

    private Program loadEntry(Path file) {
        var rel = app.root().relativize(file);
        var segments = new ArrayList<String>();
        rel.forEach(p -> segments.add(p.toString()));
        String last = segments.getLast();
        if (last.endsWith(".3b")) segments.set(segments.size() - 1, last.substring(0, last.length() - 3));
        var entryPath = new ModulePath(segments);
        Module entry = parse(app, entryPath, file, new Source(sourceName(file), reader.apply(file)));
        found.computeIfAbsent(app, k -> new HashMap<>()).put(entryPath, Optional.of(entry));
        return finish(entry);
    }

    private Program finish(Module entry) {
        for (var p : List.of(PRELUDE, COLLECTIONS, C, IO)) {
            if (find(Package.STD, p) == null) throw new IllegalStateException("missing std module " + p);
        }
        while (!pending.isEmpty()) discoverImports(pending.pop());

        for (Module m : loaded) declareItems(m);
        var prelude = preludeNames();
        for (Module m : loaded) {
            resolveImports(m);
            if (!(m.isStd() && m.local().equals(PRELUDE))) m.scope().setPrelude(prelude);
        }
        var byPackage = new HashMap<Package, Map<ModulePath, Module>>();
        for (Module m : loaded) byPackage.computeIfAbsent(m.pkg(), k -> new LinkedHashMap<>()).put(m.local(), m);
        var pkgs = packages.isEmpty() ? List.of(app) : List.copyOf(packages.values());
        return new Program(entry, app, pkgs, loaded, byPackage);
    }

    // ---- Packages (§2.5) ----

    /** Reads the application package at {@code root} and its dependencies, transitively. */
    private void readPackages(Path root) {
        var dir = root.toAbsolutePath().normalize();
        // The application's sources are named relative to its root, which must be known first.
        app = new Package("_", dir, null, true);
        app = readPackage(dir, null, new ArrayList<>(), true);
    }

    /**
     * The package in {@code dir}, reached by dependency {@code via} (null for the application);
     * null after an error. {@code stack} holds the packages being read, to find cycles.
     */
    private Package readPackage(Path dir, Manifest.Dependency via, List<Package> stack, boolean application) {
        var manifestFile = dir.resolve(Manifest.FILE);
        if (!Files.isRegularFile(manifestFile)) {
            if (via != null) {
                diagnostics.error(Code.MANIFEST, via.span(), "dependency '" + via.key() + "': there is no " + Manifest.FILE + " in '" + via.path() + "'");
                return null;
            }
            throw new IllegalArgumentException("no " + Manifest.FILE + " in " + dir);
        }
        Path real;
        try {
            real = dir.toRealPath();
        } catch (IOException e) {
            real = dir;
        }
        var existing = packages.get(real);
        if (existing != null) {
            int at = stack.indexOf(existing);
            if (at >= 0) {
                var cycle = new ArrayList<String>();
                for (var p : stack.subList(at, stack.size())) cycle.add(p.name());
                cycle.add(existing.name());
                diagnostics.error(Code.PACKAGE_CYCLE, via.span(), "package dependency cycle: " + String.join(" -> ", cycle));
                return null;
            }
            return existing;
        }
        var manifest = Manifest.parse(new Source(sourceName(manifestFile), reader.apply(manifestFile)), diagnostics);
        var pkg = new Package(manifest.name(), dir, manifest, application);
        packages.put(real, pkg);
        stack.add(pkg);
        for (var dep : manifest.dependencies()) {
            var target = readPackage(dir.resolve(dep.path()).normalize(), dep, stack, false);
            if (target == null) continue;
            checkKey(pkg, dep);
            pkg.addDependency(dep.key(), target);
        }
        stack.removeLast();
        return pkg;
    }

    /** A key may not be the name of a top-level module or directory of the depending package (§2.5). */
    private void checkKey(Package pkg, Manifest.Dependency dep) {
        String what = null;
        if (existsExactly(pkg.root(), List.of(dep.key()))) what = "module '" + dep.key() + "' (" + dep.key() + ".3b)";
        else if (isModuleDirectory(pkg.root(), dep.key())) what = "directory '" + dep.key() + "/'";
        if (what != null) {
            diagnostics.error(Code.KEY_CLASH, dep.span(), "dependency key '" + dep.key() + "' clashes with the top-level "
                    + what + " of package '" + pkg.name() + "'; rename the key");
        }
    }

    /** {@code root/name} is a directory, spelled exactly so, that is not a package of its own. */
    private static boolean isModuleDirectory(Path root, String name) {
        try (var entries = Files.list(root)) {
            if (entries.noneMatch(p -> p.getFileName().toString().equals(name))) return false;
        } catch (IOException e) {
            return false;
        }
        var dir = root.resolve(name);
        return Files.isDirectory(dir) && !Files.exists(dir.resolve(Manifest.FILE));
    }

    /** Every module file under {@code root}, outside nested packages, whose path is made of identifiers. */
    private static List<ModulePath> moduleFiles(Path root) {
        var out = new ArrayList<ModulePath>();
        try (var files = Files.walk(root)) {
            for (var f : files.sorted().toList()) {
                if (!f.toString().endsWith(".3b") || !Files.isRegularFile(f)) continue;
                var segments = new ArrayList<String>();
                root.relativize(f).forEach(p -> segments.add(p.toString()));
                String last = segments.removeLast();
                segments.add(last.substring(0, last.length() - 3));
                if (segments.stream().allMatch(ModuleLoader::isIdentifier)) out.add(new ModulePath(segments));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static boolean isIdentifier(String s) {
        return s.matches("[A-Za-z_][A-Za-z0-9_]*") && !s.equals("_");
    }

    /** What diagnostics call {@code file}: its path relative to the application's root. */
    private String sourceName(Path file) {
        return app.root().relativize(file).toString().replace('\\', '/');
    }

    // ---- Loading ----

    /** The module {@code path} names in the code of {@code from} (§2.5), loading it on first use; null if none. */
    private Module find(Module from, ModulePath path) {
        var loc = from.pkg().locate(path);
        return loc == null ? null : find(loc.pkg(), loc.local());
    }

    /** The module at {@code local} in {@code pkg}, loading it on first use; null if there is none. */
    private Module find(Package pkg, ModulePath local) {
        var cache = found.computeIfAbsent(pkg, k -> new HashMap<>());
        var cached = cache.get(local);
        if (cached != null) return cached.orElse(null);
        Module m = null;
        if (pkg.isStd()) {
            if (local.segments().size() == 1 && STD_MODULES.contains(local.last())) {
                String name = "std/" + local.last() + ".3b";
                String text = resource(name);
                if (local.equals(C)) text += cIntegerAliases();
                m = parse(pkg, local, null, new Source(name, text));
            }
        } else if (existsExactly(pkg.root(), local.segments())) {
            var file = pkg.root().resolve(String.join("/", local.segments()) + ".3b");
            m = parse(pkg, local, file, new Source(sourceName(file), reader.apply(file)));
        }
        cache.put(local, Optional.ofNullable(m));
        return m;
    }

    private Module parse(Package pkg, ModulePath local, Path file, Source source) {
        var m = new Module(pkg, local, file, source, AstParser.compilationUnit(source, diagnostics));
        loaded.add(m);
        pending.push(m);
        return m;
    }

    /**
     * Loads every module a `use` in {@code m} might name, and the modules named by fully-qualified
     * paths in its types and bodies (§2.2), so resolution can see them all.
     */
    private void discoverImports(Module m) {
        if (m.ast() == null) return;
        var inScope = new java.util.HashSet<>(PRIMITIVES);
        inScope.addAll(List.of("Self", "Map", "Set", "c"));
        inScope.addAll(IO_PRELUDE);
        var prelude = found.getOrDefault(Package.STD, Map.of()).get(PRELUDE);
        if (prelude != null && prelude.isPresent() && prelude.get().ast() != null) {
            for (Item item : prelude.get().ast().items()) inScope.add(itemName(item));
        }
        for (Item item : m.ast().items()) {
            inScope.add(itemName(item));
            if (item instanceof Item.Extern x) x.fns().forEach(f -> inScope.add(f.name().text()));
            if (!(item instanceof Item.Use u)) continue;
            var path = pathOf(u.path());
            find(m, path);
            if (path.parent() != null) find(m, path.parent());
            if (u.glob()) {
                var target = find(m, path);
                if (target != null && target.ast() != null) {
                    for (Item t : target.ast().items()) {
                        if (target.exposes(t.visibility(), m)) inScope.add(itemName(t));
                        if (t instanceof Item.Extern x) x.fns().forEach(f -> inScope.add(f.name().text()));
                        if (t instanceof Item.Use r && !r.glob() && target.exposes(r.visibility(), m)) {
                            if (r.isGroup()) r.group().forEach(n -> inScope.add((n.alias() != null ? n.alias() : n.name()).text()));
                            else inScope.add((r.alias() != null ? r.alias() : r.path().getLast()).text());
                        }
                    }
                }
            } else if (u.isGroup()) {
                for (var n : u.group()) {
                    find(m, path.child(n.name().text()));
                    inScope.add((n.alias() != null ? n.alias() : n.name()).text());
                }
            } else {
                inScope.add((u.alias() != null ? u.alias() : u.path().getLast()).text());
            }
        }
        AstWalk.walk(m.ast(), n -> {
            if (n instanceof TypeParam tp) inScope.add(tp.name().text());
        });
        AstWalk.walk(m.ast(), n -> {
            List<Name> names = switch (n) {
                case Expr.Path p -> p.segments().stream().map(Expr.PathSegment::name).toList();
                case TypeExpr.Named t -> t.path();
                default -> List.of();
            };
            if (names.size() < 2 || inScope.contains(names.getFirst().text())) return;
            for (int k = 1; k < names.size(); k++) find(m, pathOf(names.subList(0, k)));
        });
    }

    private static String itemName(Item item) {
        return switch (item) {
            case Item.Fn f -> f.name().text();
            case Item.Struct s -> s.name().text();
            case Item.Class c -> c.name().text();
            case Item.Trait t -> t.name().text();
            case Item.Enum e -> e.name().text();
            case Item.TypeAlias a -> a.name().text();
            case Item.Const c -> c.name().text();
            case Item.Static s -> s.name().text();
            case Item.Use u -> "";
            case Item.Impl i -> "";
            case Item.Extern x -> "";
        };
    }

    /**
     * {@code root/a/b.3b} exists with exactly this spelling, also on case-insensitive file systems
     * (module names are case-sensitive), and not inside a nested package (§2.5).
     */
    private static boolean existsExactly(Path root, List<String> segments) {
        Path dir = root;
        for (int i = 0; i < segments.size(); i++) {
            String entry = i + 1 < segments.size() ? segments.get(i) : segments.get(i) + ".3b";
            if (!Files.isDirectory(dir) || i > 0 && Files.exists(dir.resolve(Manifest.FILE))) return false;
            try (var entries = Files.list(dir)) {
                if (entries.noneMatch(p -> p.getFileName().toString().equals(entry))) return false;
            } catch (IOException e) {
                return false;
            }
            dir = dir.resolve(entry);
        }
        return Files.isRegularFile(dir);
    }

    public static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /**
     * The C integer aliases of `std::c` for this platform (§14.2), sized from the native linker's
     * canonical layouts. Plain `char` is unsigned on Linux for ARM, PowerPC, s390 and RISC-V.
     */
    static String cIntegerAliases() {
        var layouts = Linker.nativeLinker().canonicalLayouts();
        var sb = new StringBuilder("\n// Appended by the loader for this platform.\n");
        String arch = System.getProperty("os.arch");
        boolean unsignedChar = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")
                && (arch.startsWith("aarch64") || arch.startsWith("arm") || arch.startsWith("ppc")
                || arch.startsWith("s390") || arch.startsWith("riscv"));
        sb.append("pub type char = ").append(unsignedChar ? "u8" : "i8").append(";\n");
        sb.append("pub type uchar = u8;\n");
        for (String name : List.of("short", "int", "long", "longlong")) {
            long bits = layouts.get(name.equals("longlong") ? "long long" : name).byteSize() * 8;
            sb.append("pub type ").append(name).append(" = i").append(bits).append(";\n");
            sb.append("pub type u").append(name).append(" = u").append(bits).append(";\n");
        }
        return sb.toString();
    }

    private static String resource(String name) {
        try (InputStream in = ModuleLoader.class.getResourceAsStream("/" + name)) {
            if (in == null) throw new IllegalStateException("missing resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- Declarations ----

    private void declareItems(Module m) {
        if (m.ast() == null) return;
        for (Item item : m.ast().items()) {
            checkAttributes(item.attributes());
            switch (item) {
                case Item.Use u -> {
                    if (u.visibility() == Visibility.PROT) {
                        diagnostics.error(Code.BAD_MODIFIER, u.span(), "`prot` is only allowed in classes");
                    }
                }
                case Item.Impl i -> i.members().forEach(this::checkMemberAttributes);
                case Item.Extern x -> x.fns().forEach(f -> {
                    checkAttributes(f.attributes());
                    declare(m, f.name(), f);
                });
                case Item.Fn f -> declare(m, f.name(), f);
                case Item.Struct s -> declareType(m, s.name(), s, s.members());
                case Item.Class c -> declareType(m, c.name(), c, c.members());
                case Item.Trait t -> declareType(m, t.name(), t, t.members());
                case Item.Enum e -> declareType(m, e.name(), e, List.of());
                case Item.TypeAlias t -> declareType(m, t.name(), t, List.of());
                case Item.Const c -> declare(m, c.name(), c);
                case Item.Static s -> declare(m, s.name(), s);
            }
        }
    }

    private void declareType(Module m, Name name, Item item, List<Member> members) {
        if (PRIMITIVES.contains(name.text()) && !m.isStd()) {
            diagnostics.error(Code.PRIMITIVE_NAME, name.span(), "'" + name.text() + "' is a primitive type name");
        }
        members.forEach(this::checkMemberAttributes);
        declare(m, name, item);
    }

    /** Functions with the same name form an overload set (§4.4); any other repeat is an error. */
    private void declare(Module m, Name name, Item item) {
        var existing = m.scope().items(name.text());
        if (!existing.isEmpty() && !(item instanceof Item.Fn && existing.getFirst().item() instanceof Item.Fn)) {
            duplicate(name, existing.getFirst().item().span(), "previously defined here");
            return;
        }
        m.scope().addItem(name.text(), new Symbol.Decl(m, item));
    }

    private void duplicate(Name name, Span previous, String note) {
        diagnostics.add(new Diagnostic(Code.DUPLICATE_NAME, name.span(),
                "'" + name.text() + "' is already defined in this module",
                List.of(new Diagnostic.Note(previous, note))));
    }

    private void checkMemberAttributes(Member member) {
        checkAttributes(member.attributes());
    }

    private void checkAttributes(List<Attribute> attributes) {
        for (Attribute a : attributes) {
            if (!ATTRIBUTES.contains(a.name().text())) {
                diagnostics.error(Code.UNKNOWN_ATTRIBUTE, a.span(), "unknown attribute '@" + a.name().text() + "'");
            }
        }
    }

    // ---- Imports ----

    /** What a module exports under a name, with the visibility it is exported at (§2.2, §2.3). */
    private record Exported(Symbol symbol, Visibility visibility) {}

    /** Each `use` being resolved (false) or resolved (true). Re-exports are resolved on demand. */
    private final Map<Item.Use, Boolean> useState = new HashMap<>();

    private void resolveImports(Module m) {
        if (m.ast() == null) return;
        for (Item item : m.ast().items()) if (item instanceof Item.Use u) resolveUse(m, u);
    }

    /**
     * Resolves one `use` of {@code m}, once. A glob first needs m's explicit imports, which shadow
     * it. A `use` reached again while it is being resolved is a cycle of re-exports: the names it
     * binds are not available yet, so a lookup through it finds nothing.
     */
    private void resolveUse(Module m, Item.Use u) {
        if (useState.containsKey(u)) return;
        useState.put(u, false);
        if (u.glob()) {
            for (Item item : m.ast().items()) if (item instanceof Item.Use e && !e.glob()) resolveUse(m, e);
            resolveGlob(m, u);
        } else {
            resolveExplicit(m, u);
        }
        useState.put(u, true);
    }

    private void resolveExplicit(Module m, Item.Use u) {
        var path = pathOf(u.path());
        if (!u.isGroup()) {
            var targets = resolve(m, path, u.path());
            if (targets != null) bind(m, u, u.alias() != null ? u.alias() : u.path().getLast(), targets, false);
            return;
        }
        boolean reported = false;
        for (var n : u.group()) {
            var full = path.child(n.name().text());
            if (find(m, path) == null && find(m, full) == null) {
                // One error for the whole group: its common prefix is missing.
                if (!reported) moduleNotFound(m, path, u.path());
                reported = true;
                continue;
            }
            var names = new ArrayList<>(u.path());
            names.add(n.name());
            var targets = resolve(m, full, names);
            if (targets != null) bind(m, u, n.alias() != null ? n.alias() : n.name(), targets, false);
        }
    }

    private void resolveGlob(Module m, Item.Use u) {
        var path = pathOf(u.path());
        var target = find(m, path);
        if (target == null) {
            moduleNotFound(m, path, u.path());
            return;
        }
        for (String name : exportedNames(target)) {
            var visible = visible(target, name, m);
            if (visible.isEmpty() || !m.scope().items(name).isEmpty()) continue;
            var previous = m.scope().importNamed(name);
            if (previous != null && !previous.glob()) continue;
            bind(m, u, new Name(name, span(u.path())), visible, true);
        }
    }

    /** The names module {@code p} declares or re-exports, resolving its re-exports first. */
    private Set<String> exportedNames(Module p) {
        var names = new java.util.LinkedHashSet<>(p.scope().items().keySet());
        if (p.ast() == null) return names;
        for (Item item : p.ast().items()) {
            if (item instanceof Item.Use u && u.visibility() != Visibility.PRIVATE) resolveUse(p, u);
        }
        p.scope().imports().forEach((name, i) -> {
            if (i.visibility() != Visibility.PRIVATE) names.add(name);
        });
        return names;
    }

    /** What module {@code p} exports as {@code name} that {@code from} can see: its items, else a re-export. */
    private List<Exported> visible(Module p, String name, Module from) {
        var items = p.scope().items(name);
        if (!items.isEmpty()) {
            return items.stream().filter(d -> p.exposes(d.item().visibility(), from))
                    .map(d -> new Exported(d, d.item().visibility())).toList();
        }
        var i = reexport(p, name);
        if (i == null || !p.exposes(i.visibility(), from)) return List.of();
        return i.targets().stream().map(t -> new Exported(t, i.visibility())).toList();
    }

    /** The re-export of {@code p} binding {@code name}, resolving the `use`s that may bind it; null if none. */
    private ModuleScope.Import reexport(Module p, String name) {
        if (p.ast() == null) return null;
        for (Item item : p.ast().items()) {
            if (item instanceof Item.Use u && u.visibility() != Visibility.PRIVATE && (u.glob() || binds(u, name))) {
                resolveUse(p, u);
            }
        }
        return p.scope().reexport(name);
    }

    private static boolean binds(Item.Use u, String name) {
        if (u.isGroup()) {
            return u.group().stream().anyMatch(n -> (n.alias() != null ? n.alias() : n.name()).text().equals(name));
        }
        return (u.alias() != null ? u.alias() : u.path().getLast()).text().equals(name);
    }

    /**
     * What {@code path} names from module {@code from}: a module, or what the parent module exports
     * under that name and {@code from} can see. Null after an error.
     */
    private List<Exported> resolve(Module from, ModulePath path, List<Name> names) {
        Name last = names.getLast();
        Module asModule = find(from, path);
        Module parent = path.parent() == null ? null : find(from, path.parent());
        var items = parent == null ? List.<Symbol.Decl>of() : parent.scope().items(path.last());
        var reexport = parent == null || !items.isEmpty() ? null : reexport(parent, path.last());
        Span declared = !items.isEmpty() ? items.getFirst().item().span() : reexport != null ? reexport.name().span() : null;
        if (asModule != null && declared != null) {
            diagnostics.add(new Diagnostic(Code.AMBIGUOUS_IMPORT, span(names),
                    "'" + path + "' names both a module and an item of module '" + parent.path() + "'",
                    List.of(new Diagnostic.Note(declared, "the item is defined here"))));
            return null;
        }
        if (asModule != null) return List.of(new Exported(new Symbol.ModuleRef(asModule), Visibility.PUB));
        // A module with syntax errors has no known items; its errors were already reported.
        if (parent != null && parent.ast() == null) return null;
        if (declared != null) {
            var visible = visible(parent, path.last(), from);
            if (visible.isEmpty()) {
                var vis = !items.isEmpty() ? items.getFirst().item().visibility() : reexport.visibility();
                diagnostics.add(notVisible(last, parent, vis, declared));
                return null;
            }
            return visible;
        }
        if (parent != null) {
            diagnostics.error(Code.ITEM_NOT_FOUND, last.span(),
                    "module '" + parent.path() + "' has no item named '" + last.text() + "'");
        } else {
            moduleNotFound(from, path.parent() != null ? path.parent() : path,
                    path.parent() != null ? names.subList(0, names.size() - 1) : names);
        }
        return null;
    }

    /** E0102 for a name of {@code m}, declared at {@code declared} with {@code vis}, that the user cannot see (§2.3). */
    public static Diagnostic notVisible(Name name, Module m, Visibility vis, Span declared) {
        boolean internal = vis == Visibility.INTERNAL;
        return new Diagnostic(Code.PRIVATE_ITEM, name.span(),
                "'" + name.text() + "' is " + (internal ? "internal to package '" + m.pkg().name() + "'" : "private to module '" + m.path() + "'"),
                List.of(new Diagnostic.Note(declared, internal ? "declared here as `internal`" : "declared here without `pub`")));
    }

    /**
     * E0100, with a note when the path leads into a nested package or starts with a key of another
     * package: dependencies are not transitive (§2.5).
     */
    private void moduleNotFound(Module from, ModulePath path, List<Name> names) {
        var notes = new ArrayList<Diagnostic.Note>();
        var pkg = from.pkg();
        var first = path.segments().getFirst();
        if (pkg.root() != null && !first.equals("std") && !pkg.dependencies().containsKey(first)) {
            var dir = pkg.root();
            for (var seg : path.segments()) {
                dir = dir.resolve(seg);
                var manifest = dir.resolve(Manifest.FILE);
                if (Files.isRegularFile(manifest)) {
                    var source = new Source(sourceName(manifest), reader.apply(manifest));
                    notes.add(new Diagnostic.Note(new Span(source, 0, 0), "'" + pkg.root().relativize(dir).toString().replace('\\', '/')
                            + "' is a package of its own, not part of '" + pkg.name() + "'; depend on it to use it"));
                    break;
                }
            }
            for (var other : packages.values()) {
                if (other == pkg || other.manifest() == null) continue;
                for (var dep : other.manifest().dependencies()) {
                    if (dep.key().equals(first) && other.dependencies().containsKey(first)) {
                        notes.add(new Diagnostic.Note(dep.span(), "'" + first + "' is a dependency of package '" + other.name()
                                + "', not of '" + pkg.name() + "'; dependencies are not transitive"));
                    }
                }
            }
        }
        diagnostics.add(new Diagnostic(Code.MODULE_NOT_FOUND, span(names), "module '" + path + "' not found", notes));
    }

    /**
     * Binds {@code name} in {@code m} by `use` {@code u}. A re-export (§2.2) cannot name a module;
     * an explicit one cannot be more visible than what it names, and a glob re-exports each name
     * at the lesser of the two visibilities.
     */
    private void bind(Module m, Item.Use u, Name name, List<Exported> targets, boolean glob) {
        var vis = u.visibility() == Visibility.PROT ? Visibility.PRIVATE : u.visibility();
        if (vis != Visibility.PRIVATE) {
            if (targets.stream().anyMatch(t -> t.symbol() instanceof Symbol.ModuleRef)) {
                diagnostics.error(Code.PUB_USE, name.span(), "a module cannot be re-exported");
                vis = Visibility.PRIVATE;
            } else {
                var least = targets.stream().map(Exported::visibility).min(ModuleLoader::compare).orElseThrow();
                if (compare(least, vis) < 0) {
                    if (glob) {
                        vis = least;
                    } else {
                        diagnostics.add(new Diagnostic(Code.PUB_USE, name.span(),
                                "'" + name.text() + "' is " + describe(least) + " and cannot be re-exported as `"
                                        + vis.name().toLowerCase(Locale.ROOT) + "`",
                                List.of()));
                        vis = Visibility.PRIVATE;
                    }
                }
            }
        }
        var symbols = targets.stream().map(Exported::symbol).toList();
        var items = m.scope().items(name.text());
        if (!items.isEmpty()) {
            duplicate(name, items.getFirst().item().span(), "previously defined here");
            return;
        }
        var previous = m.scope().importNamed(name.text());
        if (previous != null) {
            // An explicit import resolved after a glob (in a re-export cycle) still shadows it.
            if (previous.glob() && !glob) {
                m.scope().addImport(new ModuleScope.Import(name, symbols, vis, false));
                return;
            }
            // Importing the same thing twice is harmless.
            if (!sameTargets(previous.targets(), symbols)) duplicate(name, previous.name().span(), "previously imported here");
            return;
        }
        m.scope().addImport(new ModuleScope.Import(name, symbols, vis, glob));
    }

    /** Orders visibilities by reach: private (and prot) < internal < pub. */
    private static int compare(Visibility a, Visibility b) {
        return Integer.compare(reach(a), reach(b));
    }

    private static int reach(Visibility v) {
        return switch (v) {
            case PRIVATE, PROT -> 0;
            case INTERNAL -> 1;
            case PUB -> 2;
        };
    }

    private static String describe(Visibility v) {
        return v == Visibility.INTERNAL ? "internal" : "private";
    }

    private static boolean sameTargets(List<Symbol> a, List<Symbol> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            var x = a.get(i);
            var y = b.get(i);
            boolean same = switch (x) {
                case Symbol.Decl d -> y instanceof Symbol.Decl e && d.item() == e.item();
                case Symbol.ModuleRef r -> y instanceof Symbol.ModuleRef s && r.module() == s.module();
            };
            if (!same) return false;
        }
        return true;
    }

    // ---- Prelude (§16) ----

    /**
     * The pub items of std::prelude, plus Map and Set from std::collections, the printing functions
     * of std::io and the module alias `c`.
     */
    private Map<String, List<Symbol>> preludeNames() {
        var names = new LinkedHashMap<String, List<Symbol>>();
        find(Package.STD, PRELUDE).scope().items().forEach((name, decls) -> {
            var pub = decls.stream().filter(d -> d.item().visibility() == Visibility.PUB).<Symbol>map(d -> d).toList();
            if (!pub.isEmpty()) names.put(name, pub);
        });
        for (String name : List.of("Map", "Set")) names.put(name, List.copyOf(find(Package.STD, COLLECTIONS).scope().items(name)));
        for (String name : IO_PRELUDE) names.put(name, List.copyOf(find(Package.STD, IO).scope().items(name)));
        names.put("c", List.of(new Symbol.ModuleRef(find(Package.STD, C))));
        return Map.copyOf(names);
    }

    private static ModulePath pathOf(List<Name> names) {
        return new ModulePath(names.stream().map(Name::text).toList());
    }

    private static Span span(List<Name> names) {
        return names.getFirst().span().to(names.getLast().span());
    }
}
