package dev.threebolt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.threebolt.parser.ThreeboltLexer;
import org.antlr.v4.runtime.CharStreams;
import org.junit.jupiter.api.Test;

class LexerSmokeTest {
    @Test
    void skipsCommentsAndWhitespace() {
        var lexer = new ThreeboltLexer(CharStreams.fromString("fn /* x /* nested */ */ main // tail\n"));
        var tokens = lexer.getAllTokens();
        assertEquals(2, tokens.size());
        assertEquals(ThreeboltLexer.IDENTIFIER, tokens.get(0).getType());
        assertEquals("main", tokens.get(1).getText());
    }
}
