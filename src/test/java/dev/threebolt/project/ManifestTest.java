package dev.threebolt.project;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.threebolt.diag.Diagnostic;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Source;
import java.util.List;
import org.junit.jupiter.api.Test;

/** `package.toml` and `workspace.toml` parsing (§2.5). */
class ManifestTest {
    private final Diagnostics diagnostics = new Diagnostics();

    private Manifest parse(String text) {
        return Manifest.parse(new Source("package.toml", text), diagnostics);
    }

    private List<String> errors() {
        return diagnostics.sorted().stream().map(Diagnostic::header).toList();
    }

    @Test
    void readsNameDependenciesAndBins() {
        var m = parse("""
                [package]
                name = "server"

                [[bin]]
                name = "server"
                entry = "main.3b"

                [[bin]]
                name = "seed"
                entry = "scripts/seed.3b"

                [dependencies]
                shared = { path = "../shared" }
                """);
        assertEquals(List.of(), errors());
        assertEquals("server", m.name());
        assertEquals(List.of("server", "seed"), m.bins().stream().map(Manifest.Bin::name).toList());
        assertEquals("scripts/seed.3b", m.bins().get(1).entry());
        var dep = m.dependencies().getFirst();
        assertEquals("shared", dep.key());
        assertEquals("../shared", dep.path());
        assertEquals(13, dep.span().line());
        assertEquals(8, m.bins().get(1).span().line());
    }

    @Test
    void reportsMistakesAtTheirLine() {
        parse("""
                [package]
                name = "x"
                version = "1.0"

                [dependencies]
                a = "../a"
                b = { git = "https://example.com/b" }
                c = { path = "../c", paht = "typo" }

                [native]
                """);
        assertEquals(List.of(
                "package.toml:3:1: error[E0111]: unknown key 'version' in [package]",
                "package.toml:6:1: error[E0111]: dependency 'a' must be a table: a = { path = \"…\" }",
                "package.toml:7:1: error[E0111]: dependency 'b': only path dependencies are supported",
                "package.toml:8:1: error[E0111]: dependency 'c': unknown key 'paht'",
                "package.toml:10:2: error[E0111]: unknown table 'native' in package.toml"), errors());
    }

    @Test
    void invalidToml() {
        var m = parse("[package\nname = 1");
        assertEquals(1, errors().size());
        assertEquals("_", m.name());
    }

    @Test
    void workspaceMembers() {
        var w = Manifest.Workspace.parse(new Source("workspace.toml", "[workspace]\nmembers = [\"server\", \"shared\"]\n"), diagnostics);
        assertEquals(List.of(), errors());
        assertEquals(List.of("server", "shared"), w.members());
    }
}
