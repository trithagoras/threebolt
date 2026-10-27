package dev.threebolt.parse;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Phase 3 acceptance: parse → print → parse gives the same AST for every program we have. */
class AstRoundTripTest {
    @TestFactory
    Stream<DynamicTest> programs() throws IOException {
        var files = new ArrayList<>(ParserAcceptanceTest.examples());
        files.addAll(threeboltFiles(Path.of("src/main/resources/std")));
        files.addAll(threeboltFiles(Path.of("src/test/resources/printer")));
        // Conformance cases only when they build cleanly: some are literal or syntax errors on purpose.
        threeboltFiles(Path.of("tests/conformance")).stream()
                .filter(f -> {
                    var diagnostics = new Diagnostics();
                    AstParser.compilationUnit(new Source(f.toString(), read(f)), diagnostics);
                    return !diagnostics.hasErrors();
                })
                .forEach(files::add);
        return files.stream().map(f -> DynamicTest.dynamicTest(f.toString(), () -> RoundTrip.module(read(f))));
    }

    @TestFactory
    Stream<DynamicTest> specSnippets() throws IOException {
        var snippets = SpecSnippets.all();
        assertTrue(snippets.size() > 10);
        return snippets.stream().map(s -> DynamicTest.dynamicTest(SpecSnippets.firstLine(s), () -> {
            switch (SpecSnippets.kind(s)) {
                case MODULE -> RoundTrip.module(s);
                case MEMBERS -> RoundTrip.check(s, AstParser::memberList, AstPrinter::printMembers);
                case STMTS -> RoundTrip.stmts(s);
                case EXPR_LINES -> SpecSnippets.exprLines(s).forEach(RoundTrip::expr);
                case null -> throw new AssertionError("snippet does not parse (see ParserAcceptanceTest)");
            }
        }));
    }

    private static List<Path> threeboltFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (var walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".3b")).sorted().toList();
        }
    }

    private static String read(Path f) {
        try {
            return Files.readString(f);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
