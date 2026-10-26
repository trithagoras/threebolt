package dev.threebolt.parse;

import static dev.threebolt.parser.ThreeboltLexer.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.antlr.v4.runtime.Token;

/**
 * Turns the lexer's error tokens into messages. The lexer itself never fails; it emits
 * INVALID_* / UNTERMINATED_* / UNEXPECTED_CHAR tokens and this class says what was wrong.
 */
public final class LexErrors {
    private LexErrors() {}

    private static final Pattern UNDERSCORELESS_SUFFIX =
            Pattern.compile("[0-9][0-9_]*(\\.[0-9][0-9_]*)?([iu](8|16|32|64|size)|f(32|64))");

    public static boolean isError(Token t) {
        return switch (t.getType()) {
            case INVALID_NUMBER, INVALID_CHAR_LITERAL, INVALID_STRING, INVALID_FSTRING,
                 UNTERMINATED_FSTRING, UNTERMINATED_COMMENT, UNEXPECTED_CHAR -> true;
            default -> false;
        };
    }

    public static List<LexError> find(List<? extends Token> tokens) {
        var errors = new ArrayList<LexError>();
        for (Token t : tokens) {
            if (isError(t)) errors.add(describe(t));
        }
        return errors;
    }

    /** The error for an error token (see {@link #isError}). */
    public static LexError describe(Token t) {
        String text = t.getText();
        return switch (t.getType()) {
            case INVALID_NUMBER -> at(t, 0, UNDERSCORELESS_SUFFIX.matcher(text).matches()
                    ? "numeric literal suffixes start with '_' (e.g. 10_u64): '" + text + "'"
                    : "invalid numeric literal '" + text + "'");
            case INVALID_CHAR_LITERAL -> quoted(t, "char literal", '\'');
            case INVALID_STRING -> quoted(t, "string literal", '"');
            case INVALID_FSTRING -> text.equals("}")
                    ? at(t, 0, "unmatched '}' in f-string (write '}}' for a literal brace)")
                    : at(t, 0, "invalid escape '" + TokenDump.escape(text) + "'");
            case UNTERMINATED_FSTRING -> at(t, 0, "unterminated f-string");
            case UNTERMINATED_COMMENT -> at(t, 0, "unterminated block comment");
            case UNEXPECTED_CHAR -> at(t, 0, "unexpected character '" + TokenDump.escape(text) + "'");
            default -> throw new IllegalArgumentException("not an error token: " + t);
        };
    }

    /** Diagnoses a lenient char/string match: first bad escape, else unterminated, else (chars) wrong length. */
    private static LexError quoted(Token t, String what, char quote) {
        String text = t.getText();
        boolean triple = text.startsWith("\"\"\"");
        String delim = triple ? "\"\"\"" : String.valueOf(quote);
        int i = delim.length();
        int chars = 0;
        while (i < text.length()) {
            if (text.startsWith(delim, i) && i + delim.length() == text.length()) break;
            if (text.charAt(i) == '\\') {
                int len = escapeLength(text, i);
                if (len < 0) {
                    int end = Math.min(text.length(), i + 2);
                    return at(t, i, "invalid escape '" + TokenDump.escape(text.substring(i, end)) + "'");
                }
                i += len;
            } else {
                i += Character.charCount(text.codePointAt(i));
            }
            chars++;
        }
        if (i >= text.length()) {
            return at(t, 0, "unterminated " + (triple ? "multi-line " : "") + what);
        }
        if (chars == 0) return at(t, 0, "empty char literal");
        return at(t, 0, "char literal must contain exactly one character");
    }

    /** Length of the valid escape at {@code text[i] == '\\'}, or -1. */
    static int escapeLength(String text, int i) {
        if (i + 1 >= text.length()) return -1;
        char c = text.charAt(i + 1);
        if ("nrt0\\\"'".indexOf(c) >= 0) return 2;
        if (c != 'u' || !text.startsWith("{", i + 2)) return -1;
        int j = i + 3;
        while (j < text.length() && j - (i + 3) < 6 && Character.digit(text.charAt(j), 16) >= 0) j++;
        if (j == i + 3 || j >= text.length() || text.charAt(j) != '}') return -1;
        return j + 1 - i;
    }

    /** Position of {@code text[offset]} within token {@code t}, accounting for line breaks in the token. */
    private static LexError at(Token t, int offset, String message) {
        int line = t.getLine();
        int col = t.getCharPositionInLine() + 1;
        String text = t.getText();
        for (int k = 0; k < offset; k += Character.charCount(text.codePointAt(k))) {
            char c = text.charAt(k);
            if (c == '\n' || (c == '\r' && !text.startsWith("\n", k + 1))) {
                line++;
                col = 1;
            } else if (c != '\r') {
                col++;
            }
        }
        return new LexError(line, col, message);
    }
}
