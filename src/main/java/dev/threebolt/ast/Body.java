package dev.threebolt.ast;

/**
 * What follows `=>` (§7.1): an expression, an assignment, or (lambdas, match arms) a block. Also
 * a function's block body, and `defer`'s operand.
 */
public sealed interface Body extends Node permits Expr, Stmt.Assign, Stmt.Block {}
