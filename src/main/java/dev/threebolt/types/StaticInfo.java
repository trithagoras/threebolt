package dev.threebolt.types;

import dev.threebolt.ast.Item;
import dev.threebolt.resolve.Module;

/** A {@code static let [mut]} at module level or in a type body (§4.2). Initialized lazily. */
public final class StaticInfo {
    public final Item.Static ast;
    public final Module module;
    /** The declaring type, or null at module level. */
    public final TypeInfo owner;
    public Type type = Type.ERROR;
    public dev.threebolt.ir.Ir.Expr init;

    public StaticInfo(Item.Static ast, Module module, TypeInfo owner) {
        this.ast = ast;
        this.module = module;
        this.owner = owner;
    }

    public String name() {
        return ast.name().text();
    }

    public boolean isMutable() {
        return ast.isMutable();
    }

    @Override
    public String toString() {
        return owner == null ? name() : owner.name() + "::" + name();
    }
}
