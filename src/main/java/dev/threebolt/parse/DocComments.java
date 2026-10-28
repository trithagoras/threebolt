package dev.threebolt.parse;

import dev.threebolt.diag.Source;
import dev.threebolt.parser.ThreeboltLexer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;

/**
 * The doc comments of a source (§1): a run of `///` lines documents the declaration that
 * follows it, i.e. the one whose first token (an attribute, `pub`, or its keyword) comes next.
 * Ordinary comments and blank lines between the run and the declaration do not matter.
 */
public final class DocComments {
    /** Doc text by the code-point offset of the token it documents. */
    private final Map<Integer, String> byNextToken;

    private DocComments(Map<Integer, String> byNextToken) {
        this.byNextToken = byNextToken;
    }

    public static DocComments of(Source source) {
        var lexer = new ThreeboltLexer(CharStreams.fromString(source.text()));
        lexer.removeErrorListeners();
        var out = new HashMap<Integer, String>();
        var lines = new ArrayList<String>();
        for (Token t : lexer.getAllTokens()) {
            if (t.getType() == ThreeboltLexer.DOC_COMMENT) {
                lines.add(text(t.getText()));
            } else if (t.getChannel() == Token.DEFAULT_CHANNEL && !lines.isEmpty()) {
                out.put(t.getStartIndex(), String.join("\n", lines).strip());
                lines.clear();
            }
        }
        return new DocComments(out);
    }

    /** The doc comment of the declaration starting at {@code offset}, or null. */
    public String at(int offset) {
        var doc = byNextToken.get(offset);
        return doc == null || doc.isEmpty() ? null : doc;
    }

    /** {@code /// text} → {@code text}: the slashes and one space after them go. */
    private static String text(String comment) {
        var s = comment.substring(3);
        return s.startsWith(" ") ? s.substring(1) : s;
    }
}
