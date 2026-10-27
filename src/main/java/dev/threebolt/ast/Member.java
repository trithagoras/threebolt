package dev.threebolt.ast;

import dev.threebolt.diag.Span;
import java.util.List;

/** A declaration in a struct, class, trait or impl body (§4.5–§4.10). */
public sealed interface Member extends Node permits Member.Field, Item.Fn, Item.Static {
    List<Attribute> attributes();

    Visibility visibility();

    Name name();

    /** {@code let name: T [= value];}; {@code value} is null without a default. */
    record Field(List<Attribute> attributes, Visibility visibility, Name name, TypeExpr type, Expr value,
                 Span span) implements Member {}
}
