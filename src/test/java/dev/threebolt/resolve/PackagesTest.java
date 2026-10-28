package dev.threebolt.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.threebolt.diag.Diagnostic;
import dev.threebolt.diag.Diagnostics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Packages (§2.5): manifests, keys, package identity and nested packages. */
class PackagesTest {
    @TempDir
    Path root;

    private final Diagnostics diagnostics = new Diagnostics();

    private void write(String file, String text) throws IOException {
        Path p = root.resolve(file);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private void manifest(String dir, String name, String deps) throws IOException {
        write(dir + "/package.toml", "[package]\nname = \"" + name + "\"\n\n[dependencies]\n" + deps);
    }

    private List<String> errors() {
        return diagnostics.sorted().stream().map(Diagnostic::header).toList();
    }

    @Test
    void dependencyModulesAreNamedUnderTheirKey() throws IOException {
        manifest("app", "app", "lib = { path = \"../shared\" }\n");
        write("app/main.3b", "use lib::m::f;\nfn main() { f(); }");
        manifest("shared", "shared", "");
        write("shared/m.3b", "pub fn f() {}");
        var program = new ModuleLoader(diagnostics).load(root.resolve("app/main.3b"), root.resolve("app"));
        assertEquals(List.of(), errors());
        var m = program.module(ModulePath.of("lib", "m"));
        assertNotNull(m);
        assertEquals("shared::m", m.path().toString());
        assertEquals("m", m.local().toString());
        assertEquals("../shared/m.3b", m.source().name());
        assertTrue(program.entry().pkg().isApplication());
        assertTrue(!m.pkg().isApplication() && !m.samePackage(program.entry()));
    }

    @Test
    void pathsReachingOneDirectoryAreOnePackage() throws IOException {
        manifest("app", "app", "a = { path = \"../shared\" }\nb = { path = \"../link\" }\n");
        write("app/main.3b", "fn main() { a::m::f(); b::m::f(); }");
        manifest("shared", "shared", "");
        write("shared/m.3b", "pub fn f() {}");
        Files.createSymbolicLink(root.resolve("link"), root.resolve("shared"));
        var program = new ModuleLoader(diagnostics).load(root.resolve("app/main.3b"), root.resolve("app"));
        assertEquals(List.of(), errors());
        assertSame(program.module(ModulePath.of("a", "m")), program.module(ModulePath.of("b", "m")));
        assertEquals(2, program.packages().size());
    }

    @Test
    void anEntryBelowTheRootIsAModuleOfThePackage() throws IOException {
        manifest(".", "app", "");
        write("scripts/seed.3b", "use game::world::make;\nfn main() { make(); }");
        write("game/world.3b", "pub fn make() {}");
        var program = new ModuleLoader(diagnostics).load(root.resolve("scripts/seed.3b"), root);
        assertEquals(List.of(), errors());
        assertEquals("scripts::seed", program.entry().path().toString());
    }

    @Test
    void nestedPackagesAreNotPartOfTheirParent() throws IOException {
        manifest(".", "app", "");
        write("main.3b", "use vendor::m;\nfn main() {}");
        manifest("vendor", "vendor", "");
        write("vendor/m.3b", "pub fn f() {}");
        new ModuleLoader(diagnostics).load(root.resolve("main.3b"), root);
        assertEquals(List.of("main.3b:1:5: error[E0100]: module 'vendor' not found\n"
                + "vendor/package.toml:1:1: note: 'vendor' is a package of its own, not part of 'app'; depend on it to use it"), errors());
    }

    @Test
    void withoutAManifestTheProgramIsOnePackage() throws IOException {
        write("main.3b", "use game::e;\nfn main() {}");
        write("game/e.3b", "");
        var program = new ModuleLoader(diagnostics).load(root.resolve("main.3b"));
        assertEquals(List.of(), errors());
        assertEquals(1, program.packages().size());
        assertNull(program.application().manifest());
        assertSame(program.entry().pkg(), program.module(ModulePath.of("game", "e")).pkg());
    }

    @Test
    void aLibraryIsLoadedWhole() throws IOException {
        manifest(".", "lib", "");
        write("a.3b", "pub fn f() {}");
        write("deep/b.3b", "fn g() {}");
        write("not-a-module.3b", "");
        var program = new ModuleLoader(diagnostics).loadLibrary(root);
        assertEquals(List.of(), errors());
        assertNull(program.entry());
        assertNotNull(program.module(ModulePath.of("a")));
        assertNotNull(program.module(ModulePath.of("deep", "b")));
    }
}
