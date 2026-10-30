package dev.threebolt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The tour pages (site/src/content/docs/tour/) show the programs in tests/conformance/tour/
 * verbatim: every ```threebolt block must be one of those .3b files, every ```txt block one of
 * their .out files, and every program must appear on some page.
 */
class TourSyncTest {
    private static final Path PAGES = Path.of("site/src/content/docs/tour");
    private static final Path PROGRAMS = Path.of("tests/conformance/tour");
    private static final Pattern FENCE = Pattern.compile("^```(\\w+)[^\\n]*\\n(.*?)^```$", Pattern.MULTILINE | Pattern.DOTALL);

    @TestFactory
    Stream<DynamicTest> pagesMatchPrograms() throws IOException {
        var programs = contents(".3b");
        var outputs = contents(".out");
        return pages().map(page -> DynamicTest.dynamicTest(page.getFileName().toString(), () -> {
            var m = FENCE.matcher(Files.readString(page));
            while (m.find()) {
                var lang = m.group(1);
                var code = m.group(2);
                var known = switch (lang) {
                    case "threebolt", "3b" -> programs;
                    case "txt" -> outputs;
                    default -> null;
                };
                if (known == null) continue;
                int line = (int) Files.readString(page).substring(0, m.start()).lines().count() + 1;
                assertTrue(known.containsKey(code), () -> page + ":" + line + ": this ```" + lang
                        + " block matches no file in " + PROGRAMS + "; edit the program there and paste it back verbatim");
            }
        }));
    }

    @Test
    void everyProgramIsShown() throws IOException {
        var shown = new HashSet<String>();
        for (var page : pages().toList()) {
            var m = FENCE.matcher(Files.readString(page));
            while (m.find()) shown.add(m.group(2));
        }
        var unshown = contents(".3b").entrySet().stream()
                .filter(e -> !shown.contains(e.getKey()))
                .map(Map.Entry::getValue).sorted().toList();
        assertTrue(unshown.isEmpty(), () -> "tour programs shown on no page: " + unshown);
    }

    private static Stream<Path> pages() throws IOException {
        List<Path> pages;
        try (var s = Files.list(PAGES)) {
            pages = s.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
        return pages.stream();
    }

    /** Contents of every {@code ext} file under {@link #PROGRAMS}, mapped to its path. */
    private static Map<String, Path> contents(String ext) throws IOException {
        try (var s = Files.walk(PROGRAMS)) {
            return s.filter(p -> p.toString().endsWith(ext))
                    .collect(Collectors.toMap(TourSyncTest::read, Function.identity(), (a, b) -> a));
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
