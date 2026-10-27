package dev.threebolt.ir;

import dev.threebolt.diag.Span;
import dev.threebolt.types.Type;

/** A local binding, parameter or {@code self}; each declaration is its own Local (identity). */
public final class Local {
    private final String name;
    private final boolean isMutable;
    private final Span span;
    public Type type;
    /**
     * Bound by a pattern after `is` (§7.3): unbound wherever the flow state does not say
     * otherwise, so its default narrowing is {@link Type#UNBOUND}.
     */
    public boolean isConditional;

    public Local(String name, boolean isMutable, Type type, Span span) {
        this.name = name;
        this.isMutable = isMutable;
        this.type = type;
        this.span = span;
    }

    public String name() {
        return name;
    }

    public boolean isMutable() {
        return isMutable;
    }

    public Span span() {
        return span;
    }

    @Override
    public String toString() {
        return name;
    }
}
