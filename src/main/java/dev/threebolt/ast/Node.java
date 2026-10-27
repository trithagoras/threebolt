package dev.threebolt.ast;

import dev.threebolt.diag.Span;

/**
 * An AST node: a record with a {@link Span}. The AST mirrors the source without semantics:
 * parentheses and other purely syntactic choices are dropped, everything else is kept.
 * Optional parts are {@code null} when absent; lists are never null.
 */
public interface Node {
    Span span();
}
