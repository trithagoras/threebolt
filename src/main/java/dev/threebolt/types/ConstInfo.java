package dev.threebolt.types;

import dev.threebolt.ast.Item;
import dev.threebolt.resolve.Module;

/** A {@code const} (§4.2): evaluated at compile time. */
public final class ConstInfo {
    public final Item.Const ast;
    public final Module module;
    public Type type = Type.ERROR;
    /** The runtime value, once evaluated; null before (or after an error, with {@link #failed}). */
    public Object value;
    public boolean evaluated;
    public boolean evaluating;
    public boolean failed;

    public ConstInfo(Item.Const ast, Module module) {
        this.ast = ast;
        this.module = module;
    }

    public String name() {
        return ast.name().text();
    }
}
