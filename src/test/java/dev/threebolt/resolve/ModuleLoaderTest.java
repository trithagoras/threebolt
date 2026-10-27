package dev.threebolt.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.threebolt.ast.Item;
import dev.threebolt.diag.Diagnostic;
import dev.threebolt.diag.Diagnostics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Module loading, `use` resolution and prelude injection (§2.1, §2.2, §16). */
class ModuleLoaderTest {
    @TempDir
    Path root;

    private final Diagnostics diagnostics = new Diagnostics();

    private void write(String file, String text) throws IOException {
        Path p = root.resolve(file);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private Program load() {
        return new ModuleLoader(diagnostics).load(root.resolve("main.3b"));
    }

    private List<String> errors() {
        return diagnostics.sorted().stream().map(Diagnostic::header).toList();
    }

    private static Item declaration(List<? extends Symbol> symbols) {
        assertEquals(1, symbols.size(), symbols::toString);
        return assertInstanceOf(Symbol.Decl.class, symbols.getFirst()).item();
    }

    @Test
    void loadsModulesReachableThroughUse() throws IOException {
        write("main.3b", "use game::entity;\nuse game::entity::{Entity, spawn as make};\nfn main() {}");
        write("game/entity.3b", "pub struct Entity;\npub fn spawn() -> Entity => Entity {};");
        write("unrelated.3b", "this does not even parse");
        var program = load();
        assertEquals(List.of(), errors());
        assertEquals(java.util.Set.of("main", "game::entity", "std::prelude", "std::collections", "std::c", "std::io"),
                program.all().stream().map(m -> m.path().toString()).collect(java.util.stream.Collectors.toSet()));
        var scope = program.entry().scope();
        assertInstanceOf(Symbol.ModuleRef.class, scope.lookup("entity").getFirst());
        assertInstanceOf(Item.Struct.class, declaration(scope.lookup("Entity")));
        assertInstanceOf(Item.Fn.class, declaration(scope.lookup("make")));
        assertEquals(List.of(), scope.lookup("spawn"));
    }

    @Test
    void cyclicImportsAreFine() throws IOException {
        write("main.3b", "use a::A;\nfn main() {}");
        write("a.3b", "use b::B;\npub struct A { let b: B; }");
        write("b.3b", "use a::A;\npub struct B;");
        var program = load();
        assertEquals(List.of(), errors());
        assertInstanceOf(Item.Struct.class, declaration(program.module(ModulePath.of("b")).scope().lookup("A")));
    }

    @Test
    void overloadsImportTogether() throws IOException {
        write("main.3b", "use m::f;\nfn main() {}");
        write("m.3b", "pub fn f() {}\npub fn f(x: i32) {}\nfn f(x: str) {}");
        var program = load();
        assertEquals(List.of(), errors());
        assertEquals(2, program.entry().scope().lookup("f").size());
    }

    @Test
    void preludeIsVisibleAndShadowable() throws IOException {
        write("main.3b", "use m::Map;\nstruct Done;\nfn main() {}");
        write("m.3b", "pub struct Map;");
        var scope = load().entry().scope();
        assertEquals(List.of(), errors());
        var println = (Item.Fn) declaration(scope.lookup("println"));
        assertEquals("std/io.3b", println.span().source().name());
        var c = assertInstanceOf(Symbol.ModuleRef.class, scope.lookup("c").getFirst());
        assertEquals("std::c", c.module().path().toString());
        assertEquals("m.3b", declaration(scope.lookup("Map")).span().source().name());
        assertEquals("main.3b", declaration(scope.lookup("Done")).span().source().name());
        assertEquals("std/collections.3b", declaration(
                load().module(ModulePath.of("m")).scope().lookup("Set")).span().source().name());
    }

    @Test
    void canonicalStdImportsResolve() throws IOException {
        write("main.3b", "use std::collections::Map;\nuse std::c;\nfn main() {}");
        load();
        assertEquals(List.of(), errors());
    }

    @Test
    void missingModulesAndItems() throws IOException {
        write("main.3b", """
                use nope;
                use nope::deeper::Thing;
                use m::Missing;
                use m::{Here, Gone};
                use absent::{X, Y};
                fn main() {}""");
        write("m.3b", "pub struct Here;");
        load();
        assertEquals(List.of(
                "main.3b:1:5: error[E0100]: module 'nope' not found",
                "main.3b:2:5: error[E0100]: module 'nope::deeper' not found",
                "main.3b:3:8: error[E0101]: module 'm' has no item named 'Missing'",
                "main.3b:4:15: error[E0101]: module 'm' has no item named 'Gone'",
                "main.3b:5:5: error[E0100]: module 'absent' not found"), errors());
    }

    @Test
    void privateItemsCannotBeImported() throws IOException {
        write("main.3b", "use m::Secret;\nuse main::helper;\nfn helper() {}\nfn main() {}");
        write("m.3b", "struct Secret;");
        load();
        // Importing from the module itself sees its private items, but `helper` is then defined twice.
        assertEquals(List.of(
                "main.3b:1:8: error[E0102]: 'Secret' is private to module 'm'\n"
                        + "m.3b:1:1: note: declared here without `pub`",
                "main.3b:2:11: error[E0104]: 'helper' is already defined in this module\n"
                        + "main.3b:3:1: note: previously defined here"), errors());
    }

    @Test
    void moduleAndItemWithTheSameNameIsAmbiguous() throws IOException {
        write("main.3b", "use a::b;\nfn main() {}");
        write("a.3b", "pub fn b() {}");
        write("a/b.3b", "pub fn f() {}");
        load();
        assertEquals(List.of("main.3b:1:5: error[E0103]: 'a::b' names both a module and an item of module 'a'\n"
                + "a.3b:1:1: note: the item is defined here"), errors());
    }

    @Test
    void duplicateNames() throws IOException {
        write("main.3b", """
                use m::A;
                use m::A;
                use n::A;
                struct S;
                enum S { X }
                fn f() {}
                fn f(x: i32) {}
                const f: i32 = 1;
                fn main() {}""");
        write("m.3b", "pub struct A;");
        write("n.3b", "pub struct A;");
        load();
        assertEquals(List.of(
                "main.3b:3:8: error[E0104]: 'A' is already defined in this module\n"
                        + "main.3b:1:8: note: previously imported here",
                "main.3b:5:6: error[E0104]: 'S' is already defined in this module\n"
                        + "main.3b:4:1: note: previously defined here",
                "main.3b:8:7: error[E0104]: 'f' is already defined in this module\n"
                        + "main.3b:6:1: note: previously defined here"), errors());
    }

    @Test
    void declarationChecks() throws IOException {
        write("main.3b", """
                struct i32;
                type str = i64;
                fn u8() {}
                @inline fn f() {}
                struct P { @c let x: i32; }
                pub use m::A;
                fn main() {}""");
        write("m.3b", "pub struct A;");
        load();
        assertEquals(List.of(
                "main.3b:1:8: error[E0105]: 'i32' is a primitive type name",
                "main.3b:2:6: error[E0105]: 'str' is a primitive type name",
                "main.3b:4:1: error[E0004]: unknown attribute '@inline'",
                "main.3b:6:1: error[E0106]: `use` cannot be `pub`: there are no re-exports in 1.0"), errors());
    }

    @Test
    void moduleNamesAreCaseSensitive() throws IOException {
        write("main.3b", "use Dice;\nfn main() {}");
        write("dice.3b", "pub fn roll() {}");
        load();
        assertEquals(List.of("main.3b:1:5: error[E0100]: module 'Dice' not found"), errors());
    }

    @Test
    void syntaxErrorsInImportedModulesAreReported() throws IOException {
        write("main.3b", "use broken::X;\nfn main() {}");
        write("broken.3b", "pub struct X\nfn");
        load();
        var errors = errors();
        assertTrue(errors.getFirst().startsWith("broken.3b:2:1: error[E0001]: "), errors::toString);
        assertTrue(errors.stream().noneMatch(e -> e.startsWith("main.3b")), errors::toString);
    }

    @Test
    void cIntegerAliasesFollowThePlatform() throws IOException {
        var aliases = ModuleLoader.cIntegerAliases();
        long longBits = java.lang.foreign.Linker.nativeLinker().canonicalLayouts().get("long").byteSize() * 8;
        assertTrue(aliases.contains("pub type int = i32;"), aliases);
        assertTrue(aliases.contains("pub type uint = u32;"), aliases);
        assertTrue(aliases.contains("pub type long = i" + longBits + ";"), aliases);
        assertTrue(aliases.contains("pub type longlong = i64;"), aliases);
        assertTrue(aliases.contains("pub type char = i8;") || aliases.contains("pub type char = u8;"), aliases);

        write("main.3b", "fn main() { let x: c::long = 1; let y: c::size_t = 2; }");
        var c = load().module(ModulePath.of("std", "c"));
        assertEquals(List.of(), errors());
        assertInstanceOf(Item.TypeAlias.class, declaration(c.scope().lookup("ulonglong")));
    }
}
