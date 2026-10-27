package dev.threebolt.types;

import dev.threebolt.ast.Expr;
import dev.threebolt.ast.Visibility;
import dev.threebolt.diag.Span;

/** A struct or class field (§4.5). {@code slot} indexes the owner's {@link TypeInfo#allFields}. */
public final class FieldInfo {
    private final String name;
    private final TypeInfo owner;
    private final Span span;
    public final Visibility visibility;
    public Type type = Type.ERROR;
    /** The default value's source, or null. */
    public final Expr defaultValue;
    /** The checked default value, filled in by the checker. */
    public dev.threebolt.ir.Ir.Expr defaultIr;
    public int slot;

    public FieldInfo(String name, TypeInfo owner, Visibility visibility, Expr defaultValue, Span span) {
        this.name = name;
        this.owner = owner;
        this.visibility = visibility;
        this.defaultValue = defaultValue;
        this.span = span;
    }

    public String name() {
        return name;
    }

    public TypeInfo owner() {
        return owner;
    }

    public Span span() {
        return span;
    }

    @Override
    public String toString() {
        return owner.name() + "." + name;
    }
}
