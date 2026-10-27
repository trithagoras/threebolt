package dev.threebolt.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.threebolt.ast.AstDump;
import dev.threebolt.diag.Diagnostic;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Source;
import java.util.function.BiFunction;
import java.util.function.Function;

/** parse → print → parse yields the same AST, and printing is idempotent. */
final class RoundTrip {
    private RoundTrip() {}

    static <T> void check(String text, BiFunction<Source, Diagnostics, T> parse, Function<T, String> print) {
        T first = parseClean("input", text, parse);
        String printed = print.apply(first);
        T second = parseClean("printed", printed, parse);
        assertEquals(AstDump.render(first), AstDump.render(second), () -> "AST changed; printed as:\n" + printed);
        assertEquals(printed, print.apply(second), "printing is not idempotent");
    }

    static void module(String text) {
        check(text, AstParser::compilationUnit, AstPrinter::print);
    }

    static void stmts(String text) {
        check(text, AstParser::stmtList, AstPrinter::printStmts);
    }

    static void expr(String text) {
        check(text, AstParser::expr, AstPrinter::print);
    }

    static <T> T parseClean(String name, String text, BiFunction<Source, Diagnostics, T> parse) {
        var diagnostics = new Diagnostics();
        T result = parse.apply(new Source(name, text), diagnostics);
        assertEquals("", String.join("\n", diagnostics.sorted().stream().map(Diagnostic::header).toList()),
                () -> name + ":\n" + text);
        assertNotNull(result);
        return result;
    }
}
