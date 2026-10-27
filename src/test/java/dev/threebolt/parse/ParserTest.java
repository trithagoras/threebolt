package dev.threebolt.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.threebolt.parser.ThreeboltParser;
import java.util.List;
import java.util.function.Function;
import org.antlr.v4.runtime.ParserRuleContext;
import org.junit.jupiter.api.Test;

/** Parser behaviour for the grammar notes of spec §17; trees rendered by {@link TreeDump}. */
class ParserTest {
    private static String expr(String source) {
        return tree(source, ThreeboltParser::exprOnly).replaceFirst("^\\(exprOnly (.*)\\)$", "$1");
    }

    private static String stmts(String source) {
        return tree(source, ThreeboltParser::stmtList).replaceFirst("^\\(stmtList (.*)\\)$", "$1");
    }

    private static String type(String source) {
        return tree(source, ThreeboltParser::typeOnly).replaceFirst("^\\(typeOnly (.*)\\)$", "$1");
    }

    private static <T extends ParserRuleContext> String tree(String source, Function<ThreeboltParser, T> rule) {
        var result = Parsing.parse(source, rule);
        assertEquals(List.of(), result.errors(), source);
        return TreeDump.render(result.tree());
    }

    private static List<ParseError> errors(String source, Function<ThreeboltParser, ? extends ParserRuleContext> rule) {
        var errors = Parsing.parse(source, rule).errors();
        assertFalse(errors.isEmpty(), "expected a syntax error: " + source);
        return errors;
    }

    private static ParseError firstError(String source) {
        return errors(source, ThreeboltParser::stmtList).getFirst();
    }

    // ---- Precedence (§7.2) ----

    @Test
    void arithmeticIsLeftAssociativeAndLayered() {
        assertEquals("(additiveExpr (additiveExpr 1 + (multiplicativeExpr 2 * 3)) - 4)", expr("1 + 2 * 3 - 4"));
    }

    @Test
    void bitwiseBindsTighterThanComparison() {
        assertEquals("(comparisonExpr (bitAndExpr a & b) == (bitOrExpr c | d))", expr("a & b == c | d"));
    }

    @Test
    void prefixBindsTighterThanCastTighterThanMultiplication() {
        assertEquals("(multiplicativeExpr (castExpr (unaryExpr - x) as u8) * 2)", expr("-x as u8 * 2"));
    }

    @Test
    void coalesceAndConditionalAreRightAssociative() {
        assertEquals("(coalesceExpr a ?? (coalesceExpr b ?? c))", expr("a ?? b ?? c"));
        assertEquals("(conditionalExpr a if c else (conditionalExpr b if d else e))",
                expr("a if c else b if d else e"));
    }

    @Test
    void comparisonsAndRangesDoNotChain() {
        assertEquals(new ParseError(1, 7, "unexpected '<'"), errors("a < b < c", ThreeboltParser::exprOnly).getFirst());
        assertEquals(new ParseError(1, 5, "unexpected '..'"), errors("a..b..c", ThreeboltParser::exprOnly).getFirst());
    }

    @Test
    void castThenPropagate() {
        assertEquals("(castExpr x as T ?)", expr("x as T?"));
    }

    @Test
    void isTakesAUnionType() {
        assertEquals("(comparisonExpr x is (type A | B))", expr("x is A | B"));
    }

    @Test
    void isTakesAPatternAfterBracketsBracesAndRanges() {
        assertEquals("(comparisonExpr x is (listPattern [ ]))", expr("x is []"));
        assertEquals("(comparisonExpr x is (listPattern [ a , (listPatternElem .. r) ]))", expr("x is [a, ..r]"));
        assertEquals("(comparisonExpr x is (listPattern [ str ]))", expr("x is [str]"), "never the list type");
        assertEquals("(comparisonExpr x is (tuplePattern ( 1 , _ )))", expr("x is (1, _)"));
        assertEquals("(comparisonExpr x is (structPattern P { x , .. }))", expr("x is P { x, .. }"));
        assertEquals("(comparisonExpr x is (literalOrRangePattern 1 ..= 9))", expr("x is 1..=9"));
        assertEquals("(comparisonExpr x is (literalOrRangePattern (literalPattern - 1) .. 0))", expr("x is -1..0"));
    }

    @Test
    void isTakesATypeOtherwise() {
        assertEquals("(comparisonExpr x is (typePath List (typeArgs < str >)))", expr("x is List<str>"));
        assertEquals("(comparisonExpr x is \"a\")", expr("x is \"a\""));
        assertEquals("(comparisonExpr x is null)", expr("x is null"));
    }

    @Test
    void isStructPatternInAHeadNeedsParentheses() {
        // §6.3: unparenthesized, `P` is a type and `{` opens the block.
        assertEquals("(ifStmt if (comparisonExpr x is P) (block { }))", stmts("if x is P {}"));
        assertEquals("(ifStmt if (parenExpr ( (comparisonExpr x is (structPattern P { .. })) )) (block { }))",
                stmts("if (x is P { .. }) {}"));
    }

    // ---- `>` assembly: `>>`, `>=`, `>>=` (§17.2) ----

    @Test
    void adjacentGreaterThansFormShiftsAndComparisons() {
        assertEquals("(comparisonExpr (shiftExpr a (shiftOp > >) b) (compareOp > =) c)", expr("a >> b >= c"));
        assertEquals("(assignStmt x (assignOp > > =) 1 ;)", stmts("x >>= 1;"));
        assertEquals("(assignStmt x <<= 1 ;)", stmts("x <<= 1;"));
    }

    @Test
    void spacedGreaterThansAreErrors() {
        assertEquals(new ParseError(1, 5, "'>>' must not contain spaces"),
                errors("a > > b", ThreeboltParser::exprOnly).getFirst());
        assertEquals(new ParseError(1, 5, "'>=' must not contain spaces"),
                errors("a > = b", ThreeboltParser::exprOnly).getFirst());
    }

    @Test
    void nestedTypeArgumentsNeedNoSplitting() {
        assertEquals("(typePath List (typeArgs < (typePath List (typeArgs < i32 >)) >))", type("List<List<i32>>"));
        assertEquals("(fnDecl fn (fnSignature f ( ) -> (typePath Box (typeArgs < T >))) (exprBody => x ;))",
                TreeDump.render(Parsing.compilationUnit("fn f() -> Box<T>=> x;").tree())
                        .replaceFirst("^\\(compilationUnit (.*)\\)$", "$1"));
    }

    // ---- Generic arguments in expressions (§7.5) ----

    @Test
    void typeArgumentsBeforeCallOrPath() {
        assertEquals("(postfixExpr x (memberOp . with (typeArgs < Details >)) (callArgs ( (initializer { name }) )) ?)",
                expr("x.with<Details>({ name })?"));
        assertEquals("(postfixExpr (path (pathSegment Type (typeArgs < T >)) :: f) (callArgs ( )))", expr("Type<T>::f()"));
        assertEquals("(postfixExpr (path (pathSegment Ptr (typeArgs < (typePath c :: long) >)) :: null) (callArgs ( )))",
                expr("Ptr<c::long>::null()"));
    }

    @Test
    void genericCallWinsOverComparisonsInArguments() {
        assertEquals("(postfixExpr f (callArgs ( (postfixExpr (pathSegment a (typeArgs < b , c >)) (callArgs ( d ))) )))",
                expr("f(a < b, c > (d))"));
        assertEquals("(tupleExpr ( (comparisonExpr a < b) , (comparisonExpr c > d) ))", expr("(a < b, c > d)"));
    }

    @Test
    void lessThanWithoutClosingTypeArgsIsComparison() {
        assertEquals("(comparisonExpr (postfixExpr a (memberOp . b)) < c)", expr("a.b < c"));
    }

    // ---- Members after `.` and `::` (§1) ----

    @Test
    void keywordsAndTupleIndicesAsMemberNames() {
        assertEquals("(postfixExpr x (memberOp . type) (callArgs ( )))", expr("x.type()"));
        assertEquals("(postfixExpr t (memberOp . 0) (memberOp . 1))", expr("t.0.1"));
    }

    // ---- Lambdas, tuples, unsafe (§7.8, §7.12, §17.7) ----

    @Test
    void lambdaVersusTuple() {
        assertEquals("(lambda (lambdaParams ( a , b )) => (additiveExpr a + b))", expr("(a, b) => a + b"));
        assertEquals("(tupleExpr ( a , b ))", expr("(a, b)"));
        assertEquals("(tupleExpr ( a , ))", expr("(a,)"));
        assertEquals("(lambda (lambdaParams mut c) => (postfixExpr c (memberOp . update) (callArgs ( ))))",
                expr("mut c => c.update()"));
    }

    @Test
    void lambdaBodies() {
        assertEquals("(lambda x => (block { (returnStmt return x ;) }))", expr("x => { return x; }"));
        assertEquals("(lambda x => (assignOrExpr (postfixExpr self (memberOp . y)) = x))", expr("x => self.y = x"));
    }

    @Test
    void lambdaBraceIsAlwaysABlock() {
        // `{ a: 1 }` is a valid initializer, but a lambda body `{` is a block (§7.1).
        errors("x => { a: 1 }", ThreeboltParser::exprOnly);
    }

    @Test
    void unsafeExpressionExtendsRight() {
        assertEquals("(unsafeExpr unsafe => (castExpr (postfixExpr time (callArgs ( null ))) as u32))",
                expr("unsafe => time(null) as u32"));
    }

    // ---- Literals and initializers (§7.7, §4.7) ----

    @Test
    void collectionLiterals() {
        assertEquals("(listCollection [ 1 , 2 , 3 ])", expr("[1, 2, 3]"));
        assertEquals("(mapCollection [ (mapEntry k : v) , (mapEntry k2 : v2) ])", expr("[k: v, k2: v2]"));
        assertEquals("(emptyMap [ : ])", expr("[:]"));
        assertEquals("(repeatCollection [ 0 ; 16 ])", expr("[0; 16]"));
    }

    @Test
    void initializerWithSpread() {
        assertEquals("(namedInitExpr Vec2 (initializer { (namedField x : 1.0) , (spreadField .. base) }))",
                expr("Vec2 { x: 1.0, ..base }"));
    }

    @Test
    void nestedFStrings() {
        assertEquals("(fstring f\" a  (fstringPart { (postfixExpr g (callArgs ( (fstring f\" (fstringPart { x }) \") ))) })  b \")",
                expr("f\"a {g(f\"{x}\")} b\""));
    }

    @Test
    void fnExpressionBodyBraceIsAnInitializer() {
        var tree = TreeDump.render(Parsing.parse("fn new() -> Self => { name };", ThreeboltParser::memberList).tree());
        assertTrue(tree.contains("(exprBody => (initializer { name }) ;)"), tree);
    }

    // ---- Statements (§6) ----

    @Test
    void statementInitialBraceIsABlock() {
        assertEquals(new ParseError(1, 1, "unexpected '{'"), firstError("{ a: 1 };"));
    }

    @Test
    void initializersMustBeParenthesizedInHeads() {
        assertEquals(new ParseError(1, 9,
                        "unexpected 'Foo' (an initializer in an if/while/for/match head must be parenthesized)"),
                firstError("if x == Foo { a: 1 } { }"));
        assertEquals("(ifStmt if (parenExpr ( (comparisonExpr x == (namedInitExpr Foo (initializer { (namedField a : 1) }))) )) (block { }))",
                stmts("if (x == Foo { a: 1 }) { }"));
    }

    @Test
    void collectionLiteralsAreAllowedInHeads() {
        assertEquals("(forStmt for x in (postfixExpr (listCollection [ 1 , 2 ]) (memberOp . iter) (callArgs ( ))) (block { }))",
                stmts("for x in [1, 2].iter() { }"));
        assertEquals("(forStmt for x in (listCollection [ 1 , 2 ]) (block { }))", stmts("for x in [1, 2] { }"));
    }

    @Test
    void initializersAreAllowedInsideBracketsInHeads() {
        assertTrue(stmts("for i in xs.map(x => Foo { a: x }) { }").contains("(namedInitExpr Foo"));
    }

    @Test
    void ifLetAndElseIf() {
        assertEquals("(ifStmt if let mut x = e (block { }) else (ifStmt if c (block { }) else (block { })))",
                stmts("if let mut x = e { } else if c { } else { }"));
        assertEquals("(whileStmt while let x = (postfixExpr it (memberOp . next) (callArgs ( ))) (block { }))",
                stmts("while let x = it.next() { }"));
    }

    @Test
    void matchStatementNeedsNoSemicolon() {
        assertEquals("(matchExpr match x (matchBody { (matchArm a => 1) })) (exprStmt (postfixExpr y (callArgs ( ))) ;)",
                stmts("match x { a => 1 } y();"));
    }

    @Test
    void matchArmsAndGuards() {
        assertEquals("(matchExpr match x (matchBody { (matchArm (pattern 1 | 2) => \"s\" ,) "
                        + "(matchArm n if (comparisonExpr n < 0) => \"neg\" ,) (matchArm _ => (block { (returnStmt return ;) })) }))",
                expr("match x { 1 | 2 => \"s\", n if n < 0 => \"neg\", _ => { return; } }"));
        errors("match x { 1 => a 2 => b }", ThreeboltParser::exprOnly);
    }

    @Test
    void assignmentTargets() {
        assertEquals("(assignStmt _ = (postfixExpr f (callArgs ( ))) ;)", stmts("_ = f();"));
        assertEquals("(assignStmt (postfixExpr a (memberOp . b) (indexOp [ i ]) (memberOp . c)) = 2 ;)", stmts("a.b[i].c = 2;"));
        assertEquals(new ParseError(1, 7, "unexpected '='"), firstError("a + b = c;"));
    }

    @Test
    void letWithPattern() {
        assertEquals("(letStmt let (tuplePattern ( a , (bindingPattern mut b) )) = pair ;)", stmts("let (a, mut b) = pair;"));
    }

    // ---- Types (§3.2) ----

    @Test
    void types() {
        assertEquals("(fnType fn ( i32 ) -> (type R | null))", type("fn(i32) -> R | null"));
        assertEquals("(tupleType ( i32 , ))", type("(i32,)"));
        assertEquals("(arrayType [ u8 ; (multiplicativeExpr N * 2) ])", type("[u8; N * 2]"));
        assertEquals("(type \"left\" | \"right\" | 3 | (literalType - 1) | null)", type("\"left\" | \"right\" | 3 | -1 | null"));
    }

    // ---- Declarations ----

    @Test
    void getIsContextual() {
        var tree = TreeDump.render(Parsing.compilationUnit("struct S { get fn f(self) -> i32 => 1; fn get(self) {} }").tree());
        assertTrue(tree.contains("(fnMember get fn (fnSignature f"), tree);
        assertTrue(tree.contains("(fnMember fn (fnSignature get"), tree);
        assertEquals(new ParseError(1, 1, "unexpected 'get'"),
                errors("get fn f(self) -> i32 => 1;", ThreeboltParser::compilationUnit).getFirst());
    }

    @Test
    void dispatchModifiersAreMemberOnly() {
        assertEquals(new ParseError(1, 14, "expected 'class', found 'fn'"),
                errors("pub abstract fn f() {}", ThreeboltParser::compilationUnit).getFirst());
    }

    @Test
    void errorsHaveLineAndColumn() {
        assertEquals(List.of(new ParseError(2, 15, "expected ';', found '}'")),
                Parsing.compilationUnit("fn f() {\n    let x = 1 }").errors());
    }

    @Test
    void lexErrorsAreReportedOnceWithoutParserNoise() {
        assertEquals(List.of(new ParseError(1, 18, "unterminated string literal")),
                Parsing.compilationUnit("fn f() { let s = \"abc; }").errors());
    }
}
