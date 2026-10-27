package dev.threebolt.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Phase 2 acceptance: every example program and every spec snippet parses (docs/PLAN.md). */
class ParserAcceptanceTest {
    static List<Path> examples() throws IOException {
        try (var walk = Files.walk(Path.of("examples"))) {
            return walk.filter(p -> p.toString().endsWith(".3b")).sorted().toList();
        }
    }

    @TestFactory
    Stream<DynamicTest> examplesParse() throws IOException {
        var files = examples();
        assertTrue(!files.isEmpty());
        return files.stream().map(f -> DynamicTest.dynamicTest(f.toString(),
                () -> assertEquals(List.of(), Parsing.compilationUnit(Files.readString(f)).errors())));
    }

    @TestFactory
    Stream<DynamicTest> specSnippetsParse() throws IOException {
        var snippets = SpecSnippets.all();
        assertTrue(snippets.size() > 10, "expected the spec's ```rs blocks");
        return snippets.stream().map(s -> DynamicTest.dynamicTest(SpecSnippets.firstLine(s), () -> assertNotNull(
                SpecSnippets.kind(s),
                () -> "snippet does not parse; as a module: " + Parsing.compilationUnit(s).errors() + "\n" + s)));
    }
}
