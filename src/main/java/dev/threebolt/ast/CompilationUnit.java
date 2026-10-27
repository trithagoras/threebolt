package dev.threebolt.ast;

import dev.threebolt.diag.Span;
import java.util.List;

/** One parsed `.3b` file. */
public record CompilationUnit(List<Item> items, Span span) implements Node {}
