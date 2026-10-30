package dev.threebolt.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Where the printer must put parentheses back; every case is also checked to round-trip. */
class AstPrinterTest {
    private static String expr(String source) {
        RoundTrip.expr(source);
        return AstPrinter.print(RoundTrip.parseClean("expr", source, AstParser::expr));
    }

    private static String stmts(String source) {
        RoundTrip.stmts(source);
        return AstPrinter.printStmts(RoundTrip.parseClean("stmts", source, AstParser::stmtList));
    }

    private static String type(String source) {
        return AstPrinter.print(RoundTrip.parseClean("type", source, AstParser::type));
    }

    @Test
    void precedenceAndAssociativity() {
        assertEquals("(1 + 2) * 3", expr("(1 + 2) * 3"));
        assertEquals("1 + 2 * 3", expr("1 + (2 * 3)"));
        assertEquals("a - (b - c)", expr("a - (b - c)"));
        assertEquals("a - b - c", expr("(a - b) - c"));
        assertEquals("(a ?? b) ?? c", expr("(a ?? b) ?? c"));
        assertEquals("a ?? b ?? c", expr("a ?? (b ?? c)"));
        assertEquals("(a < b) == (c < d)", expr("(a < b) == (c < d)"));
        assertEquals("(x => x + 1)(2)", expr("(x => x + 1)(2)"));
        assertEquals("-x as i64", expr("(-x) as i64"));
        assertEquals("-(x as i64)", expr("-(x as i64)"));
        assertEquals("(x as T)?", expr("x as T?"));
    }

    @Test
    void comparisonsInListsStayParenthesized() {
        // Without parentheses, `a < b, c > (d)` would be the generic call `a<b, c>(d)` (§7.5).
        assertEquals("f((a < b), c > d)", expr("f((a < b), (c > (d)))"));
        assertEquals("[(x < y), z > w]", expr("[(x < y), z > (w)]"));
        assertEquals("f((a < b), c > (d, e))", expr("f((a < b), c > (d, e))"));
        assertEquals("f(a<b, c>(d))", expr("f(a < b, c > (d))"));
    }

    @Test
    void castsFollowedByPipeOrLess() {
        assertEquals("(x as u8) | y", expr("(x as u8) | y"));
        assertEquals("a + (b as T) | c", expr("a + (b as T) | c"));
        assertEquals("(x as T) < y", expr("(x as T) < y"));
        assertEquals("x as A | B", expr("x as A | B"));
        assertEquals("x as T + 1", expr("(x as T) + 1"));
    }

    @Test
    void tupleFieldsAndNumbers() {
        assertEquals("(1).0", expr("(1).0"));
        assertEquals("t.0.1", expr("(t.0).1"));
        assertEquals("1.5.0", expr("(1.5).0"));
        assertEquals("(1,)", expr("(1,)"));
    }

    @Test
    void deferUnsafeKeepsItsForm() {
        assertEquals("defer unsafe {\n    f();\n}\n", stmts("defer unsafe { f(); }"));
        assertEquals("defer {\n    unsafe {\n        f();\n    }\n}\n", stmts("defer { unsafe { f(); } }"));
        assertEquals("defer unsafe => f();\n", stmts("defer unsafe => f();"));
    }

    @Test
    void initializersInHeads() {
        assertEquals("if (P { x: 0 }) == p {}\n", stmts("if (P { x: 0 }) == p {}"));
        assertEquals("if f(P { x: 0 }) {}\n", stmts("if f(P { x: 0 }) {}"));
        assertEquals("for x in (R { lo: 0 }) {}\n", stmts("for x in (R { lo: 0 }) {}"));
    }

    @Test
    void forWithPropagation() {
        assertEquals("for line? in f.lines() {}\n", stmts("for line? in f.lines() {}"));
        assertEquals("for mut x? in xs {}\n", stmts("for mut x ? in xs {}"));
        assertEquals("for (k, v)? in m {}\n", stmts("for (k, v)? in m {}"));
    }

    @Test
    void negatedTests() {
        assertEquals("x is not CancelledErr", expr("x is not CancelledErr"));
        assertEquals("x is not CancelledErr", expr("!(x is CancelledErr)"));
        assertEquals("xs is not [_, ..]", expr("xs is not [_, ..]"));
        assertEquals("k not in m", expr("!(k in m)"));
        assertEquals("(k not in m) == b", expr("(k not in m) == b"));
        assertEquals("!(k not in m)", expr("!(k not in m)"));
        assertEquals("a && x is not T", expr("a && x is not T"));
        assertEquals("if (p is not P { x: 0 }) {}\n", stmts("if (p is not P { x: 0 }) {}"));
        // Elsewhere `not` is an ordinary name.
        assertEquals("not(x) && not in xs", expr("not(x) && not in xs"));
    }

    @Test
    void negatedAndRelationalPatterns() {
        assertEquals("xs is [not 0, ..]", expr("xs is [not 0, ..]"));
        assertEquals("p is (not null, _)", expr("p is (not null, _)"));
        assertEquals("c is not ('a'..='z' | '_')", expr("c is not ('a'..='z' | '_')"));
        assertEquals("x is (not 1 | 2)", expr("x is (not 1 | 2)"));
        assertEquals("x is ((not 1) | 2)", expr("x is ((not 1) | 2)"));
        assertEquals("n is > 10", expr("n is > 10"));
        assertEquals("n is not <= -1", expr("n is not <= -1"));
        assertEquals("match v {\n    is not Err => 1,\n    e is not Err => 2,\n    >= 0.5 => 3,\n    < \"m\" => 4,\n    not => 5,\n}",
                expr("match v { is not Err => 1, e is not Err => 2, >= 0.5 => 3, < \"m\" => 4, not => 5 }"));
        assertEquals("match v {\n    (is not A) | B => 1,\n}", expr("match v { (is not A) | B => 1 }"));
    }

    @Test
    void statementsStartingWithBraceOrMatch() {
        assertEquals("({ x: 1 }.go());\n", stmts("({ x: 1 }).go();"));
        assertEquals("({ x: 1 }.y) = 2;\n", stmts("({ x: 1 }).y = 2;"));
        assertEquals("(match x {\n    _ => a,\n} - 1);\n", stmts("(match x { _ => a }) - 1;"));
        assertEquals("match x {\n    _ => a,\n};\n-1;\n", stmts("match x { _ => a }; -1;"));
        assertEquals("match x {\n    _ => a,\n}\ny();\n", stmts("match x { _ => a } y();"));
    }

    @Test
    void lambdaPatternParams() {
        assertEquals("((a, b)) => a", expr("((a, b)) => a"));
        assertEquals("x => x", expr("((x)) => x"));
        assertEquals("(acc, P { x, y: (_, mut z) }: P) => z", expr("(acc, P { x, y: (_, mut z) }: P) => z"));
    }

    @Test
    void bracesAfterArrowsInLambdasAndArms() {
        assertEquals("x => ({ a: 1 })", expr("x => ({ a: 1 })"));
        assertEquals("match x {\n    _ => ({ a }),\n}", expr("match x { _ => ({ a }) }"));
    }

    @Test
    void fstrings() {
        assertEquals("f\"{ { x: 1 }.x}\"", expr("f\"{ {x: 1}.x }\""));
        assertEquals("f\"a {{b}} {f\"{c}\"}\\n\"", expr("f\"\"\"a {{b}} {f\"{c}\"}\n\"\"\""));
    }

    @Test
    void types() {
        assertEquals("(fn() -> i32) | null", type("(fn() -> i32) | null"));
        assertEquals("fn() -> i32 | null", type("fn() -> (i32 | null)"));
        assertEquals("A | (B | C)", type("A | (B | C)"));
        assertEquals("fn() | null", type("fn() | null"));
    }

    @Test
    void comprehensions() {
        assertEquals("[x + 1 for x in xs if x % 2 == 0]", expr("[(x + 1) for x in xs if (x % 2 == 0)]"));
        assertEquals("[(x, y) for x in xs, y in ys if x != y]", expr("[(x, y) for x in xs, y in ys if x != y]"));
        assertEquals("[k: v for (k, v) in m]", expr("[k: v for (k, v) in m]"));
        assertEquals("[a if c else b for x in (p if q else r) if (s if t else u)]",
                expr("[a if c else b for x in (p if q else r) if (s if t else u)]"));
        assertEquals("[f for x in xs if (y => y)(x)]", expr("[f for x in xs if (y => y)(x)]"));
    }

    @Test
    void orPatternsWithIsTypes() {
        assertEquals("match x {\n    (a is A) | b => 1,\n    a is A | B => 2,\n}",
                expr("match x { (a is A) | b => 1, a is A | B => 2 }"));
    }
}
