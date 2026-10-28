package dev.threebolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.threebolt.diag.Diagnostic;
import dev.threebolt.diag.Diagnostics;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs tests/conformance (see its README): each case is checked, and its diagnostics compared
 * with the case's `.err` file (none expected without one; warnings only when there are no errors). A case without errors is then run and
 * its stdout, stderr and exit code compared with `.out`, `.stderr` and the `//! exit:` directive
 * (with `.in`, if present, as stdin).
 * `-PupdateGolden` rewrites the `.err`, `.out` and `.stderr` files instead.
 */
class ConformanceTest {
    private static final Path ROOT = Path.of("tests/conformance");
    private static final boolean UPDATE = Boolean.getBoolean("threebolt.updateGolden");

    @TestFactory
    Stream<DynamicTest> conformance() throws IOException {
        var entries = new ArrayList<Path>();
        collect(ROOT, entries);
        assertTrue(!entries.isEmpty(), "no conformance cases");
        return entries.stream().map(entry -> DynamicTest.dynamicTest(ROOT.relativize(entry).toString(), () -> run(entry)));
    }

    /** A directory with a `main.3b` is one multi-module case; otherwise each `.3b` file is a case. */
    private static void collect(Path dir, List<Path> entries) throws IOException {
        Path main = dir.resolve("main.3b");
        if (!dir.equals(ROOT) && Files.isRegularFile(main)) {
            entries.add(main);
            return;
        }
        List<Path> children;
        try (var list = Files.list(dir)) {
            children = list.sorted().toList();
        }
        for (Path p : children) {
            if (Files.isDirectory(p)) collect(p, entries);
            else if (p.toString().endsWith(".3b")) entries.add(p);
        }
    }

    private static void run(Path entry) throws IOException {
        var diagnostics = new Diagnostics();
        var program = Driver.check(entry, diagnostics);
        var actual = new StringBuilder();
        for (Diagnostic d : diagnostics.sorted()) {
            // Warnings are expected only of cases without errors: an error case's leftovers are noise.
            if (!d.isWarning() || !diagnostics.hasErrors()) actual.append(d.header()).append('\n');
        }
        golden(sibling(entry, ".err"), actual.toString());
        if (program == null) return;

        String source = Files.readString(entry);
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code;
        try (var o = new PrintStream(out, true, StandardCharsets.UTF_8);
             var e = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            var args = directive(source, "args").map(a -> List.of(a.split(" +"))).orElse(List.of());
            Path stdin = sibling(entry, ".in");
            var in = new ByteArrayInputStream(Files.isRegularFile(stdin) ? Files.readAllBytes(stdin) : new byte[0]);
            code = Driver.run(program, args, in, o, e);
        }
        golden(sibling(entry, ".out"), out.toString(StandardCharsets.UTF_8));
        golden(sibling(entry, ".stderr"), err.toString(StandardCharsets.UTF_8));
        int expected = directive(source, "exit").map(Integer::parseInt).orElse(0);
        assertEquals(expected, code, "exit code (set it with `//! exit: N`)");
    }

    /** {@code //! name: value} on a line of its own. */
    private static java.util.Optional<String> directive(String source, String name) {
        return source.lines().map(String::strip).filter(l -> l.startsWith("//! " + name + ":"))
                .map(l -> l.substring(("//! " + name + ":").length()).strip()).findFirst();
    }

    private static Path sibling(Path entry, String extension) {
        String name = entry.getFileName().toString();
        return entry.resolveSibling(name.substring(0, name.length() - ".3b".length()) + extension);
    }

    /** Compares {@code actual} with {@code file} (absent = empty), or rewrites it with -PupdateGolden. */
    private static void golden(Path file, String actual) throws IOException {
        if (UPDATE) {
            if (actual.isEmpty()) Files.deleteIfExists(file);
            else Files.writeString(file, actual);
            return;
        }
        String expected = Files.exists(file) ? Files.readString(file) : "";
        assertEquals(expected, actual, file.getFileName().toString());
    }
}
