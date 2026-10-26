package dev.threebolt.parse;

import dev.threebolt.parser.ThreeboltLexer;
import dev.threebolt.parser.ThreeboltParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

/** Lexes and parses source text, collecting lexical and syntax errors in source order. */
public final class Parsing {
    private Parsing() {}

    public record Result<T extends ParserRuleContext>(T tree, List<ParseError> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public static Result<ThreeboltParser.CompilationUnitContext> compilationUnit(String source) {
        return parse(source, ThreeboltParser::compilationUnit);
    }

    /** Parses {@code source} with the given entry rule (e.g. {@code ThreeboltParser::stmtList}). */
    public static <T extends ParserRuleContext> Result<T> parse(String source, Function<ThreeboltParser, T> rule) {
        var lexer = new ThreeboltLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        var tokens = new CommonTokenStream(lexer);
        tokens.fill();

        var errors = new ArrayList<ParseError>();
        LexErrors.find(tokens.getTokens()).forEach(e -> errors.add(ParseError.of(e)));

        var parser = new ThreeboltParser(tokens);
        parser.removeErrorListeners();
        parser.setErrorHandler(new SyntaxErrorStrategy());
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                    int charPositionInLine, String msg, RecognitionException e) {
                errors.add(new ParseError(line, charPositionInLine + 1, msg));
            }
        });
        T tree = rule.apply(parser);
        errors.sort(Comparator.comparingInt(ParseError::line).thenComparingInt(ParseError::column));
        return new Result<>(tree, List.copyOf(errors));
    }
}
