package dev.threebolt.interp;

import java.util.List;

/** A runtime panic (§13.1): not catchable by programs; aborts with exit code 101. */
public final class Panic extends RuntimeException {
    /** Call stack at the panic, innermost first; recorded by the interpreter as it unwinds. */
    private List<String> trace;

    public Panic(String message) {
        super(message, null, false, false);
    }

    public List<String> trace() {
        return trace;
    }

    void setTrace(List<String> trace) {
        this.trace = trace;
    }
}
