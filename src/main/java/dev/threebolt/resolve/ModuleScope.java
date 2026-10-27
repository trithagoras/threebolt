package dev.threebolt.resolve;

import dev.threebolt.ast.Name;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A module's names: its own items, then its imports, then the prelude (§2.2, §16). Items and
 * imports never share a name (that is an error), so their order only matters against the
 * prelude, which both shadow. Primitive type names are not here; they sit below every scope.
 */
public final class ModuleScope {
    /** One binding made by a `use`: {@code name} as written (alias or last segment). */
    public record Import(Name name, List<Symbol> targets) {}

    private final Map<String, List<Symbol.Decl>> items = new LinkedHashMap<>();
    private final Map<String, Import> imports = new LinkedHashMap<>();
    private Map<String, List<Symbol>> prelude = Map.of();

    /** Symbols for {@code name}, innermost source first; empty if unbound. */
    public List<? extends Symbol> lookup(String name) {
        var own = items.get(name);
        if (own != null) return own;
        var imported = imports.get(name);
        if (imported != null) return imported.targets();
        return prelude.getOrDefault(name, List.of());
    }

    /** This module's declarations named {@code name}. */
    public List<Symbol.Decl> items(String name) {
        return items.getOrDefault(name, List.of());
    }

    public Map<String, List<Symbol.Decl>> items() {
        return Collections.unmodifiableMap(items);
    }

    public Map<String, Import> imports() {
        return Collections.unmodifiableMap(imports);
    }

    void addItem(String name, Symbol.Decl decl) {
        items.computeIfAbsent(name, k -> new ArrayList<>()).add(decl);
    }

    void addImport(Import i) {
        imports.put(i.name().text(), i);
    }

    Import importNamed(String name) {
        return imports.get(name);
    }

    void setPrelude(Map<String, List<Symbol>> prelude) {
        this.prelude = prelude;
    }
}
