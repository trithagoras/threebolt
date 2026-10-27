package dev.threebolt.ast;

import dev.threebolt.diag.Span;
import java.util.List;

/** {@code T: Bound + Bound2} (§10). */
public record TypeParam(Name name, List<TypeExpr.Named> bounds, Span span) implements Node {}
