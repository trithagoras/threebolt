package dev.threebolt.diag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Collects the diagnostics of one compilation; every stage keeps going after an error. Warnings
 * are kept with the errors but never count as errors.
 */
public final class Diagnostics {
    private final List<Diagnostic> all = new ArrayList<>();
    private final java.util.Set<String> warned = new java.util.HashSet<>();
    private int muted;
    private int dropped;
    private int errors;

    public void add(Diagnostic d) {
        if (d.isWarning()) {
            // Speculative checking says nothing, and a body checked twice warns once.
            if (muted == 0 && warned.add(d.header())) all.add(d);
            return;
        }
        if (muted > 0) {
            dropped++;
        } else {
            all.add(d);
            errors++;
        }
    }

    /**
     * Runs {@code work} with diagnostics discarded (speculative checking, e.g. typing overload
     * arguments); {@link #dropped} tells whether it reported anything.
     */
    public <T> T muted(Supplier<T> work) {
        muted++;
        try {
            return work.get();
        } finally {
            muted--;
        }
    }

    /** Inside {@link #muted}: what is reported now is discarded. */
    public boolean isMuted() {
        return muted > 0;
    }

    /** How many errors were dropped while muted, ever; compare before and after. */
    public int dropped() {
        return dropped;
    }

    /** How many errors (not warnings) were reported. */
    public int count() {
        return errors;
    }

    public void error(Code code, Span span, String message) {
        add(new Diagnostic(code, span, message));
    }

    public void warning(Code code, Span span, String message) {
        add(new Diagnostic(code, span, message));
    }

    public boolean hasErrors() {
        return errors > 0;
    }

    /** Sorted by file name, then position; stable for equal positions. */
    public List<Diagnostic> sorted() {
        return all.stream()
                .sorted(Comparator.comparing((Diagnostic d) -> d.span().source().name())
                        .thenComparingInt(d -> d.span().start()))
                .toList();
    }
}
