package dev.threebolt.parse;

/** A lexical or syntax error at a 1-based line and column. */
public record ParseError(int line, int column, String message) {
    static ParseError of(LexError e) {
        return new ParseError(e.line(), e.column(), e.message());
    }

    @Override
    public String toString() {
        return line + ":" + column + ": " + message;
    }
}
