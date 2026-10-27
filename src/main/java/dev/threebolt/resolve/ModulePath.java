package dev.threebolt.resolve;

import java.util.ArrayList;
import java.util.List;

/** A module's name: its file path relative to the source root, `/` → `::`, extension dropped (§2.1). */
public record ModulePath(List<String> segments) {
    public ModulePath {
        segments = List.copyOf(segments);
        if (segments.isEmpty()) throw new IllegalArgumentException("empty module path");
    }

    public static ModulePath of(String... segments) {
        return new ModulePath(List.of(segments));
    }

    public ModulePath child(String name) {
        var s = new ArrayList<>(segments);
        s.add(name);
        return new ModulePath(s);
    }

    /** The enclosing path, or null for a single segment. */
    public ModulePath parent() {
        return segments.size() == 1 ? null : new ModulePath(segments.subList(0, segments.size() - 1));
    }

    public String last() {
        return segments.getLast();
    }

    public boolean isStd() {
        return segments.getFirst().equals("std");
    }

    @Override
    public String toString() {
        return String.join("::", segments);
    }
}
