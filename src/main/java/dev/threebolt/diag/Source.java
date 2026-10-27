package dev.threebolt.diag;

import java.util.Arrays;

/**
 * A source file's text. Offsets are code-point indices, as in ANTLR's char streams, so they line
 * up with token start/stop indices; columns count code points too (1-based).
 */
public final class Source {
    private final String name;
    private final String text;
    private final int[] codePoints;
    /** Offset of the first code point of each line. */
    private final int[] lineStarts;

    /** {@code name} is what diagnostics print, e.g. {@code game/entity.3b}. */
    public Source(String name, String text) {
        this.name = name;
        this.text = text;
        this.codePoints = text.codePoints().toArray();
        var starts = new int[16];
        int lines = 1;
        for (int i = 0; i < codePoints.length; i++) {
            int c = codePoints[i];
            boolean lineBreak = c == '\n' || (c == '\r' && (i + 1 == codePoints.length || codePoints[i + 1] != '\n'));
            if (lineBreak) {
                if (lines == starts.length) starts = Arrays.copyOf(starts, lines * 2);
                starts[lines++] = i + 1;
            }
        }
        this.lineStarts = Arrays.copyOf(starts, lines);
    }

    public String name() {
        return name;
    }

    public String text() {
        return text;
    }

    /** Length in code points. */
    public int length() {
        return codePoints.length;
    }

    /** 1-based line of {@code offset}. */
    public int line(int offset) {
        int i = Arrays.binarySearch(lineStarts, offset);
        return i >= 0 ? i + 1 : -i - 1;
    }

    /** 1-based column of {@code offset}, in code points. */
    public int column(int offset) {
        return offset - lineStarts[line(offset) - 1] + 1;
    }

    /** Offset of a 1-based line and column (clamped to the text). */
    public int offset(int line, int column) {
        int l = Math.clamp(line, 1, lineStarts.length);
        return Math.clamp(lineStarts[l - 1] + column - 1L, 0, codePoints.length);
    }

    /** The text of a 1-based line, without its line break. */
    public String lineText(int line) {
        int start = lineStarts[line - 1];
        int end = line < lineStarts.length ? lineStarts[line] : codePoints.length;
        while (end > start && (codePoints[end - 1] == '\n' || codePoints[end - 1] == '\r')) end--;
        return new String(codePoints, start, end - start);
    }

    @Override
    public String toString() {
        return name;
    }
}
