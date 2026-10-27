package dev.threebolt.ast;

import dev.threebolt.diag.Span;
import java.util.List;

/** A type expression (§3.2). Grouping parentheses are dropped; unions are not flattened. */
public sealed interface TypeExpr extends Node {
    /** {@code a::B<T, U>}; also {@code Self}. Type arguments only follow the last segment. */
    record Named(List<Name> path, List<TypeExpr> typeArgs, Span span) implements TypeExpr {}

    /** {@code "left"}, {@code 42}, {@code -1}, {@code true}, {@code null}: a negative sign is folded into the value. */
    record Literal(Expr.Literal value, Span span) implements TypeExpr {}

    record Unit(Span span) implements TypeExpr {}

    /** Two or more elements, or one for {@code (T,)}. */
    record Tuple(List<TypeExpr> elements, Span span) implements TypeExpr {}

    /** {@code [T]}. */
    record ListType(TypeExpr element, Span span) implements TypeExpr {}

    /** {@code [T; N]}. */
    record ArrayType(TypeExpr element, Expr size, Span span) implements TypeExpr {}

    /** {@code [extern] fn(A, B) -> R}; {@code returnType} is null when omitted. */
    record FnType(boolean isExtern, List<TypeExpr> params, TypeExpr returnType, Span span) implements TypeExpr {}

    /** {@code !}. */
    record Never(Span span) implements TypeExpr {}

    /** {@code A | B | C}, as written. */
    record Union(List<TypeExpr> members, Span span) implements TypeExpr {}
}
