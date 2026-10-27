package dev.threebolt.resolve;

import dev.threebolt.ast.Item;

/** What a module-level name refers to. */
public sealed interface Symbol {
    /** A declaration; a function name may have several (an overload set, §4.4). */
    record Decl(Module module, Item item) implements Symbol {}

    /** A module, bound by {@code use a::b;} (or the prelude's {@code c}). */
    record ModuleRef(Module module) implements Symbol {}
}
