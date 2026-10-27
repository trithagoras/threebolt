package dev.threebolt.diag;

/** The code points {@code [start, end)} of a source. */
public record Span(Source source, int start, int end) {
    public int line() {
        return source.line(start);
    }

    public int column() {
        return source.column(start);
    }

    /** The smallest span covering both. */
    public Span to(Span other) {
        return new Span(source, Math.min(start, other.start), Math.max(end, other.end));
    }

    @Override
    public String toString() {
        return source.name() + ":" + line() + ":" + column();
    }
}
