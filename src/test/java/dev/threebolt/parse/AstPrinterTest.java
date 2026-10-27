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
    void initializersInHeads() {
        assertEquals("if (P { x: 0 }) == p {}\n", stmts("if (P { x: 0 }) == p {}"));
        assertEquals("if f(P { x: 0 }) {}\n", stmts("if f(P { x: 0 }) {}"));
        assertEquals("for x in (R { lo: 0 }) {}\n", stmts("for x in (R { lo: 0 }) {}"));
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
    void orPatternsWithIsTypes() {
        assertEquals("match x {\n    (a is A) | b => 1,\n    a is A | B => 2,\n}",
                expr("match x { (a is A) | b => 1, a is A | B => 2 }"));
    }
}
