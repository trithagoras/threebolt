package dev.threebolt.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.threebolt.parser.ThreeboltLexer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LexerTest {
    private static List<? extends Token> lex(String src) {
        return new ThreeboltLexer(CharStreams.fromString(src)).getAllTokens();
    }

    /** Symbolic token names, space-separated. */
    private static String types(String src) {
        var names = new ArrayList<String>();
        for (Token t : lex(src)) names.add(ThreeboltLexer.VOCABULARY.getSymbolicName(t.getType()));
        return String.join(" ", names);
    }

    private static String single(String src) {
        var tokens = lex(src);
        assertEquals(1, tokens.size(), () -> "expected one token for " + src + ", got " + TokenDump.render(tokens));
        return ThreeboltLexer.VOCABULARY.getSymbolicName(tokens.get(0).getType());
    }

    private static List<String> errors(String src) {
        return LexErrors.find(lex(src)).stream().map(LexError::toString).toList();
    }

    @Test
    void everyKeywordIsItsOwnToken() {
        String keywords = "abstract as break class const continue defer else enum extern false fn for get if impl in"
                + " is let loop match mut nameof null override prot pub return Self self static struct super"
                + " trait true type typeof unsafe use virtual while";
        for (String kw : keywords.split(" ")) {
            String type = single(kw);
            String expected = kw.equals("Self") ? "SELF_TYPE" : kw.toUpperCase();
            assertEquals(expected, type, kw);
            assertEquals("IDENTIFIER", single(kw + "x"), kw + "x");
        }
    }

    @Test
    void underscoreAloneIsNotAnIdentifier() {
        assertEquals("UNDERSCORE", single("_"));
        assertEquals("IDENTIFIER IDENTIFIER IDENTIFIER", types("__ _x x_1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "123", "1_000", "007", "1_", "0xFF", "0xdead_BEEF", "0x_1", "0o17", "0b1010",
            "10_u64", "1_000_i8", "0xFF_u8", "0b1_usize", "5_isize", "0o7_u16"})
    void integerLiterals(String src) {
        assertEquals("INT_LITERAL", single(src));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "1e9", "1E9", "2.5e-3", "1e+5", "1_000.000_1", "1.5_f32", "2.0_f64", "1_f32",
            "1e9_f64", "0.5e1_f32"})
    void floatLiterals(String src) {
        assertEquals("FLOAT_LITERAL", single(src));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10u64", "1.0f32", "5_u7", "1.0abc", "0x", "0xG", "0o8", "0b12", "0XFF", "1e", "12abc",
            "0x1_i9", "1_f16"})
    void invalidNumbers(String src) {
        assertEquals("INVALID_NUMBER", single(src));
    }

    @Test
    void dotAfterNumberWithoutDigitsIsNotAFloat() {
        assertEquals("INT_LITERAL DOT IDENTIFIER LPAREN RPAREN", types("1.max()"));
        assertEquals("INT_LITERAL DOTDOT INT_LITERAL", types("1..2"));
        assertEquals("INT_LITERAL DOTDOT_EQ IDENTIFIER", types("0..=n"));
        assertEquals("DOT INT_LITERAL", types(".5"));
    }

    @Test
    void digitsAfterDotAreTupleIndices() {
        assertEquals("IDENTIFIER DOT INT_LITERAL DOT INT_LITERAL", types("t.0.1"));
        assertEquals("IDENTIFIER DOT INT_LITERAL DOT INT_LITERAL DOT INT_LITERAL", types("t.0.1.2"));
        assertEquals("IDENTIFIER DOT INT_LITERAL DOT INT_LITERAL", types("t . /* c */ 0.1"));
        // ...but only directly after `.`:
        assertEquals("FLOAT_LITERAL DOT IDENTIFIER", types("1.0.max"));
        assertEquals("IDENTIFIER DOTDOT FLOAT_LITERAL", types("a..0.5"));
    }

    @Test
    void greaterThanIsNeverCombined() {
        assertEquals("GT GT", types(">>"));
        assertEquals("GT ASSIGN", types(">="));
        assertEquals("GT GT ASSIGN", types(">>="));
        assertEquals("IDENTIFIER LT IDENTIFIER LT IDENTIFIER GT GT FAT_ARROW", types("List<List<i32>>=>"));
        assertEquals("SHL SHL_EQ LE LT", types("<< <<= <= <"));
    }

    @Test
    void operatorsAndPunctuation() {
        assertEquals("PLUS MINUS STAR SLASH PERCENT AMP PIPE CARET TILDE BANG AND_AND OR_OR EQ_EQ NOT_EQ ASSIGN",
                types("+ - * / % & | ^ ~ ! && || == != ="));
        assertEquals("PLUS_EQ MINUS_EQ STAR_EQ SLASH_EQ PERCENT_EQ AMP_EQ PIPE_EQ CARET_EQ",
                types("+= -= *= /= %= &= |= ^="));
        assertEquals("QQ QUESTION DOTDOT DOTDOT_EQ ELLIPSIS FAT_ARROW ARROW COLON_COLON COLON DOT COMMA SEMI AT",
                types("?? ? .. ..= ... => -> :: : . , ; @"));
        assertEquals("LPAREN RPAREN LBRACKET RBRACKET LBRACE RBRACE", types("()[]{}"));
    }

    @Test
    void asQuestionIsOneTokenOnlyWithoutWhitespace() {
        assertEquals("IDENTIFIER AS_Q IDENTIFIER", types("x as? T"));
        assertEquals("IDENTIFIER AS IDENTIFIER QUESTION", types("x as T?"));
        assertEquals("IDENTIFIER AS QUESTION", types("x as ?"));
        assertEquals("IDENTIFIER QUESTION", types("has?"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"'a'", "'\\n'", "'\\''", "'\"'", "'\\0'", "'\\u{1F600}'", "'\\u{0}'", "'😀'", "'é'"})
    void charLiterals(String src) {
        assertEquals("CHAR_LITERAL", single(src));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"abc\"", "\"a\\\"b\"", "\"\\n\\r\\t\\0\\\\\\'\"", "\"\\u{10FFFF}\"", "\"'\"",
            "\"😀 {not interp}\""})
    void stringLiterals(String src) {
        assertEquals("STRING_LITERAL", single(src));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"\"\"\"\"", "\"\"\"a\nb\"\"\"", "\"\"\"say \"hi\" or \"\"x\"\" ok\"\"\"",
            "\"\"\"\\\"\"\"\"", "\"\"\"\r\n\\t\"\"\""})
    void tripleStringLiterals(String src) {
        assertEquals("TRIPLE_STRING_LITERAL", single(src));
    }

    @Test
    void tripleStringEndsAtFirstTripleQuote() {
        assertEquals("TRIPLE_STRING_LITERAL INVALID_STRING", types("\"\"\"a\"\"\"\""));
        assertEquals("STRING_LITERAL IDENTIFIER", types("\"\"x"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "''           | 1:1: empty char literal",
            "'ab'         | 1:1: char literal must contain exactly one character",
            "'a           | 1:1: unterminated char literal",
            "'\\q'        | 1:2: invalid escape '\\q'",
            "\"ab\\x\"    | 1:4: invalid escape '\\x'",
            "\"\\u{}\"    | 1:2: invalid escape '\\u'",
            "\"\\u{1234567}\" | 1:2: invalid escape '\\u'",
            "\"abc        | 1:1: unterminated string literal",
            "\"abc\\\"    | 1:1: unterminated string literal",
            "\"\"\"abc    | 1:1: unterminated multi-line string literal",
            "/* a /* b */ | 1:1: unterminated block comment",
            "#            | 1:1: unexpected character '#'",
    })
    void errorMessages(String src, String expected) {
        assertEquals(List.of(expected), errors(src));
    }

    @Test
    void stringsMayNotSpanLines() {
        assertEquals(List.of("1:1: unterminated string literal"), errors("\"abc\nx"));
        assertEquals("INVALID_STRING IDENTIFIER", types("\"abc\nx"));
        assertEquals(List.of("2:2: invalid escape '\\q'"), errors("\"\"\"a\nb\\q\"\"\""));
    }

    @Test
    void fStringWithInterpolation() {
        assertEquals("FSTRING_START FSTRING_TEXT INTERP_START IDENTIFIER INTERP_END FSTRING_TEXT FSTRING_END",
                types("f\"a {x} b\""));
        assertEquals("FSTRING_START FSTRING_END", types("f\"\""));
        assertEquals("FSTRING_START INTERP_START INTERP_END FSTRING_END", types("f\"{}\""));
        assertEquals("FSTRING_START FSTRING_TEXT FSTRING_END", types("f\"{{literal}} \\n \\u{41}\""));
        assertEquals("FSTRING_START FSTRING_TEXT INTERP_START IDENTIFIER INTERP_END FSTRING_END",
                types("f\"{{{x}\""));
        assertEquals("IDENTIFIER STRING_LITERAL", types("f \"x\""));
    }

    @Test
    void fStringInterpolationsMayContainStringsAndBraces() {
        assertEquals("FSTRING_START INTERP_START IDENTIFIER LBRACKET STRING_LITERAL RBRACKET INTERP_END FSTRING_END",
                types("f\"{m[\"k\"]}\""));
        assertEquals("FSTRING_START INTERP_START FSTRING_START INTERP_START IDENTIFIER INTERP_END FSTRING_END"
                + " INTERP_END FSTRING_END", types("f\"{f\"{x}\"}\""));
        assertEquals("FSTRING_START INTERP_START IDENTIFIER LBRACE IDENTIFIER COLON INT_LITERAL RBRACE DOT"
                + " IDENTIFIER INTERP_END FSTRING_END", types("f\"{V { x: 1 }.x}\""));
        assertEquals("FSTRING_START INTERP_START CHAR_LITERAL EQ_EQ CHAR_LITERAL INTERP_END FSTRING_END",
                types("f\"{'{' == '}'}\""));
        // Line breaks are fine inside the expression, just not in the text.
        assertEquals("FSTRING_START INTERP_START IDENTIFIER PLUS IDENTIFIER INTERP_END FSTRING_END",
                types("f\"{a\n+ b}\""));
    }

    @Test
    void bracesOutsideFStringsDoNotLeaveTopLevel() {
        assertEquals("RBRACE RBRACE LBRACE RBRACE", types("}}{}"));
        assertEquals("LBRACE FSTRING_START INTERP_START IDENTIFIER INTERP_END FSTRING_END RBRACE",
                types("{f\"{x}\"}"));
    }

    @Test
    void fStringErrors() {
        assertEquals(List.of("1:4: unmatched '}' in f-string (write '}}' for a literal brace)"), errors("f\"a}\""));
        assertEquals(List.of("1:3: invalid escape '\\q'"), errors("f\"\\q\""));
        assertEquals(List.of("1:3: invalid escape '\\u{zz}'"), errors("f\"\\u{zz}\""));
        // Line break: reported, and lexing resumes outside the f-string.
        assertEquals("FSTRING_START FSTRING_TEXT UNTERMINATED_FSTRING IDENTIFIER", types("f\"ab\nx"));
        assertEquals(List.of("1:5: unterminated f-string"), errors("f\"ab\nx"));
        // EOF inside the text or inside an interpolation.
        assertEquals(List.of("1:5: unterminated f-string"), errors("f\"ab"));
        assertEquals("FSTRING_START INTERP_START IDENTIFIER UNTERMINATED_FSTRING", types("f\"{x"));
    }

    @Test
    void tripleFStrings() {
        assertEquals("FSTRING_START FSTRING_TEXT FSTRING_TEXT FSTRING_TEXT INTERP_START IDENTIFIER INTERP_END"
                + " FSTRING_END", types("f\"\"\"a\n\"b{x}\"\"\""));
        assertEquals("FSTRING_START FSTRING_TEXT FSTRING_TEXT FSTRING_END", types("f\"\"\"\"\"x\"\"\""));
        // Ends at the first `"""`, like plain multi-line strings.
        assertEquals("FSTRING_START FSTRING_TEXT FSTRING_END INVALID_STRING", types("f\"\"\"x\"\"\"\""));
        assertEquals(List.of("2:1: unterminated f-string"), errors("f\"\"\"a\n"));
    }

    @Test
    void comments() {
        assertEquals("IDENTIFIER IDENTIFIER", types("a /* x /* nested */ still comment */ b"));
        assertEquals("IDENTIFIER", types("a // tail"));
        assertEquals("DOC_COMMENT FN", types("/// doc\nfn"));
        assertEquals("DOC_COMMENT", types("///"));
        assertEquals("", types("//// not a doc comment"));
        assertEquals("STAR SLASH", types("*/"));
        for (String c : List.of("/**/", "/***/", "/* ** */", "/*/ x */", "/* a **/", "/* /* */ /**/ */")) {
            assertEquals("IDENTIFIER", types(c + "x"), c);
        }
        assertEquals("UNTERMINATED_COMMENT IDENTIFIER IDENTIFIER", types("/* a /* b */ c"));
        var doc = lex("/// doc").get(0);
        assertEquals(ThreeboltLexer.DOC, doc.getChannel());
    }

    @Test
    void byteOrderMarkOnlyAtStart() {
        assertEquals("IDENTIFIER", types("\uFEFFx"));
        assertEquals("IDENTIFIER UNEXPECTED_CHAR", types("x\uFEFF"));
    }

    @Test
    void positionsAreOneBasedAndCountCodePoints() {
        assertEquals("1:1 STRING_LITERAL '\"😀\"'\n1:5 IDENTIFIER 'x'\n2:3 IDENTIFIER 'y'\n",
                TokenDump.render(lex("\"😀\" x\n  y")));
    }

    @Test
    void lexerCanBeResetMidFString() {
        var lexer = new ThreeboltLexer(CharStreams.fromString("f\"{ { x"));
        for (int i = 0; i < 3; i++) lexer.nextToken(); // FSTRING_START INTERP_START LBRACE
        lexer.setInputStream(CharStreams.fromString("} t.0.1"));
        var names = lexer.getAllTokens().stream()
                .map(t -> ThreeboltLexer.VOCABULARY.getSymbolicName(t.getType())).toList();
        assertEquals(List.of("RBRACE", "IDENTIFIER", "DOT", "INT_LITERAL", "DOT", "INT_LITERAL"), names);
    }

    /** Hostile input never reaches ANTLR's error path, and the lexer always reaches EOF. */
    @Test
    void neverReportsAntlrErrors() {
        String alphabet = "f\"'{}\\\n\r/*_.0123xXuU+-<>=?!as;: \t😀#\uFEFF";
        var rnd = new Random(3);
        var failures = new ArrayList<String>();
        for (int n = 0; n < 5000; n++) {
            var sb = new StringBuilder();
            int len = rnd.nextInt(24);
            for (int i = 0; i < len; i++) sb.appendCodePoint(alphabet.codePointAt(alphabet.offsetByCodePoints(0,
                    rnd.nextInt(alphabet.codePointCount(0, alphabet.length())))));
            String src = sb.toString();
            var lexer = new ThreeboltLexer(CharStreams.fromString(src));
            lexer.removeErrorListeners();
            lexer.addErrorListener(new BaseErrorListener() {
                @Override
                public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg,
                                        RecognitionException e) {
                    failures.add(TokenDump.escape(src) + " -> " + msg);
                }
            });
            var tokens = lexer.getAllTokens();
            for (Token t : tokens) {
                if (LexErrors.isError(t)) LexErrors.describe(t); // must not throw
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures.subList(0, Math.min(10, failures.size()))));
    }
}
