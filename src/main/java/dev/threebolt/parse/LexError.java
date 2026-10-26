package dev.threebolt.parse;

/** A lexical error at a 1-based line and column. */
public record LexError(int line, int column, String message) {
    @Override
    public String toString() {
        return line + ":" + column + ": " + message;
    }
}
