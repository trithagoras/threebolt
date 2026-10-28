package dev.threebolt.types;

import dev.threebolt.ast.Item;
import dev.threebolt.ast.Name;
import dev.threebolt.ast.Visibility;
import dev.threebolt.diag.Code;
import dev.threebolt.diag.Diagnostic;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Span;
import dev.threebolt.resolve.Module;
import dev.threebolt.resolve.ModuleLoader;
import dev.threebolt.resolve.ModulePath;
import dev.threebolt.resolve.Program;
import dev.threebolt.resolve.Symbol;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves module-level paths ({@code a::b::C}) to declarations (§2.2): through the module scope
 * (items, imports, prelude), modules bound by `use`, and fully-qualified module paths.
 */
public final class Names {
    /** What a path prefix names. */
    public sealed interface Entity {}

    public record ModuleEntity(Module module) implements Entity {}

    public record TypeEntity(TypeInfo type) implements Entity {}

    public record PrimEntity(Prim prim) implements Entity {}

    public record FnEntity(List<FnInfo> fns) implements Entity {}

    public record ConstEntity(ConstInfo info) implements Entity {}

    public record StaticEntity(StaticInfo info) implements Entity {}

    /** {@code entity} names the first {@code consumed} segments; the rest are members of it. */
    public record Resolved(Entity entity, int consumed) {}

    private final Program program;
    private final Diagnostics diagnostics;
    final SymbolIndex symbolIndex;
    /** Set by the checker when answering a completion request. */
    Completer completer;
    /** Set by the checker: told which imports are used. */
    Lints lints;
    /** Semantic info for each declaration item. */
    final Map<Item, Object> infos = new HashMap<>();

    Names(Program program, Diagnostics diagnostics, SymbolIndex index) {
        this.program = program;
        this.diagnostics = diagnostics;
        this.symbolIndex = index;
    }

    public Object info(Item item) {
        return infos.get(item);
    }

    /**
     * Resolves the longest module/item prefix of {@code path} as seen from {@code from}. Reports
     * an error and returns null when even the first segment names nothing.
     */
    public Resolved resolve(Module from, List<Name> path) {
        Name first = path.getFirst();
        var symbols = from.scope().lookup(first.text());
        if (symbols.isEmpty()) {
            var prim = Prim.named(first.text());
            if (prim != null) return new Resolved(new PrimEntity(prim), 1);
            return fullyQualified(from, path);
        }
        noteImport(from, first.text());
        var entity = entity(symbols);
        record(first, entity);
        return continueFrom(from, entity, path, 1);
    }

    /** A module-level name only (no path); null if unbound. */
    public Entity lookup(Module from, String name) {
        var symbols = from.scope().lookup(name);
        if (symbols.isEmpty()) {
            var prim = Prim.named(name);
            return prim == null ? null : new PrimEntity(prim);
        }
        noteImport(from, name);
        return entity(symbols);
    }

    private void noteImport(Module from, String name) {
        if (lints == null || !from.scope().items(name).isEmpty()) return;
        var i = from.scope().imports().get(name);
        if (i != null) lints.usedImport(i);
    }

    private Resolved fullyQualified(Module from, List<Name> path) {
        // The longest prefix naming a module wins; then continue with its items.
        for (int k = path.size(); k >= 1; k--) {
            var mp = new ModulePath(path.subList(0, k).stream().map(Name::text).toList());
            var m = program.module(from, mp);
            if (m != null) {
                record(path.get(k - 1), new ModuleEntity(m));
                return continueFrom(from, new ModuleEntity(m), path, k);
            }
        }
        if (completer != null && path.size() > 1 && completer.at(path.getLast())) {
            // `std::|`: a path prefix that is not a module itself.
            completer.modulePrefix(from, new ModulePath(path.subList(0, path.size() - 1).stream().map(Name::text).toList()),
                    path.getLast());
            return null;
        }
        Name first = path.getFirst();
        diagnostics.error(Code.UNRESOLVED_NAME, first.span(), "cannot find '" + first.text() + "' in this scope");
        return null;
    }

    private Resolved continueFrom(Module from, Entity entity, List<Name> path, int index) {
        while (entity instanceof ModuleEntity(Module m) && index < path.size()) {
            Name seg = path.get(index);
            if (completer != null && completer.at(seg)) {
                completer.moduleItems(m, from, seg);
                return null;
            }
            var items = m.scope().items(seg.text());
            var reexport = items.isEmpty() ? m.scope().reexport(seg.text()) : null;
            if (reexport != null) {
                // A re-export (§2.2) names items (never a module) of another module.
                if (!m.exposes(reexport.visibility(), from)) {
                    diagnostics.add(ModuleLoader.notVisible(seg, m, reexport.visibility(), reexport.name().span()));
                    return null;
                }
                entity = entity(reexport.targets());
                record(seg, entity);
                index++;
                continue;
            }
            if (items.isEmpty()) {
                // A submodule: a::b where a/b.3b exists.
                var sub = program.child(m, seg.text());
                if (sub != null) {
                    entity = new ModuleEntity(sub);
                    record(seg, entity);
                    index++;
                    continue;
                }
                if (m.ast() != null) {
                    diagnostics.error(Code.ITEM_NOT_FOUND, seg.span(),
                            "module '" + m.path() + "' has no item named '" + seg.text() + "'");
                }
                return null;
            }
            if (m != from) {
                var owner = m;
                var visible = items.stream().filter(d -> owner.exposes(d.item().visibility(), from)).toList();
                if (visible.isEmpty()) {
                    var first = items.getFirst().item();
                    diagnostics.add(ModuleLoader.notVisible(seg, m, first.visibility(), first.span()));
                    return null;
                }
                items = visible;
            }
            entity = entity(items);
            record(seg, entity);
            index++;
        }
        return new Resolved(entity, index);
    }

    private void record(Name name, Entity entity) {
        Object target = switch (entity) {
            case ModuleEntity(Module m) -> m;
            case TypeEntity(TypeInfo t) -> t;
            case FnEntity(List<FnInfo> fns) -> fns.getFirst();
            case ConstEntity(ConstInfo c) -> c;
            case StaticEntity(StaticInfo s) -> s;
            case PrimEntity p -> null;
        };
        symbolIndex.ref(name.span(), target, null);
    }

    Entity entity(List<? extends Symbol> symbols) {
        var first = symbols.getFirst();
        if (first instanceof Symbol.ModuleRef r) return new ModuleEntity(r.module());
        var fns = new ArrayList<FnInfo>();
        for (Symbol s : symbols) {
            if (s instanceof Symbol.Decl d && infos.get(d.item()) instanceof FnInfo f) fns.add(f);
        }
        if (!fns.isEmpty()) return new FnEntity(fns);
        Object info = infos.get(((Symbol.Decl) first).item());
        return switch (info) {
            case TypeInfo t -> new TypeEntity(t);
            case ConstInfo c -> new ConstEntity(c);
            case StaticInfo s -> new StaticEntity(s);
            default -> throw new IllegalStateException("no info for " + first);
        };
    }

    public static Span span(List<Name> names) {
        return names.getFirst().span().to(names.getLast().span());
    }
}
