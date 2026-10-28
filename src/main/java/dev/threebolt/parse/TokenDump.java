package dev.threebolt.parse;

import dev.threebolt.parser.ThreeboltLexer;
import java.util.List;
import org.antlr.v4.runtime.Token;

/** One-line-per-token rendering used by `bolt debug tokens` and the lexer golden tests. */
public final class TokenDump {
    private TokenDump() {}

    /** {@code line:col TYPE 'text'} per token (1-based column); EOF is omitted. */
    public static String render(List<? extends Token> tokens) {
        var out = new StringBuilder();
        for (Token t : tokens) {
            if (t.getType() == Token.EOF) continue;
            out.append(t.getLine()).append(':').append(t.getCharPositionInLine() + 1).append(' ')
                    .append(ThreeboltLexer.VOCABULARY.getSymbolicName(t.getType()))
                    .append(" '").append(escape(t.getText())).append("'\n");
        }
        return out.toString();
    }

    /** Escapes control characters so every token stays on one line; everything else is verbatim. */
    static String escape(String text) {
        var out = new StringBuilder();
        text.codePoints().forEach(cp -> {
            switch (cp) {
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (Character.isISOControl(cp) || cp == 0xFEFF) out.append(String.format("\\u{%X}", cp));
                    else out.appendCodePoint(cp);
                }
            }
        });
        return out.toString();
    }
}
