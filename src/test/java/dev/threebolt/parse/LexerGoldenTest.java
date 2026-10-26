package dev.threebolt.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.threebolt.parser.ThreeboltLexer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.antlr.v4.runtime.CharStreams;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Token-stream golden tests: each {@code src/test/resources/lexer/NAME.3b} is lexed and compared
 * with {@code NAME.tokens}. Regenerate with {@code ./gradlew test -PupdateGolden} and review the diff.
 * Also checks that every example program and spec snippet lexes without errors.
 */
class LexerGoldenTest {
    private static final Path GOLDEN_DIR = Path.of("src/test/resources/lexer");
    private static final boolean UPDATE = Boolean.getBoolean("threebolt.updateGolden");

    static String render(String source) {
        var tokens = new ThreeboltLexer(CharStreams.fromString(source)).getAllTokens();
        var out = new StringBuilder(TokenDump.render(tokens));
        var errors = LexErrors.find(tokens);
        if (!errors.isEmpty()) {
            out.append("-- errors --\n");
            errors.forEach(e -> out.append(e).append('\n'));
        }
        return out.toString();
    }

    @TestFactory
    Stream<DynamicTest> goldenFiles() throws IOException {
        var inputs = sources(GOLDEN_DIR);
        assertTrue(!inputs.isEmpty(), "no golden inputs in " + GOLDEN_DIR.toAbsolutePath());
        return inputs.stream().map(src -> DynamicTest.dynamicTest(src.getFileName().toString(), () -> {
            String actual = render(Files.readString(src));
            Path expectedFile = src.resolveSibling(src.getFileName().toString().replaceAll("\\.3b$", ".tokens"));
            if (UPDATE) {
                Files.writeString(expectedFile, actual);
            } else {
                assertTrue(Files.exists(expectedFile), "missing " + expectedFile + " (run with -PupdateGolden)");
                assertEquals(Files.readString(expectedFile), actual);
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> examplesLexCleanly() throws IOException {
        return sources(Path.of("examples")).stream().map(src -> DynamicTest.dynamicTest(src.toString(),
                () -> assertNoErrors(Files.readString(src))));
    }

    private static final Pattern CODE_BLOCK = Pattern.compile("(?s)```rs\\n(.*?)```");

    @TestFactory
    Stream<DynamicTest> specSnippetsLexCleanly() throws IOException {
        String spec = Files.readString(Path.of("docs/spec/threebolt-1.0.md"));
        var snippets = new ArrayList<String>();
        CODE_BLOCK.matcher(spec).results().forEach(m -> snippets.add(m.group(1)));
        assertTrue(snippets.size() > 10, "expected the spec's ```rs blocks");
        return snippets.stream().map(s -> DynamicTest.dynamicTest(s.lines().findFirst().orElse(""),
                () -> assertNoErrors(s)));
    }

    private static void assertNoErrors(String source) {
        var errors = LexErrors.find(new ThreeboltLexer(CharStreams.fromString(source)).getAllTokens());
        assertEquals(List.of(), errors);
    }

    private static List<Path> sources(Path dir) throws IOException {
        try (var files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".3b")).sorted().toList();
        }
    }
}
