package dev.threebolt.parse;

import dev.threebolt.ast.CompilationUnit;
import dev.threebolt.ast.Expr;
import dev.threebolt.ast.Member;
import dev.threebolt.ast.Stmt;
import dev.threebolt.ast.TypeExpr;
import dev.threebolt.diag.Code;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Source;
import dev.threebolt.diag.Span;
import dev.threebolt.parser.ThreeboltParser;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.antlr.v4.runtime.ParserRuleContext;

/**
 * Source text to AST. Lexical and syntax errors are reported as {@link Code#SYNTAX} diagnostics;
 * when there are any, no AST is built and the result is {@code null}.
 */
public final class AstParser {
    private AstParser() {}

    public static CompilationUnit compilationUnit(Source source, Diagnostics diagnostics) {
        return parse(source, diagnostics, ThreeboltParser::compilationUnit, AstBuilder::compilationUnit);
    }

    public static List<Member> memberList(Source source, Diagnostics diagnostics) {
        return parse(source, diagnostics, ThreeboltParser::memberList, AstBuilder::memberList);
    }

    public static List<Stmt> stmtList(Source source, Diagnostics diagnostics) {
        return parse(source, diagnostics, ThreeboltParser::stmtList, AstBuilder::stmtList);
    }

    public static Expr expr(Source source, Diagnostics diagnostics) {
        return parse(source, diagnostics, ThreeboltParser::exprOnly, AstBuilder::exprOnly);
    }

    public static TypeExpr type(Source source, Diagnostics diagnostics) {
        return parse(source, diagnostics, ThreeboltParser::typeOnly, AstBuilder::typeOnly);
    }

    private static <C extends ParserRuleContext, R> R parse(Source source, Diagnostics diagnostics,
                                                            Function<ThreeboltParser, C> rule,
                                                            BiFunction<AstBuilder, C, R> build) {
        var result = Parsing.parse(source.text(), rule);
        for (var e : result.errors()) {
            int start = source.offset(e.line(), e.column());
            diagnostics.error(Code.SYNTAX, new Span(source, start, Math.min(start + 1, source.length())), e.message());
        }
        if (!result.ok()) return null;
        return build.apply(new AstBuilder(source, diagnostics), result.tree());
    }
}
