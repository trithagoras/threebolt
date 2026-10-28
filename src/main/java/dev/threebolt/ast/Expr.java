package dev.threebolt.ast;

import dev.threebolt.diag.Span;
import java.math.BigInteger;
import java.util.List;

/**
 * An expression (§7). Parentheses are dropped; method calls are a {@link Call} of a
 * {@link Member}; field shorthand {@code { name }} is a {@link FieldInit} whose value is the path
 * {@code name}.
 */
public sealed interface Expr extends Body {
    // ---- Literals (§1); escapes are decoded ----

    sealed interface Literal extends Expr {}

    /** {@code suffix} is the type name of a suffix ({@code "u64"} for {@code 10_u64}) or null. */
    record IntLit(BigInteger value, String suffix, Span span) implements Literal {}

    /** {@code digits} without {@code _} separators, e.g. {@code "2.5e-3"}; {@code suffix} as for {@link IntLit}. */
    record FloatLit(String digits, String suffix, Span span) implements Literal {}

    record StrLit(String value, Span span) implements Literal {}

    /** A Unicode scalar value. */
    record CharLit(int codePoint, Span span) implements Literal {}

    record BoolLit(boolean value, Span span) implements Literal {}

    record NullLit(Span span) implements Literal {}

    /** {@code ()}. */
    record Unit(Span span) implements Expr {}

    /** {@code f"text {expr} text"}; adjacent text is merged and {@code {{ }}} decoded. */
    record FString(List<FStringPart> parts, Span span) implements Expr {}

    sealed interface FStringPart extends Node {}

    record Text(String text, Span span) implements FStringPart {}

    record Interpolation(Expr expr, Span span) implements FStringPart {}

    // ---- Names ----

    /** {@code a}, {@code a::b}, {@code f<T>}, {@code Type<T>::f}, {@code Self::new}. */
    record Path(List<PathSegment> segments, Span span) implements Expr {}

    record PathSegment(Name name, List<TypeExpr> typeArgs, Span span) implements Node {}

    record Self(Span span) implements Expr {}

    record Super(Span span) implements Expr {}

    // ---- Postfix (§7.5–§7.6, §7.10) ----

    /** {@code receiver.name[<T>]}; tuple fields have names {@code "0"}, {@code "1"}, .... */
    record Member(Expr receiver, Name name, List<TypeExpr> typeArgs, Span span) implements Expr {}

    record Call(Expr callee, List<Arg> args, Span span) implements Expr {}

    /** {@code name} is null for positional arguments. */
    record Arg(Name name, Expr value, Span span) implements Node {}

    record Index(Expr target, Expr index, Span span) implements Expr {}

    /**
     * An open-ended slice {@code xs[a..]}, {@code xs[..b]}, {@code xs[..=b]}, {@code xs[..]} (§7.6);
     * absent bounds are null. Closed ones ({@code xs[a..b]}) are an {@link Index} of a range.
     */
    record Slice(Expr target, Expr low, Expr high, boolean isInclusive, Span span) implements Expr {}

    /** {@code operand?}. */
    record Propagate(Expr operand, Span span) implements Expr {}

    // ---- Operators (§7.2–§7.4, §7.9) ----

    record Unary(UnaryOp op, Expr operand, Span span) implements Expr {}

    /** {@code operand as T}, or {@code as?} when {@code isChecked}. */
    record Cast(Expr operand, TypeExpr type, boolean isChecked, Span span) implements Expr {}

    record Binary(BinaryOp op, Expr left, Expr right, Span span) implements Expr {}

    record Is(Expr operand, TypeExpr type, Span span) implements Expr {}

    /** {@code operand is pattern} (§7.3): a refutable pattern test that binds where it holds. */
    record IsPattern(Expr operand, Pattern pattern, Span span) implements Expr {}

    /** {@code then if condition else otherwise}. */
    record Conditional(Expr then, Expr condition, Expr otherwise, Span span) implements Expr {}

    // ---- Compound (§7.7–§7.12) ----

    record Lambda(List<LambdaParam> params, Body body, Span span) implements Expr {}

    /** A plain parameter is a {@link Pattern.Binding}; {@code type} is null when not annotated. */
    record LambdaParam(Pattern pattern, TypeExpr type, Span span) implements Node {}

    /** {@code unsafe => operand}. */
    record Unsafe(Expr operand, Span span) implements Expr {}

    record Match(Expr scrutinee, List<MatchArm> arms, Span span) implements Expr {}

    /** {@code guard} is null without {@code if}. */
    record MatchArm(Pattern pattern, Expr guard, Body body, Span span) implements Node {}

    /** {@code Type { ... }}, or the target-typed {@code { ... }} when {@code type} is null (§4.7). */
    record Init(Path type, List<InitField> fields, Span span) implements Expr {}

    sealed interface InitField extends Node {}

    record FieldInit(Name name, Expr value, Span span) implements InitField {}

    record Spread(Expr value, Span span) implements InitField {}

    /** {@code [a, b]}; {@code []} is an empty list. */
    record ListLit(List<Expr> elements, Span span) implements Expr {}

    /** {@code [value; count]}. */
    record RepeatLit(Expr value, Expr count, Span span) implements Expr {}

    /** {@code [k: v]}; {@code [:]} is an empty map. */
    record MapLit(List<MapEntry> entries, Span span) implements Expr {}

    record MapEntry(Expr key, Expr value, Span span) implements Node {}

    /** {@code [element for p in it if c, ...]}. */
    record ListComp(Expr element, List<CompClause> clauses, Span span) implements Expr {}

    /** {@code [k: v for p in it if c, ...]}. */
    record MapComp(MapEntry entry, List<CompClause> clauses, Span span) implements Expr {}

    /** {@code pattern in iterable if filter}; {@code filter} is null without {@code if}. */
    record CompClause(Pattern pattern, Expr iterable, Expr filter, Span span) implements Node {}

    /** Two or more elements, or one for {@code (a,)}. */
    record Tuple(List<Expr> elements, Span span) implements Expr {}

    record Typeof(TypeExpr type, Span span) implements Expr {}

    record Nameof(TypeExpr type, Span span) implements Expr {}

    enum UnaryOp {
        NEG("-"), NOT("!"), BIT_NOT("~");

        private final String symbol;

        UnaryOp(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    /** Binary operators with their §7.2 precedence level (higher binds tighter). */
    enum BinaryOp {
        COALESCE("??", 3), OR("||", 4), AND("&&", 5),
        EQ("==", 6), NE("!=", 6), LT("<", 6), LE("<=", 6), GT(">", 6), GE(">=", 6), IN("in", 6),
        RANGE("..", 7), RANGE_INCLUSIVE("..=", 7),
        BIT_OR("|", 8), BIT_XOR("^", 9), BIT_AND("&", 10), SHL("<<", 11), SHR(">>", 11),
        ADD("+", 12), SUB("-", 12), MUL("*", 13), DIV("/", 13), REM("%", 13);

        private final String symbol;
        private final int precedence;

        BinaryOp(String symbol, int precedence) {
            this.symbol = symbol;
            this.precedence = precedence;
        }

        public String symbol() {
            return symbol;
        }

        public int precedence() {
            return precedence;
        }
    }
}
