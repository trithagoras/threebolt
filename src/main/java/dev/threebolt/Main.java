package dev.threebolt;

import dev.threebolt.parser.ThreeboltLexer;
import java.io.IOException;
import java.nio.file.Path;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("tokens")) {
            dumpTokens(Path.of(args[1]));
            return;
        }
        System.err.println("usage: threebolt tokens <file.3b>");
        System.exit(2);
    }

    private static void dumpTokens(Path file) throws IOException {
        var lexer = new ThreeboltLexer(CharStreams.fromPath(file));
        for (Token t = lexer.nextToken(); t.getType() != Token.EOF; t = lexer.nextToken()) {
            String name = ThreeboltLexer.VOCABULARY.getSymbolicName(t.getType());
            System.out.printf("%d:%d %s '%s'%n", t.getLine(), t.getCharPositionInLine(), name, t.getText());
        }
    }
}
