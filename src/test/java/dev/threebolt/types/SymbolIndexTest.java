package dev.threebolt.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Span;
import dev.threebolt.ir.Local;
import dev.threebolt.resolve.Module;
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
 * Indexes every example and conformance program and checks each recorded name: its span spells
 * the target's name, and the target has a definition whose span spells it too.
 */
class SymbolIndexTest {
    @TestFactory
    Stream<DynamicTest> everyNameSpellsItsTarget() throws IOException {
        var entries = new ArrayList<Path>();
        for (var root : List.of(Path.of("examples"), Path.of("tests/conformance"))) {
            try (var files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".3b"))
                        .filter(p -> p.getFileName().toString().equals("main.3b") || !Files.exists(p.resolveSibling("main.3b")))
                        .sorted().forEach(entries::add);
            }
        }
        return entries.stream().map(e -> DynamicTest.dynamicTest(e.toString(), () -> check(e)));
    }

    private static void check(Path entry) {
        var diagnostics = new Diagnostics();
        var program = new ModuleLoader(diagnostics).load(entry);
        var index = new SymbolIndex();
        new Checker(program, diagnostics, index).check();
        int count = 0;
        for (Module m : program.all()) {
            for (var r : index.refsIn(m.source())) {
                String name = name(r.target());
                if (!m.scope().imports().containsKey(text(r.span()))) {
                    assertSpells(name, r.span(), "at " + r.span() + " for " + r.target());
                }
                var def = SymbolIndex.definition(r.target());
                if (def != null && !(r.target() instanceof Module)) {
                    assertSpells(name, def, "definition of " + r.target() + " at " + def);
                }
                assertTrue(!SymbolIndex.describe(r).isEmpty());
                count++;
            }
        }
        assertTrue(count > 0);
    }

    /** Std spells keyword-named members with a trailing `_`: `fn null_()` declares `null`. */
    private static void assertSpells(String name, Span span, String what) {
        String text = text(span);
        if (!text.equals(name + "_")) assertEquals(name, text, what);
    }

    private static String name(Object target) {
        return switch (target) {
            case Local l -> l.name();
            case FnInfo f -> f.name();
            case TypeInfo t -> t.name();
            case FieldInfo f -> f.name();
            case StaticInfo s -> s.name();
            case ConstInfo c -> c.name();
            case TypeParamInfo p -> p.name();
            case SymbolIndex.EnumMember e -> e.name();
            case Module m -> m.path().segments().getLast();
            default -> throw new AssertionError(target.getClass().toString());
        };
    }

    private static String text(Span span) {
        var t = span.source().text();
        int from = t.offsetByCodePoints(0, span.start());
        return t.substring(from, t.offsetByCodePoints(from, span.end() - span.start()));
    }
}
