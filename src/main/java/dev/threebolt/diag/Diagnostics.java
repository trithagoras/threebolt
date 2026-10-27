package dev.threebolt.diag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Collects the diagnostics of one compilation; every stage keeps going after an error. */
public final class Diagnostics {
    private final List<Diagnostic> all = new ArrayList<>();

    public void add(Diagnostic d) {
        all.add(d);
    }

    public void error(Code code, Span span, String message) {
        add(new Diagnostic(code, span, message));
    }

    public boolean hasErrors() {
        return !all.isEmpty();
    }

    /** Sorted by file name, then position; stable for equal positions. */
    public List<Diagnostic> sorted() {
        return all.stream()
                .sorted(Comparator.comparing((Diagnostic d) -> d.span().source().name())
                        .thenComparingInt(d -> d.span().start()))
                .toList();
    }
}
