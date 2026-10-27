package dev.threebolt.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.threebolt.ast.AstDump;
import dev.threebolt.ast.Expr;
import dev.threebolt.ast.Item;
import dev.threebolt.diag.Diagnostic;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Source;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parse tree → AST: structure (as {@link AstDump}s), literal decoding, spans, literal errors. */
class AstBuilderTest {
    private static String expr(String source) {
        return AstDump.render(RoundTrip.parseClean("expr", source, AstParser::expr));
    }

    private static String stmts(String source) {
        return AstDump.render(RoundTrip.parseClean("stmts", source, AstParser::stmtList));
    }

    private static String item(String source) {
        return AstDump.render(RoundTrip.parseClean("item", source, AstParser::compilationUnit).items().getFirst());
    }

    private static List<String> errors(String source) {
        var diagnostics = new Diagnostics();
        assertNotNull(AstParser.stmtList(new Source("t.3b", source), diagnostics));
        return diagnostics.sorted().stream().map(Diagnostic::header).toList();
    }

    private static String path(String name) {
        return "(Path [(PathSegment " + name + " [])])";
    }

    // ---- Structure ----

    @Test
    void operatorsByPrecedence() {
        assertEquals("(Binary ADD (IntLit 1 null) (Binary MUL (IntLit 2 null) (IntLit 3 null)))", expr("1 + 2 * 3"));
        assertEquals("(Binary GE " + path("a") + " " + path("b") + ")", expr("a >= b"));
        assertEquals("(Binary SHR " + path("a") + " " + path("b") + ")", expr("a >> b"));
        assertEquals("(Binary RANGE_INCLUSIVE (IntLit 1 null) " + path("n") + ")", expr("1..=n"));
    }

    @Test
    void parenthesesAreDropped() {
        assertEquals(expr("a * (b)"), expr("(a) * b"));
    }

    @Test
    void castThenPropagate() {
        assertEquals("(Propagate (Cast " + path("x") + " (Named [T] []) false))", expr("x as T?"));
        assertEquals("(Cast " + path("x") + " (Named [T] []) true)", expr("x as? T"));
    }

    @Test
    void methodCallIsCallOfMember() {
        assertEquals("(Call (Member " + path("x") + " m [(Named [T] [])]) [(Arg null (IntLit 1 null)) (Arg k "
                + path("v") + ")])", expr("x.m<T>(1, k: v)"));
    }

    @Test
    void genericPath() {
        assertEquals("(Call (Path [(PathSegment Ptr [(Named [c long] [])]) (PathSegment null [])]) [])",
                expr("Ptr<c::long>::null()"));
    }

    @Test
    void initializerShorthandIsANamedField() {
        assertEquals(expr("P { name: name, ..base }"), expr("P { name, ..base }"));
        assertEquals("(Init null [(FieldInit x " + path("x") + ")])", expr("{ x }"));
    }

    @Test
    void elseIfChains() {
        assertEquals("[(If null " + path("a") + " (Block []) (If null " + path("b") + " (Block []) (Block [])))]",
                stmts("if a {} else if b {} else {}"));
        assertEquals("[(If (Binder true x) " + path("e") + " (Block []) null)]", stmts("if let mut x = e {}"));
    }

    @Test
    void assignments() {
        assertEquals("[(Assign null ASSIGN " + path("f") + ")]", stmts("_ = f;"));
        assertEquals("[(Assign " + path("x") + " SHR (IntLit 1 null))]", stmts("x >>= 1;"));
    }

    @Test
    void matchStatementsAndArms() {
        assertEquals("[(ExprStmt (Match " + path("x") + " [(MatchArm (Literal (IntLit -1 null)) null (IntLit 0 null))"
                + " (MatchArm (Binding false n null) (Binary LT " + path("n") + " (IntLit 0 null)) (Block []))]))]",
                stmts("match x { -1 => 0, n if n < 0 => {} }"));
    }

    @Test
    void fnTypeReturnIsSeparatedFromParams() {
        assertEquals("(FnType false [(Named [i32] []) (Named [str] [])] (Union [(Named [R] []) (Literal (NullLit))]))",
                AstDump.render(RoundTrip.parseClean("t", "fn(i32, str) -> R | null", AstParser::type)));
    }

    @Test
    void items() {
        assertEquals("(Use [] PUB [a b] null [(UseName C null) (UseName D E)])", item("pub use a::b::{C, D as E};"));
        assertEquals("(Use [] PRIVATE [a b] c null)", item("use a::b as c;"));
        assertEquals("(Fn [] PRIVATE NONE false false sq [] [(Named false x (Named [f64] []) null)] (Named [f64] [])"
                + " (Binary MUL " + path("x") + " " + path("x") + "))", item("fn sq(x: f64) -> f64 => x * x;"));
        assertEquals("(Extern [(Attribute link [(StrLit \"m\")])] PRIVATE \"C\" [(Fn [] PRIVATE NONE false false sqrt"
                + " [] [(Named false x (Named [f64] []) null)] (Named [f64] []) null)])",
                item("@link(\"m\") extern \"C\" { fn sqrt(x: f64) -> f64; }"));
    }

    @Test
    void members() {
        var cls = (Item.Class) RoundTrip.parseClean("c", """
                pub abstract class A : B {
                    prot let legs: i32 = 4;
                    pub virtual get fn n(self) -> i32 => self.legs;
                    static let mut N: u64 = 0;
                }""", AstParser::compilationUnit).items().getFirst();
        assertEquals("[(Field [] PROT legs (Named [i32] []) (IntLit 4 null)) (Fn [] PUB VIRTUAL false true n []"
                + " [(Receiver false)] (Named [i32] []) (Member (Self) legs [])) (Static [] PRIVATE true N"
                + " (Named [u64] []) (IntLit 0 null))]", AstDump.render(cls.members()));
    }

    // ---- Literals ----

    @Test
    void integerLiterals() {
        assertEquals("(IntLit 255 \"u8\")", expr("0xFF_u8"));
        assertEquals("(IntLit 1000000 null)", expr("1_000_000"));
        assertEquals("(IntLit 10 null)", expr("0b1010"));
        assertEquals("(IntLit 15 null)", expr("0o17"));
        assertEquals("(IntLit 18446744073709551616 null)", expr("18446744073709551616"));
    }

    @Test
    void floatLiterals() {
        assertEquals("(FloatLit \"2.5e-3\" \"f32\")", expr("2.5e-3_f32"));
        assertEquals("(FloatLit \"1\" \"f32\")", expr("1_f32"));
        assertEquals("(FloatLit \"1000.5\" null)", expr("1_000.5"));
    }

    @Test
    void stringAndCharLiterals() {
        assertEquals("(StrLit \"a\\nb\\\"c\\\\ 😀\")", expr("\"a\\nb\\\"c\\\\ \\u{1F600}\""));
        assertEquals("(CharLit 128512)", expr("'\\u{1F600}'"));
        assertEquals("(CharLit 39)", expr("'\\''"));
        assertEquals("(StrLit \"a\\nb\")", expr("\"\"\"a\r\nb\"\"\""));
    }

    @Test
    void fstringTextIsDecodedAndMerged() {
        assertEquals("(FString [(Text \"a \\\"b\\\" {\") (Interpolation " + path("x") + ") (Text \"}\")])",
                expr("f\"\"\"a \"b\" {{{x}}}\"\"\""));
    }

    @Test
    void invalidScalarValues() {
        assertEquals(List.of("t.3b:1:10: error[E0002]: '\\u{D800}' is not a Unicode scalar value"),
                errors("let s = \"\\u{D800}\";"));
        assertEquals(List.of("t.3b:1:12: error[E0002]: '\\u{110000}' is not a Unicode scalar value"),
                errors("let s = f\"a\\u{110000}\";"));
    }

    @Test
    void invalidTupleIndices() {
        assertEquals(List.of("t.3b:1:3: error[E0003]: invalid tuple index '0_u8' (write a plain decimal like .0)"),
                errors("t.0_u8;"));
        assertEquals(List.of("t.3b:1:3: error[E0003]: invalid tuple index '01' (write a plain decimal like .0)"),
                errors("t.01;"));
    }

    @Test
    void syntaxErrorsBecomeDiagnostics() {
        var diagnostics = new Diagnostics();
        assertEquals(null, AstParser.stmtList(new Source("t.3b", "let = 1;"), diagnostics));
        assertEquals("t.3b:1:5: error[E0001]: unexpected '='", diagnostics.sorted().getFirst().header());
    }

    @Test
    void spans() {
        var e = (Expr.Binary) RoundTrip.parseClean("s", "foo +\n  bar", AstParser::expr);
        assertEquals("s:1:1", e.span().toString());
        assertEquals("s:2:3", e.right().span().toString());
        assertEquals(11, e.span().end());
    }
}
