package dev.threebolt.ast;

import dev.threebolt.diag.Span;
import java.util.List;

/** {@code @name} or {@code @name(args)} (§1). */
public record Attribute(Name name, List<Expr.Literal> args, Span span) implements Node {}
