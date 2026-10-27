package dev.threebolt.ast;

import dev.threebolt.diag.Span;

/** A function parameter (§4.3). */
public sealed interface Param extends Node {
    /** {@code self} or {@code mut self}. */
    record Receiver(boolean isMutable, Span span) implements Param {}

    /** {@code [mut] name: T [= default]}; {@code defaultValue} is null for required parameters. */
    record Named(boolean isMutable, Name name, TypeExpr type, Expr defaultValue, Span span) implements Param {}

    /** {@code ...} in an extern declaration (§14.1). */
    record Variadic(Span span) implements Param {}
}
