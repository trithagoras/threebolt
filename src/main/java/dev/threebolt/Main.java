package dev.threebolt;

import dev.threebolt.ast.AstDump;
import dev.threebolt.diag.Diagnostic;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Source;
import dev.threebolt.parse.AstParser;
import dev.threebolt.parse.AstPrinter;
import dev.threebolt.parse.LexErrors;
import dev.threebolt.parse.Parsing;
import dev.threebolt.parse.TokenDump;
import dev.threebolt.parse.TreeDump;
import dev.threebolt.parser.ThreeboltLexer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.antlr.v4.runtime.CharStreams;

public final class Main {
    private Main() {}

    private static final String USAGE = """
            usage: threebolt <command> <file.3b> [args...]
              run     check the program and run its `main` with the remaining arguments
              check   load and type-check the program (entry file + the modules it reaches)
              tokens  dump the token stream
              parse   dump the parse tree
              ast     dump the AST
              print   pretty-print the AST as source""";

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || (args.length > 2 && !args[0].equals("run"))) usage();
        Path file = Path.of(args[1]);
        int status = switch (args[0]) {
            case "run" -> run(file, List.of(args).subList(2, args.length));
            case "check" -> check(file);
            case "tokens" -> dumpTokens(file);
            case "parse" -> dumpTree(file);
            case "ast" -> dumpAst(file, false);
            case "print" -> dumpAst(file, true);
            default -> usage();
        };
        System.exit(status);
    }

    private static int usage() {
        System.err.println(USAGE);
        System.exit(2);
        return 2;
    }

    private static int check(Path file) {
        var diagnostics = new Diagnostics();
        Driver.check(file, diagnostics);
        return report(diagnostics);
    }

    private static int run(Path file, List<String> args) {
        var diagnostics = new Diagnostics();
        var program = Driver.check(file, diagnostics);
        if (program == null) return report(diagnostics);
        return Driver.run(program, args, System.out, System.err);
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

    private static int dumpAst(Path file, boolean asSource) throws IOException {
        var diagnostics = new Diagnostics();
        var unit = AstParser.compilationUnit(new Source(file.toString(), Files.readString(file)), diagnostics);
        if (unit != null) System.out.print(asSource ? AstPrinter.print(unit) : AstDump.render(unit) + "\n");
        return report(diagnostics);
    }

    private static int report(Diagnostics diagnostics) {
        for (Diagnostic d : diagnostics.sorted()) System.err.print(d.render());
        return diagnostics.hasErrors() ? 1 : 0;
    }
}
