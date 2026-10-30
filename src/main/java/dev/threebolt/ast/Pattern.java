package dev.threebolt.ast;

import dev.threebolt.diag.Span;
import java.util.List;

/** A pattern (§11). Grouping parentheses are dropped. */
public sealed interface Pattern extends Node {
    /** {@code _}. */
    record Wildcard(Span span) implements Pattern {}

    /**
     * {@code [mut] name [is [not] T]}; {@code isType} is null without {@code is}, and {@code isNot}
     * says the test is negated.
     */
    record Binding(boolean isMutable, Name name, TypeExpr isType, boolean isNot, Span span) implements Pattern {}

    /** {@code is T}, binding nothing; {@code is not T} is a {@link Not} of one. */
    record Is(TypeExpr type, Span span) implements Pattern {}

    /** {@code not p}: matches when {@code p} does not; binds nothing (§11). */
    record Not(Pattern pattern, Span span) implements Pattern {}

    /** {@code < v}, {@code <= v}, {@code > v}, {@code >= v} (§11); {@code op} is one of LT, LE, GT, GE. */
    record Relational(Expr.BinaryOp op, Expr.Literal value, Span span) implements Pattern {}

    /** A literal; a negative sign is folded into the value. */
    record Literal(Expr.Literal value, Span span) implements Pattern {}

    /** {@code a..b} or {@code a..=b}. */
    record Range(Expr.Literal low, Expr.Literal high, boolean isInclusive, Span span) implements Pattern {}

    record Unit(Span span) implements Pattern {}

    /** Two or more elements, or one for {@code (p,)}. */
    record Tuple(List<Pattern> elements, Span span) implements Pattern {}

    /** {@code p1 | p2 | ...}. */
    record Or(List<Pattern> alternatives, Span span) implements Pattern {}

    /** {@code [p1, ..rest, p2]}; rest elements are {@link Rest}. */
    record ListPattern(List<Pattern> elements, Span span) implements Pattern {}

    /** {@code ..} or {@code ..name}, only inside a {@link ListPattern}; {@code name} may be null. */
    record Rest(Name name, Span span) implements Pattern {}

    /** {@code Type { f1, f2: p, .. }}; {@code hasRest} for the trailing {@code ..}. */
    record Struct(Expr.Path type, List<FieldPattern> fields, boolean hasRest, Span span) implements Pattern {}

    /** {@code f: p}; the shorthand {@code [mut] f} is a {@link Binding} named {@code f}. */
    record FieldPattern(Name field, Pattern pattern, Span span) implements Node {}

    /** {@code Enum::Member} or another path of two or more segments. */
    record Path(Expr.Path path, Span span) implements Pattern {}
}
