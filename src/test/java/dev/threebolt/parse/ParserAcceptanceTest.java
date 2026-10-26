package dev.threebolt.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.threebolt.parser.ThreeboltParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Phase 2 acceptance: every example program and every spec snippet parses (docs/PLAN.md). */
class ParserAcceptanceTest {
    private static final Pattern CODE_BLOCK = Pattern.compile("(?s)```rs\\n(.*?)```");
    // Spec snippets elide bodies as `{ ... }`.
    private static final Pattern ELIDED_BODY = Pattern.compile("\\{ \\.\\.\\. }");

    @TestFactory
    Stream<DynamicTest> examplesParse() throws IOException {
        List<Path> files;
        try (var walk = Files.walk(Path.of("examples"))) {
            files = walk.filter(p -> p.toString().endsWith(".3b")).sorted().toList();
        }
        assertTrue(!files.isEmpty());
        return files.stream().map(f -> DynamicTest.dynamicTest(f.toString(),
                () -> assertEquals(List.of(), Parsing.compilationUnit(Files.readString(f)).errors())));
    }

    /**
     * Snippets are whole modules, member lists (§4.8), statement lists, or one expression per
     * line (§7.8); each must parse as one of those.
     */
    @TestFactory
    Stream<DynamicTest> specSnippetsParse() throws IOException {
        String spec = Files.readString(Path.of("docs/spec/threebolt-1.0.md"));
        var snippets = new ArrayList<String>();
        CODE_BLOCK.matcher(spec).results().forEach(m -> snippets.add(ELIDED_BODY.matcher(m.group(1)).replaceAll("{}")));
        assertTrue(snippets.size() > 10, "expected the spec's ```rs blocks");
        return snippets.stream().map(s -> DynamicTest.dynamicTest(s.lines().findFirst().orElse(""), () -> {
            var module = Parsing.compilationUnit(s);
            if (module.ok()
                    || Parsing.parse(s, ThreeboltParser::memberList).ok()
                    || Parsing.parse(s, ThreeboltParser::stmtList).ok()
                    || everyLineIsAnExpression(s)) {
                return;
            }
            fail("snippet does not parse; as a module: " + module.errors() + "\n" + s);
        }));
    }

    private static boolean everyLineIsAnExpression(String snippet) {
        return snippet.lines()
                .map(line -> line.replaceFirst("//.*", "").strip())
                .filter(line -> !line.isEmpty())
                .allMatch(line -> Parsing.parse(line, ThreeboltParser::exprOnly).ok());
    }
}
