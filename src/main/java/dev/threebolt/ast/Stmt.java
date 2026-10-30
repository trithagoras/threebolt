package dev.threebolt.ast;

import dev.threebolt.diag.Span;
import java.util.List;

/** A statement (§6). */
public sealed interface Stmt extends Node {
    /** {@code let pattern [: T] = value;}; {@code type} is null when omitted. */
    record Let(Pattern pattern, TypeExpr type, Expr value, Span span) implements Stmt {}

    record Block(List<Stmt> stmts, Span span) implements Stmt, Body {}

    /**
     * {@code if [let [mut] x =] condition { ... } [else ...]}; {@code binder} is null for a plain
     * {@code if}, {@code otherwise} is null, a {@link Block}, or an {@link If} for {@code else if}.
     */
    record If(Binder binder, Expr condition, Block then, Stmt otherwise, Span span) implements Stmt {}

    /** The {@code let [mut] x =} of {@code if let} / {@code while let}. */
    record Binder(boolean isMutable, Name name, Span span) implements Node {}

    record While(Binder binder, Expr condition, Block body, Span span) implements Stmt {}

    record Loop(Block body, Span span) implements Stmt {}

    /** {@code for p in e}; with {@code propagate}, {@code for p? in e} (§6.4). */
    record For(Pattern pattern, boolean propagate, Expr iterable, Block body, Span span) implements Stmt {}

    /** An expression statement; a {@code match} statement is one of these. */
    record ExprStmt(Expr expr, Span span) implements Stmt {}

    record Break(Span span) implements Stmt {}

    record Continue(Span span) implements Stmt {}

    /** {@code value} is null for a bare {@code return;}. */
    record Return(Expr value, Span span) implements Stmt {}

    /** {@code defer expr;} or {@code defer { ... }}: {@code body} is an {@link Expr} or a {@link Block}. */
    record Defer(Body body, Span span) implements Stmt {}

    record Unsafe(Block body, Span span) implements Stmt {}

    /** {@code target op= value;}; {@code target} is null for the discard {@code _ = value;}. */
    record Assign(Expr target, AssignOp op, Expr value, Span span) implements Stmt, Body {}

    enum AssignOp {
        ASSIGN("="), ADD("+="), SUB("-="), MUL("*="), DIV("/="), REM("%="),
        AND("&="), OR("|="), XOR("^="), SHL("<<="), SHR(">>=");

        private final String symbol;

        AssignOp(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }
}
