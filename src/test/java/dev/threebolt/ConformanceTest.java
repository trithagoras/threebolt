package dev.threebolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.threebolt.diag.Diagnostic;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.resolve.ModuleLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs tests/conformance (see its README): each case is loaded as a program and its diagnostics
 * are compared with the case's `.err` file (none expected without one). `-PupdateGolden`
 * rewrites the `.err` files instead.
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
        new ModuleLoader(diagnostics).load(entry);
        var actual = new StringBuilder();
        for (Diagnostic d : diagnostics.sorted()) actual.append(d.header()).append('\n');

        String name = entry.getFileName().toString();
        Path expectedFile = entry.resolveSibling(name.substring(0, name.length() - ".3b".length()) + ".err");
        if (UPDATE) {
            if (actual.isEmpty()) Files.deleteIfExists(expectedFile);
            else Files.writeString(expectedFile, actual);
            return;
        }
        String expected = Files.exists(expectedFile) ? Files.readString(expectedFile) : "";
        assertEquals(expected, actual.toString());
    }
}
