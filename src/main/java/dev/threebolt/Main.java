package dev.threebolt;

import dev.threebolt.parse.LexErrors;
import dev.threebolt.parse.Parsing;
import dev.threebolt.parse.TokenDump;
import dev.threebolt.parse.TreeDump;
import dev.threebolt.parser.ThreeboltLexer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.antlr.v4.runtime.CharStreams;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("tokens")) {
            System.exit(dumpTokens(Path.of(args[1])));
        }
        if (args.length == 2 && args[0].equals("parse")) {
            System.exit(dumpTree(Path.of(args[1])));
        }
        System.err.println("usage: threebolt tokens|parse <file.3b>");
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

    private static int dumpTree(Path file) throws IOException {
        var result = Parsing.compilationUnit(Files.readString(file));
        System.out.println(TreeDump.render(result.tree()));
        for (var e : result.errors()) {
            System.err.println(file + ":" + e.line() + ":" + e.column() + ": error: " + e.message());
        }
        return result.ok() ? 0 : 1;
    }
}
