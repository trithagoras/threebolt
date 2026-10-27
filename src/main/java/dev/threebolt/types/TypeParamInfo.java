package dev.threebolt.types;

import dev.threebolt.diag.Span;
import java.util.ArrayList;
import java.util.List;

/** A generic type parameter (§10), or a trait's {@code Self}. Compared by identity. */
public final class TypeParamInfo {
    private final String name;
    private final Span span;
    /** Trait bounds, resolved by the declarer. */
    public final List<Type.Nominal> bounds = new ArrayList<>();

    public TypeParamInfo(String name, Span span) {
        this.name = name;
        this.span = span;
    }

    public String name() {
        return name;
    }

    public Span span() {
        return span;
    }

    public Type type() {
        return new Type.Param(this);
    }

    @Override
    public String toString() {
        return name;
    }
}
