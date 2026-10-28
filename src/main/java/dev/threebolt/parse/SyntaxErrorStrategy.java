package dev.threebolt.parse;

import static dev.threebolt.parser.ThreeboltLexer.*;

import dev.threebolt.parser.ThreeboltParser;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.FailedPredicateException;
import org.antlr.v4.runtime.InputMismatchException;
import org.antlr.v4.runtime.NoViableAltException;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.IntervalSet;

/**
 * ANTLR's recovery with shorter messages: "expected ';', found 'x'" when only a few tokens could
 * follow, "unexpected 'x'" otherwise. Errors at lexer error tokens are dropped — the lexer
 * already reported them (see {@link LexErrors}).
 */
final class SyntaxErrorStrategy extends DefaultErrorStrategy {
    /** Beyond this many expected tokens, listing them is noise. */
    private static final int MAX_LISTED = 4;

    @Override
    protected void reportNoViableAlternative(Parser recognizer, NoViableAltException e) {
        Token t = e.getOffendingToken();
        String message = "unexpected " + describe(t);
        if (startsInitializer(recognizer, t)) {
            message += " (an initializer in an if/while/for/match head must be parenthesized)";
        }
        report(recognizer, t, message + comprehensionHint(recognizer, t));
    }

    /** A hint for an `else` after a comprehension's `for`: a conditional iterable or filter (§17.19). */
    private static String comprehensionHint(Parser recognizer, Token t) {
        if (t.getType() != ELSE) return "";
        for (var ctx = recognizer.getContext(); ctx != null; ctx = ctx.getParent()) {
            if (ctx instanceof ThreeboltParser.CollectionLiteralContext c) {
                var in = recognizer.getInputStream();
                for (int i = c.getStart().getTokenIndex(); i < t.getTokenIndex(); i++) {
                    if (in.get(i).getType() == FOR) {
                        return " (a conditional in a comprehension's iterable or filter must be parenthesized)";
                    }
                }
                return "";
            }
        }
        return "";
    }

    /** {@code t} begins `{...}` or `Name {...}` where §6.3 forbids initializers. */
    private static boolean startsInitializer(Parser recognizer, Token t) {
        if (!(recognizer instanceof ThreeboltParser p) || !p.inNoStructHead()) return false;
        int next = recognizer.getInputStream().get(t.getTokenIndex() + 1).getType();
        return t.getType() == LBRACE
                || (t.getType() == IDENTIFIER && next == LBRACE);
    }

    @Override
    protected void reportInputMismatch(Parser recognizer, InputMismatchException e) {
        report(recognizer, e.getOffendingToken(), mismatch(e.getOffendingToken(), e.getExpectedTokens())
                + comprehensionHint(recognizer, e.getOffendingToken()));
    }

    @Override
    protected void reportFailedPredicate(Parser recognizer, FailedPredicateException e) {
        // Predicates with a <fail={...}> message carry it; the rest only guard prediction.
        String message = e.getMessage() != null && !e.getMessage().startsWith("failed predicate")
                ? e.getMessage()
                : "unexpected " + describe(e.getOffendingToken());
        report(recognizer, e.getOffendingToken(), message);
    }

    @Override
    protected void reportUnwantedToken(Parser recognizer) {
        if (inErrorRecoveryMode(recognizer)) return;
        beginErrorCondition(recognizer);
        Token t = recognizer.getCurrentToken();
        report(recognizer, t, mismatch(t, getExpectedTokens(recognizer)) + comprehensionHint(recognizer, t));
    }

    @Override
    protected void reportMissingToken(Parser recognizer) {
        if (inErrorRecoveryMode(recognizer)) return;
        beginErrorCondition(recognizer);
        Token t = recognizer.getCurrentToken();
        report(recognizer, t, mismatch(t, getExpectedTokens(recognizer)));
    }

    private static String mismatch(Token found, IntervalSet expected) {
        List<String> names = new ArrayList<>();
        for (int type : expected.toList()) {
            if (type == Token.EPSILON) continue;
            names.add(tokenName(type));
        }
        if (names.isEmpty() || names.size() > MAX_LISTED) return "unexpected " + describe(found);
        return "expected " + join(names) + ", found " + describe(found);
    }

    private static String join(List<String> names) {
        if (names.size() == 1) return names.getFirst();
        return String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.getLast();
    }

    private static void report(Parser recognizer, Token offending, String message) {
        if (LexErrors.isError(offending)) return;
        recognizer.notifyErrorListeners(offending, message, null);
    }

    static String describe(Token t) {
        if (t.getType() == Token.EOF) return "end of file";
        return switch (t.getType()) {
            case FSTRING_START -> "f-string";
            case STRING_LITERAL, TRIPLE_STRING_LITERAL, CHAR_LITERAL -> TokenDump.escape(t.getText());
            default -> "'" + TokenDump.escape(t.getText()) + "'";
        };
    }

    static String tokenName(int type) {
        return switch (type) {
            case Token.EOF -> "end of file";
            case IDENTIFIER -> "identifier";
            case INT_LITERAL -> "integer";
            case FLOAT_LITERAL -> "float";
            case STRING_LITERAL, TRIPLE_STRING_LITERAL -> "string";
            case CHAR_LITERAL -> "char";
            case FSTRING_TEXT, INTERP_START, INTERP_END, FSTRING_END -> "f-string";
            default -> {
                String literal = VOCABULARY.getLiteralName(type);
                yield literal != null ? literal : VOCABULARY.getSymbolicName(type);
            }
        };
    }
}
