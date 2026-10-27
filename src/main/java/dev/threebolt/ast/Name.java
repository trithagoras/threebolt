package dev.threebolt.ast;

import dev.threebolt.diag.Span;

/** An identifier (or keyword used as a name, §1) where it occurs. */
public record Name(String text, Span span) implements Node {}
