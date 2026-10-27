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
 * std modules behind the prelude. The source root is the entry file's directory; `std::` modules
 * come from the interpreter's resources. Then builds each module's scope: its items, its
 * resolved imports (§2.2) and the prelude (§16).
 */
public final class ModuleLoader {
    /** The std modules that exist; `std::a::b` is the resource `std/a/b.3b`. */
    public static final Set<String> STD_MODULES = Set.of(
            "prelude", "collections", "c", "process", "thread", "sync", "math", "random", "io");

    private static final ModulePath PRELUDE = ModulePath.of("std", "prelude");
    private static final ModulePath COLLECTIONS = ModulePath.of("std", "collections");
    private static final ModulePath C = ModulePath.of("std", "c");

    private static final Set<String> PRIMITIVES = Set.of(
            "i8", "i16", "i32", "i64", "isize", "u8", "u16", "u32", "u64", "usize", "f32", "f64", "bool", "char", "str");

    private static final Set<String> ATTRIBUTES = Set.of("c", "flags", "link");

    private final Diagnostics diagnostics;
    /** Reads a source file; an editor substitutes its unsaved buffers. */
    private final Function<Path, String> reader;
    private Path root;
    /** Every module path looked up so far; empty when there is no such module. */
    private final Map<ModulePath, Optional<Module>> found = new HashMap<>();
    private final Map<ModulePath, Module> loaded = new LinkedHashMap<>();
    private final ArrayDeque<Module> pending = new ArrayDeque<>();

    public ModuleLoader(Diagnostics diagnostics) {
        this(diagnostics, ModuleLoader::read);
    }

    public ModuleLoader(Diagnostics diagnostics, Function<Path, String> reader) {
        this.diagnostics = diagnostics;
        this.reader = reader;
    }

    public Program load(Path entryFile) {
        var file = entryFile.toAbsolutePath().normalize();
        root = file.getParent();
        String fileName = file.getFileName().toString();
        var entryPath = ModulePath.of(fileName.endsWith(".3b") ? fileName.substring(0, fileName.length() - 3) : fileName);
        Module entry = parse(entryPath, file, new Source(fileName, reader.apply(file)));
        found.put(entryPath, Optional.of(entry));

        for (var p : List.of(PRELUDE, COLLECTIONS, C)) {
            if (find(p) == null) throw new IllegalStateException("missing std module " + p);
        }
        while (!pending.isEmpty()) discoverImports(pending.pop());

        for (Module m : loaded.values()) declareItems(m);
        var prelude = preludeNames();
        for (Module m : loaded.values()) {
            resolveImports(m);
            if (!m.path().equals(PRELUDE)) m.scope().setPrelude(prelude);
        }
        return new Program(entry, java.util.Collections.unmodifiableMap(loaded));
    }

    // ---- Loading ----

    /** The module at {@code path}, loading it on first use; null if there is none. */
    private Module find(ModulePath path) {
        var cached = found.get(path);
        if (cached != null) return cached.orElse(null);
        Module m = null;
        if (path.isStd()) {
            var rel = path.segments().subList(1, path.segments().size());
            if (rel.size() == 1 && STD_MODULES.contains(rel.getFirst())) {
                String name = "std/" + String.join("/", rel) + ".3b";
                String text = resource(name);
                if (path.equals(C)) text += cIntegerAliases();
                m = parse(path, null, new Source(name, text));
            }
        } else if (existsExactly(path.segments())) {
            String name = String.join("/", path.segments()) + ".3b";
            var file = root.resolve(name);
            m = parse(path, file, new Source(name, reader.apply(file)));
        }
        found.put(path, Optional.ofNullable(m));
        return m;
    }

    private Module parse(ModulePath path, Path file, Source source) {
        var m = new Module(path, file, source, AstParser.compilationUnit(source, diagnostics));
        loaded.put(path, m);
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
        var prelude = found.get(PRELUDE);
        if (prelude != null && prelude.isPresent() && prelude.get().ast() != null) {
            for (Item item : prelude.get().ast().items()) inScope.add(itemName(item));
        }
        for (Item item : m.ast().items()) {
            inScope.add(itemName(item));
            if (item instanceof Item.Extern x) x.fns().forEach(f -> inScope.add(f.name().text()));
            if (!(item instanceof Item.Use u)) continue;
            var path = pathOf(u.path());
            find(path);
            if (path.parent() != null) find(path.parent());
            if (u.glob()) {
                var target = find(path);
                if (target != null && target.ast() != null) {
                    for (Item t : target.ast().items()) {
                        if (t.visibility() == Visibility.PUB) inScope.add(itemName(t));
                        if (t instanceof Item.Extern x) x.fns().forEach(f -> inScope.add(f.name().text()));
                    }
                }
            } else if (u.isGroup()) {
                for (var n : u.group()) {
                    find(path.child(n.name().text()));
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
            for (int k = 1; k < names.size(); k++) find(pathOf(names.subList(0, k)));
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
     * (module names are case-sensitive).
     */
    private boolean existsExactly(List<String> segments) {
        Path dir = root;
        for (int i = 0; i < segments.size(); i++) {
            String entry = i + 1 < segments.size() ? segments.get(i) : segments.get(i) + ".3b";
            if (!Files.isDirectory(dir)) return false;
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
                    if (u.visibility() == Visibility.PUB) {
                        diagnostics.error(Code.PUB_USE, u.span(), "`use` cannot be `pub`: there are no re-exports in 1.0");
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

    private void resolveImports(Module m) {
        if (m.ast() == null) return;
        for (Item item : m.ast().items()) {
            if (!(item instanceof Item.Use u) || u.glob()) continue;
            var path = pathOf(u.path());
            if (!u.isGroup()) {
                var targets = resolve(m, path, u.path());
                if (targets != null) bind(m, u.alias() != null ? u.alias() : u.path().getLast(), targets);
                continue;
            }
            boolean reported = false;
            for (var n : u.group()) {
                var full = path.child(n.name().text());
                if (find(path) == null && find(full) == null) {
                    // One error for the whole group: its common prefix is missing.
                    if (!reported) moduleNotFound(path, u.path());
                    reported = true;
                    continue;
                }
                var names = new ArrayList<>(u.path());
                names.add(n.name());
                var targets = resolve(m, full, names);
                if (targets != null) bind(m, n.alias() != null ? n.alias() : n.name(), targets);
            }
        }
        // Glob imports last: the module's items and explicit imports shadow them.
        var globbed = new java.util.HashSet<String>();
        for (Item item : m.ast().items()) {
            if (!(item instanceof Item.Use u) || !u.glob()) continue;
            var path = pathOf(u.path());
            var target = find(path);
            if (target == null) {
                moduleNotFound(path, u.path());
                continue;
            }
            for (var e : target.scope().items().entrySet()) {
                var visible = e.getValue().stream()
                        .filter(d -> target == m || d.item().visibility() == Visibility.PUB).toList();
                if (visible.isEmpty() || !m.scope().items(e.getKey()).isEmpty()) continue;
                var previous = m.scope().importNamed(e.getKey());
                if (previous != null && !globbed.contains(e.getKey())) continue;
                var name = new Name(e.getKey(), span(u.path()));
                globbed.add(e.getKey());
                bind(m, name, List.copyOf(visible));
            }
        }
    }

    /**
     * What {@code path} names from module {@code from}: a module, or the items of that name in
     * the parent module (pub only, unless the parent is {@code from}). Null after an error.
     */
    private List<Symbol> resolve(Module from, ModulePath path, List<Name> names) {
        Name last = names.getLast();
        Module asModule = find(path);
        Module parent = path.parent() == null ? null : find(path.parent());
        var items = parent == null ? List.<Symbol.Decl>of() : parent.scope().items(path.last());
        if (asModule != null && !items.isEmpty()) {
            diagnostics.add(new Diagnostic(Code.AMBIGUOUS_IMPORT, span(names),
                    "'" + path + "' names both a module and an item of module '" + parent.path() + "'",
                    List.of(new Diagnostic.Note(items.getFirst().item().span(), "the item is defined here"))));
            return null;
        }
        if (asModule != null) return List.of(new Symbol.ModuleRef(asModule));
        // A module with syntax errors has no known items; its errors were already reported.
        if (parent != null && parent.ast() == null) return null;
        if (!items.isEmpty()) {
            var visible = items.stream().filter(d -> parent == from || d.item().visibility() == Visibility.PUB).toList();
            if (visible.isEmpty()) {
                diagnostics.add(new Diagnostic(Code.PRIVATE_ITEM, last.span(),
                        "'" + last.text() + "' is private to module '" + parent.path() + "'",
                        List.of(new Diagnostic.Note(items.getFirst().item().span(), "declared here without `pub`"))));
                return null;
            }
            return List.copyOf(visible);
        }
        if (parent != null) {
            diagnostics.error(Code.ITEM_NOT_FOUND, last.span(),
                    "module '" + parent.path() + "' has no item named '" + last.text() + "'");
        } else {
            moduleNotFound(path.parent() != null ? path.parent() : path,
                    path.parent() != null ? names.subList(0, names.size() - 1) : names);
        }
        return null;
    }

    private void moduleNotFound(ModulePath path, List<Name> names) {
        diagnostics.error(Code.MODULE_NOT_FOUND, span(names), "module '" + path + "' not found");
    }

    private void bind(Module m, Name name, List<Symbol> targets) {
        var items = m.scope().items(name.text());
        if (!items.isEmpty()) {
            duplicate(name, items.getFirst().item().span(), "previously defined here");
            return;
        }
        var previous = m.scope().importNamed(name.text());
        if (previous != null) {
            // Importing the same thing twice is harmless.
            if (!sameTargets(previous.targets(), targets)) duplicate(name, previous.name().span(), "previously imported here");
            return;
        }
        m.scope().addImport(new ModuleScope.Import(name, targets));
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

    /** The pub items of std::prelude, plus Map and Set from std::collections and the module alias `c`. */
    private Map<String, List<Symbol>> preludeNames() {
        var names = new LinkedHashMap<String, List<Symbol>>();
        find(PRELUDE).scope().items().forEach((name, decls) -> {
            var pub = decls.stream().filter(d -> d.item().visibility() == Visibility.PUB).<Symbol>map(d -> d).toList();
            if (!pub.isEmpty()) names.put(name, pub);
        });
        for (String name : List.of("Map", "Set")) names.put(name, List.copyOf(find(COLLECTIONS).scope().items(name)));
        names.put("c", List.of(new Symbol.ModuleRef(find(C))));
        return Map.copyOf(names);
    }

    private static ModulePath pathOf(List<Name> names) {
        return new ModulePath(names.stream().map(Name::text).toList());
    }

    private static Span span(List<Name> names) {
        return names.getFirst().span().to(names.getLast().span());
    }
}
