package dev.threebolt.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.threebolt.diag.Diagnostic;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.resolve.ModuleLoader;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Union normalization (§3.4), assignability (§3.5), widening (§3.6), trait lookup (§4.10), ABI
 * types (§14.2).
 */
class TypeSystemTest {
    private static final String PROGRAM = """
            trait Speak { fn speak(self) -> str; }
            trait Loud : Speak {}
            class Animal { pub fn new() -> Self => {}; }
            class Dog : Animal, Loud {
                pub fn new() -> Self => { ..Animal::new() };
                pub fn speak(self) -> str => "woof";
            }
            struct Pt : Speak {
                let x: i32 = 0;
                pub fn speak(self) -> str => "pt";
            }
            struct Holder { let f: fn() -> i32; }
            class Box<T> { let v: T; }
            @c struct CPair { let a: i32; let b: [u8; 4]; }
            enum Color : u8 { Red }
            fn main() {}
            """;

    private static TypeSystem ts;
    private static Checker checker;

    @BeforeAll
    static void check(@TempDir Path root) throws IOException {
        var entry = root.resolve("main.3b");
        Files.writeString(entry, PROGRAM);
        var diagnostics = new Diagnostics();
        checker = new Checker(new ModuleLoader(diagnostics).load(entry), diagnostics);
        checker.check();
        assertEquals(List.of(), diagnostics.sorted().stream().map(Diagnostic::header).toList());
        ts = checker.ts;
    }

    private static TypeInfo decl(String name) {
        return checker.decls.allTypes.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private static Type.Nominal type(String name, Type... args) {
        return ts.nominal(decl(name), args);
    }

    private static Type lit(long v) {
        return new Type.Lit(Prim.I32, BigInteger.valueOf(v));
    }

    private static Type lit(String s) {
        return new Type.Lit(Prim.STR, s);
    }

    private static Type union(Type... ts) {
        return TypeSystem.union(List.of(ts));
    }

    // ---- Unions (§3.4) ----

    @Test
    void unionsFlattenAndDeduplicate() {
        var u = union(Prim.I32, union(Prim.STR, Type.NULL), Prim.I32);
        assertEquals(List.of(Prim.I32, Prim.STR, Type.NULL), TypeSystem.members(u));
        assertEquals(union(Type.NULL, Prim.STR, Prim.I32), u, "member order does not matter for equality");
        assertEquals("i32 | str | null", u.toString());
    }

    @Test
    void singleMemberUnionIsThatType() {
        assertEquals(Prim.BOOL, union(Prim.BOOL, Prim.BOOL));
    }

    @Test
    void literalsAreSubsumedByTheirBase() {
        assertEquals(union(Prim.STR, Prim.I32), union(lit("a"), Prim.STR, lit(1), Prim.I32));
        assertEquals(2, TypeSystem.members(union(lit("a"), lit("b"))).size());
        assertEquals(union(lit(1), Prim.I64), union(lit(1), Prim.I64), "an i32 literal is not subsumed by i64");
    }

    @Test
    void neverVanishesAndErrorAbsorbs() {
        assertEquals(Prim.I32, union(Prim.I32, Type.NEVER));
        assertEquals(Type.NEVER, union(Type.NEVER, Type.NEVER));
        assertEquals(Type.ERROR, union(Prim.I32, Type.ERROR));
    }

    @Test
    void minusRemovesAssignableMembers() {
        var u = union(Prim.I32, Prim.STR, Type.NULL);
        assertEquals(union(Prim.I32, Prim.STR), ts.minus(u, Type.NULL));
        assertEquals(Type.NULL, ts.minus(u, union(Prim.I32, Prim.STR)));
        assertEquals(Type.NEVER, ts.minus(Prim.I32, Prim.I32));
    }

    @Test
    void literalBaseOnlyForSingleBaseLiteralUnions() {
        assertEquals(Prim.STR, TypeSystem.literalBase(union(lit("left"), lit("right"))));
        var mixed = union(lit("a"), lit(1));
        assertEquals(mixed, TypeSystem.literalBase(mixed));
        assertEquals(Prim.I32, TypeSystem.literalBase(Prim.I32));
    }

    @Test
    void widenRemovesLiteralTypes() {
        assertEquals(Prim.I32, TypeSystem.widen(lit(3)));
        assertEquals(new Type.Tuple(List.of(Prim.STR, Prim.I32)),
                TypeSystem.widen(new Type.Tuple(List.of(lit("a"), lit(1)))));
        assertEquals(union(Prim.STR, Type.NULL), TypeSystem.widen(union(lit("a"), lit("b"), Type.NULL)));
    }

    // ---- Widening (§3.6) ----

    @Test
    void integersWidenLosslessly() {
        assertTrue(Prim.I8.widensTo(Prim.I16));
        assertTrue(Prim.I32.widensTo(Prim.I64));
        assertTrue(Prim.U8.widensTo(Prim.U16));
        assertTrue(Prim.U8.widensTo(Prim.I16), "unsigned to a wider signed type");
        assertTrue(Prim.U32.widensTo(Prim.I64));
        assertFalse(Prim.U32.widensTo(Prim.I32), "same width: the sign bit is lost");
        assertFalse(Prim.I8.widensTo(Prim.U16), "signed never widens to unsigned");
        assertFalse(Prim.I64.widensTo(Prim.I32));
    }

    @Test
    void sizeTypesWidenOnlyTo64Bit() {
        assertTrue(Prim.ISIZE.widensTo(Prim.I64));
        assertTrue(Prim.USIZE.widensTo(Prim.U64));
        assertFalse(Prim.USIZE.widensTo(Prim.I64));
        assertFalse(Prim.ISIZE.widensTo(Prim.F64));
        for (var p : List.of(Prim.I8, Prim.U8, Prim.I32, Prim.U32, Prim.I64)) {
            assertFalse(p.widensTo(Prim.ISIZE), p + " -> isize");
            assertFalse(p.widensTo(Prim.USIZE), p + " -> usize");
        }
    }

    @Test
    void integersWidenToFloatsThatHoldThemExactly() {
        assertTrue(Prim.I16.widensTo(Prim.F32));
        assertFalse(Prim.I32.widensTo(Prim.F32));
        assertTrue(Prim.I32.widensTo(Prim.F64));
        assertTrue(Prim.U32.widensTo(Prim.F64));
        assertFalse(Prim.I64.widensTo(Prim.F64));
        assertTrue(Prim.F32.widensTo(Prim.F64));
        assertFalse(Prim.F64.widensTo(Prim.F32));
        assertFalse(Prim.F32.widensTo(Prim.I64));
    }

    // ---- Assignability (§3.5) ----

    @Test
    void primitivesAndLiterals() {
        assertTrue(ts.assignable(Prim.I32, Prim.I32));
        assertTrue(ts.assignable(Prim.I32, Prim.I64));
        assertFalse(ts.assignable(Prim.I64, Prim.I32));
        assertTrue(ts.assignable(lit(1), Prim.I32));
        assertTrue(ts.assignable(lit(1), Prim.F64), "a literal is assignable where its base widens");
        assertFalse(ts.assignable(Prim.I32, lit(1)));
        assertFalse(ts.assignable(lit(1), lit(2)));
        assertTrue(ts.assignable(Type.NEVER, Prim.STR));
        assertTrue(ts.assignable(Type.ERROR, Prim.STR));
        assertTrue(ts.assignable(Prim.STR, Type.ERROR));
    }

    @Test
    void unions() {
        var isn = union(Prim.I32, Prim.STR, Type.NULL);
        assertTrue(ts.assignable(Type.NULL, isn));
        assertTrue(ts.assignable(Prim.STR, isn));
        assertTrue(ts.assignable(union(Prim.I32, Type.NULL), isn), "a subset union");
        assertFalse(ts.assignable(isn, union(Prim.I32, Prim.STR)));
        assertFalse(ts.assignable(Prim.BOOL, isn));
        assertTrue(ts.assignable(Prim.I16, union(Prim.I64, Prim.STR)), "widening into a member");
        assertTrue(ts.assignable(union(lit("a"), lit("b")), Prim.STR));
        assertFalse(ts.assignable(Prim.STR, union(lit("a"), lit("b"))));
    }

    @Test
    void classesUpcastButNeverDowncast() {
        assertTrue(ts.assignable(type("Dog"), type("Animal")));
        assertFalse(ts.assignable(type("Animal"), type("Dog")));
        assertTrue(ts.assignable(type("Dog"), union(type("Animal"), Type.NULL)));
    }

    @Test
    void genericsAreInvariant() {
        assertTrue(ts.assignable(type("Box", Prim.I32), type("Box", Prim.I32)));
        assertFalse(ts.assignable(type("Box", Prim.I32), type("Box", Prim.I64)));
        assertFalse(ts.assignable(ts.listOf(type("Dog")), ts.listOf(type("Animal"))));
    }

    @Test
    void traitReferences() {
        assertTrue(ts.assignable(type("Pt"), type("Speak")), "boxing a struct");
        assertTrue(ts.assignable(type("Dog"), type("Loud")));
        assertTrue(ts.assignable(type("Dog"), type("Speak")), "through a supertrait");
        assertTrue(ts.assignable(type("Loud"), type("Speak")), "a trait reference to its supertrait");
        assertFalse(ts.assignable(type("Speak"), type("Loud")));
        assertFalse(ts.assignable(type("Animal"), type("Speak")), "the base class does not implement it");
        assertFalse(ts.assignable(Prim.I32, type("Speak")));
    }

    @Test
    void functionTypes() {
        var f = new Type.Fn(List.of(Prim.I32), Prim.STR, false);
        assertTrue(ts.assignable(f, new Type.Fn(List.of(Prim.I32), Prim.STR, false)));
        assertFalse(ts.assignable(f, new Type.Fn(List.of(Prim.I64), Prim.STR, false)), "no variance");
        assertFalse(ts.assignable(f, new Type.Fn(List.of(Prim.I32), Prim.STR, true)), "not to extern fn");
    }

    // ---- Traits (§4.10, §4.5) ----

    @Test
    void implementsAsFindsInstances() {
        assertEquals(type("Speak"), ts.implementsAs(type("Dog"), decl("Speak")));
        assertEquals(ts.nominal(ts.iterable, Prim.STR), ts.implementsAs(ts.listOf(Prim.STR), ts.iterable));
        assertNull(ts.implementsAs(type("Animal"), decl("Speak")));
    }

    @Test
    void automaticEqAndHash() {
        assertNotNull(ts.implementsAs(type("Pt"), ts.eq), "a struct of Eq fields");
        assertNotNull(ts.implementsAs(type("Pt"), ts.hash));
        assertNull(ts.implementsAs(type("Holder"), ts.eq), "fn fields have no Eq");
        assertNotNull(ts.implementsAs(new Type.Tuple(List.of(Prim.I32, Prim.STR)), ts.eq));
        assertNotNull(ts.implementsAs(union(Prim.I32, Type.NULL), ts.hash));
        assertNull(ts.implementsAs(Prim.F64, ts.hash), "floats have no Hash");
        assertNull(ts.implementsAs(Prim.BOOL, ts.ord), "bool has no Ord");
        assertNotNull(ts.implementsAs(type("Animal"), ts.eq), "classes by identity");
        assertNotNull(ts.implementsAs(type("Speak"), ts.eq), "trait references by identity");
        assertNull(ts.implementsAs(type("Animal"), ts.hash));
    }

    @Test
    void containsInstances() {
        assertEquals(ts.nominal(ts.contains, Prim.STR), ts.implementsAs(ts.listOf(Prim.STR), ts.contains));
        assertEquals(ts.nominal(ts.contains, type("Animal")), ts.implementsAs(ts.listOf(type("Animal")), ts.contains));
        assertNull(ts.implementsAs(ts.listOf(type("Holder")), ts.contains), "elements without Eq");
        assertEquals(ts.nominal(ts.contains, Prim.I32), ts.implementsAs(ts.nominal(ts.iterator, Prim.I32), ts.contains),
                "an impl on a trait reference");
        assertNull(ts.implementsAs(type("Dog"), ts.contains));
        assertEquals(ts.nominal(ts.contains, Prim.CHAR), ts.implementsAs(Prim.STR, ts.contains));
        assertEquals(ts.nominal(ts.iterable, Prim.CHAR), ts.implementsAs(Prim.STR, ts.iterable));
    }

    @Test
    void unionsImplementUnionSafeTraitsOfEveryMember() {
        assertTrue(ts.unionSafe(ts.display));
        assertTrue(ts.unionSafe(decl("Loud")), "supertrait members are checked too");
        assertFalse(ts.unionSafe(ts.ord), "Self as a parameter");
        assertFalse(ts.unionSafe(ts.defaultTrait), "a static member");
        assertEquals(ts.nominal(ts.display), ts.implementsAs(union(Prim.I32, Prim.STR), ts.display));
        assertEquals(ts.nominal(ts.display), ts.implementsAs(union(Prim.I32, Type.NULL), ts.display), "null displays");
        assertEquals(type("Speak"), ts.implementsAs(union(type("Dog"), type("Pt")), decl("Speak")));
        assertNull(ts.implementsAs(union(type("Dog"), Prim.I32), decl("Speak")), "not every member");
        assertNull(ts.implementsAs(union(Prim.I32, Prim.STR), ts.ord));
        assertNull(ts.implementsAs(union(ts.listOf(Prim.STR), ts.listOf(Prim.I32)), ts.iterable),
                "members implement different instances");
    }

    @Test
    void sameIsStructural() { // recursive aliases are covered by unions/traits.3b (`Json`)
        assertTrue(TypeSystem.same(union(Prim.I32, Prim.STR), union(Prim.STR, Prim.I32)));
        assertFalse(TypeSystem.same(ts.listOf(Prim.I32), ts.listOf(Prim.I64)));
    }

    @Test
    void nameofSpelling() {
        assertEquals("List<i32>", TypeSystem.name(ts.listOf(Prim.I32)));
        assertEquals("Box<List<str>>", TypeSystem.name(type("Box", ts.listOf(Prim.STR))));
        assertEquals("fn(i32) -> str | null", TypeSystem.name(new Type.Fn(List.of(Prim.I32), union(Prim.STR, Type.NULL), false)));
        assertEquals("[i32]", ts.listOf(Prim.I32).toString(), "diagnostics use the surface spelling");
    }

    // ---- ABI types (§14.2) ----

    @Test
    void abiTypes() {
        for (Type t : List.of(Prim.I8, Prim.U64, Prim.USIZE, Prim.F32, Prim.BOOL, type("CPair"), type("Color"),
                type("Ptr", Prim.STR), new Type.Fn(List.of(Prim.I32), Prim.I32, true))) {
            assertTrue(TypeSystem.isAbi(t, false), t + " is an ABI type");
        }
        for (Type t : List.of(Prim.STR, Prim.CHAR, type("Pt"), type("Dog"), ts.listOf(Prim.I32), Type.NULL,
                union(Prim.I32, Type.NULL), new Type.Fn(List.of(), Prim.I32, false), new Type.Tuple(List.of(Prim.I32)))) {
            assertFalse(TypeSystem.isAbi(t, false), t + " is not an ABI type");
        }
    }

    @Test
    void arraysAreAbiOnlyInsideCStructs() {
        var bytes = new Type.Array(Prim.U8, 4);
        assertFalse(TypeSystem.isAbi(bytes, false));
        assertTrue(TypeSystem.isAbi(bytes, true));
        assertTrue(TypeSystem.isAbi(new Type.Array(bytes, 2), true));
        assertFalse(TypeSystem.isAbi(new Type.Array(Prim.STR, 2), true));
    }
}
