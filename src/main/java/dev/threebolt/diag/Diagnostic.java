package dev.threebolt.diag;

import java.util.List;

/** A compile-time error or warning at a span, optionally with notes pointing elsewhere. */
public record Diagnostic(Code code, Span span, String message, List<Note> notes) {
    public record Note(Span span, String message) {}

    public Diagnostic(Code code, Span span, String message) {
        this(code, span, message, List.of());
    }

    public boolean isWarning() {
        return code.isWarning();
    }

    /** {@code file:line:col: error[E0100]: message} (or {@code warning[W0001]}), plus one line per note. */
    public String header() {
        var out = new StringBuilder();
        out.append(span).append(isWarning() ? ": warning[" : ": error[").append(code.id()).append("]: ").append(message);
        for (Note n : notes) out.append('\n').append(n.span()).append(": note: ").append(n.message());
        return out.toString();
    }

    /** {@link #header()} plus the offending source line with a caret under the span. */
    public String render() {
        var out = new StringBuilder(header().lines().findFirst().orElseThrow()).append('\n');
        excerpt(span, out);
        for (Note n : notes) {
            out.append(n.span()).append(": note: ").append(n.message()).append('\n');
            excerpt(n.span(), out);
        }
        return out.toString();
    }

    private static void excerpt(Span span, StringBuilder out) {
        int line = span.line();
        String text = span.source().lineText(line);
        String gutter = " ".repeat(String.valueOf(line).length());
        int col = span.column();
        int width = Math.max(1, Math.min(span.end() - span.start(), text.codePointCount(0, text.length()) - col + 1));
        out.append(gutter).append(" |\n");
        out.append(line).append(" | ").append(text).append('\n');
        out.append(gutter).append(" | ").append(" ".repeat(col - 1)).append("^".repeat(width)).append('\n');
    }
}
