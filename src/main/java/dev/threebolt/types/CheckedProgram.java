package dev.threebolt.types;

import java.util.List;

/** A checked program, ready to run: its type tables, `main` (§2.4), and statics. */
public record CheckedProgram(TypeSystem ts, FnInfo main, List<StaticInfo> statics) {}
