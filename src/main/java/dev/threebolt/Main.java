package dev.threebolt;

import dev.threebolt.parse.LexErrors;
import dev.threebolt.parse.TokenDump;
import dev.threebolt.parser.ThreeboltLexer;
import java.io.IOException;
import java.nio.file.Path;
import org.antlr.v4.runtime.CharStreams;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("tokens")) {
            System.exit(dumpTokens(Path.of(args[1])));
        }
        System.err.println("usage: threebolt tokens <file.3b>");
        System.exit(2);
    }

    private static int dumpTokens(Path file) throws IOException {
        var tokens = new ThreeboltLexer(CharStreams.fromPath(file)).getAllTokens();
        System.out.print(TokenDump.render(tokens));
        var errors = LexErrors.find(tokens);
        for (var e : errors) {
            System.err.println(file + ":" + e.line() + ":" + e.column() + ": error: " + e.message());
        }
        return errors.isEmpty() ? 0 : 1;
    }
}
