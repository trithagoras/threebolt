package dev.threebolt.lsp;

import dev.threebolt.diag.Source;
import dev.threebolt.diag.Span;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/** LSP positions count UTF-16 code units per line; {@link Source} offsets count code points. */
final class Positions {
    private Positions() {}

    static Position position(Source source, int offset) {
        int line = source.line(offset);
        String text = source.lineText(line);
        int column = Math.min(source.column(offset) - 1, text.codePointCount(0, text.length()));
        return new Position(line - 1, text.offsetByCodePoints(0, column));
    }

    static Range range(Span span) {
        return new Range(position(span.source(), span.start()), position(span.source(), span.end()));
    }

    /** The code-point offset of an LSP position (clamped to the text). */
    static int offset(Source source, Position p) {
        int line = Math.max(1, p.getLine() + 1);
        String text = line <= source.line(source.length()) ? source.lineText(line) : "";
        int column = text.codePointCount(0, Math.clamp(p.getCharacter(), 0, text.length()));
        return source.offset(line, column + 1);
    }
}
