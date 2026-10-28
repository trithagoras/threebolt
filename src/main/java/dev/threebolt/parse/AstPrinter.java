package dev.threebolt.parse;

import dev.threebolt.ast.Attribute;
import dev.threebolt.ast.Body;
import dev.threebolt.ast.CompilationUnit;
import dev.threebolt.ast.Dispatch;
import dev.threebolt.ast.Expr;
import dev.threebolt.ast.Item;
import dev.threebolt.ast.Member;
import dev.threebolt.ast.Name;
import dev.threebolt.ast.Param;
import dev.threebolt.ast.Pattern;
import dev.threebolt.ast.Stmt;
import dev.threebolt.ast.TypeExpr;
import dev.threebolt.ast.TypeParam;
import dev.threebolt.ast.Visibility;
import java.util.List;
import java.util.function.Consumer;

/**
 * Prints an AST as source that parses back to the same AST (up to spans). Comments and layout
 * are not preserved. Parentheses are inserted where the dropped ones mattered:
 * <ul>
 *   <li>precedence and associativity (§7.2);
 *   <li>initializers directly in an `if`/`while`/`for`/`match` head (§6.3);
 *   <li>a `<` comparison in a comma-separated list, which could otherwise be read as generic
 *       arguments together with a later `>` (§7.5: {@code f((a < b), c > (d))});
 *   <li>a cast followed by `|` or `<`, which would extend its type ({@code (x as T) | y});
 *   <li>an expression starting with `{` (or `match`) where `{` would start a block (§6.1, §7.1).
 * </ul>
 */
public final class AstPrinter {
    // Precedence levels (higher binds tighter); binary operators carry theirs (Expr.BinaryOp).
    private static final int LAMBDA = 1;
    private static final int CONDITIONAL = 2;
    private static final int COALESCE = 3;
    private static final int COMPARISON = 6;
    private static final int RANGE = 7;
    private static final int CAST = 14;
    private static final int PREFIX = 15;
    private static final int POSTFIX = 16;
    private static final int PRIMARY = 17;

    private StringBuilder out = new StringBuilder();
    private int indent;
    /** Printing an element of a comma-separated list, outside any further brackets. */
    private boolean inList;
    /** Printing an `if`/`while`/`for`/`match` head, outside any brackets. */
    private boolean inHead;

    private AstPrinter() {}

    public static String print(CompilationUnit unit) {
        var p = new AstPrinter();
        Item previous = null;
        for (Item item : unit.items()) {
            if (previous != null) p.out.append(previous instanceof Item.Use && item instanceof Item.Use ? "\n" : "\n\n");
            p.item(item);
            previous = item;
        }
        if (!unit.items().isEmpty()) p.out.append('\n');
        return p.out.toString();
    }

    public static String printMembers(List<Member> members) {
        var p = new AstPrinter();
        for (Member m : members) {
            p.member(m);
            p.out.append('\n');
        }
        return p.out.toString();
    }

    public static String printStmts(List<Stmt> stmts) {
        var p = new AstPrinter();
        for (int i = 0; i < stmts.size(); i++) {
            p.stmt(stmts.get(i), i + 1 < stmts.size() ? stmts.get(i + 1) : null);
            p.out.append('\n');
        }
        return p.out.toString();
    }

    public static String print(Expr e) {
        var p = new AstPrinter();
        p.expr(e, 0, false);
        return p.out.toString();
    }

    public static String print(TypeExpr t) {
        var p = new AstPrinter();
        p.type(t);
        return p.out.toString();
    }

    public static String print(Pattern pattern) {
        var p = new AstPrinter();
        p.pattern(pattern);
        return p.out.toString();
    }

    // ---- Items and members ----

    private void item(Item item) {
        attributes(item.attributes());
        visibility(item.visibility());
        switch (item) {
            case Item.Use u -> {
                out.append("use ");
                names(u.path(), "::");
                if (u.alias() != null) out.append(" as ").append(u.alias().text());
                if (u.isGroup()) {
                    out.append("::{");
                    commaSeparated(u.group(), n -> {
                        out.append(n.name().text());
                        if (n.alias() != null) out.append(" as ").append(n.alias().text());
                    });
                    out.append('}');
                }
                if (u.glob()) out.append("::*");
                out.append(';');
            }
            case Item.Fn f -> fn(f);
            case Item.Struct s -> {
                out.append("struct ").append(s.name().text());
                typeParams(s.typeParams());
                bases(s.bases(), ", ");
                if (s.members().isEmpty()) out.append(';');
                else memberBlock(s.members());
            }
            case Item.Class c -> {
                if (c.isAbstract()) out.append("abstract ");
                out.append("class ").append(c.name().text());
                typeParams(c.typeParams());
                bases(c.bases(), ", ");
                memberBlock(c.members());
            }
            case Item.Trait t -> {
                out.append("trait ").append(t.name().text());
                typeParams(t.typeParams());
                bases(t.supertraits(), " + ");
                memberBlock(t.members());
            }
            case Item.Impl i -> {
                out.append("impl");
                typeParams(i.typeParams());
                out.append(' ');
                type(i.trait());
                out.append(" for ");
                type(i.target());
                memberBlock(i.members());
            }
            case Item.Enum e -> {
                out.append("enum ").append(e.name().text());
                if (e.backing() != null) {
                    out.append(" : ");
                    type(e.backing());
                }
                if (e.members().isEmpty()) {
                    out.append(" {}");
                    return;
                }
                out.append(" {");
                indent++;
                for (var m : e.members()) {
                    newline();
                    out.append(m.name().text());
                    if (m.value() != null) {
                        out.append(" = ");
                        expr(m.value(), 0, false);
                    }
                    out.append(',');
                }
                indent--;
                newline();
                out.append('}');
            }
            case Item.TypeAlias t -> {
                out.append("type ").append(t.name().text());
                typeParams(t.typeParams());
                out.append(" = ");
                type(t.type());
                out.append(';');
            }
            case Item.Const c -> {
                out.append("const ").append(c.name().text()).append(": ");
                type(c.type());
                out.append(" = ");
                expr(c.value(), 0, false);
                out.append(';');
            }
            case Item.Static s -> staticDecl(s);
            case Item.Extern x -> {
                out.append("extern ");
                quote(x.abi(), '"', out);
                if (x.fns().isEmpty()) {
                    out.append(" {}");
                    return;
                }
                out.append(" {");
                indent++;
                for (var f : x.fns()) {
                    newline();
                    attributes(f.attributes());
                    visibility(f.visibility());
                    fn(f);
                }
                indent--;
                newline();
                out.append('}');
            }
        }
    }

    private void member(Member m) {
        attributes(m.attributes());
        visibility(m.visibility());
        switch (m) {
            case Member.Field f -> {
                out.append("let ").append(f.name().text()).append(": ");
                type(f.type());
                if (f.value() != null) {
                    out.append(" = ");
                    expr(f.value(), 0, false);
                }
                out.append(';');
            }
            case Item.Static s -> staticDecl(s);
            case Item.Fn f -> fn(f);
        }
    }

    private void memberBlock(List<Member> members) {
        if (members.isEmpty()) {
            out.append(" {}");
            return;
        }
        out.append(" {");
        indent++;
        for (Member m : members) {
            newline();
            member(m);
        }
        indent--;
        newline();
        out.append('}');
    }

    private void attributes(List<Attribute> attributes) {
        for (Attribute a : attributes) {
            out.append('@').append(a.name().text());
            if (!a.args().isEmpty()) {
                out.append('(');
                commaSeparated(a.args(), l -> expr(l, 0, false));
                out.append(')');
            }
            newline();
        }
    }

    private void visibility(Visibility v) {
        switch (v) {
            case PUB -> out.append("pub ");
            case INTERNAL -> out.append("internal ");
            case PROT -> out.append("prot ");
            case PRIVATE -> {}
        }
    }

    private void fn(Item.Fn f) {
        if (f.dispatch() != Dispatch.NONE) out.append(f.dispatch().name().toLowerCase()).append(' ');
        if (f.isUnsafe()) out.append("unsafe ");
        if (f.isGetter()) out.append("get ");
        out.append("fn ").append(f.name().text());
        typeParams(f.typeParams());
        out.append('(');
        commaSeparated(f.params(), this::param);
        out.append(')');
        if (f.returnType() != null) {
            out.append(" -> ");
            type(f.returnType());
        }
        switch (f.body()) {
            case null -> out.append(';');
            case Stmt.Block b -> {
                out.append(' ');
                block(b);
            }
            case Stmt.Assign a -> {
                out.append(" => ");
                assign(a);
                out.append(';');
            }
            // After a function's `=>`, `{` is an initializer, so no wrapping is needed (§17.12).
            case Expr e -> {
                out.append(" => ");
                expr(e, 0, false);
                out.append(';');
            }
        }
    }

    private void param(Param p) {
        switch (p) {
            case Param.Receiver r -> out.append(r.isMutable() ? "mut self" : "self");
            case Param.Named n -> {
                if (n.isMutable()) out.append("mut ");
                out.append(n.name().text()).append(": ");
                type(n.type());
                if (n.defaultValue() != null) {
                    out.append(" = ");
                    expr(n.defaultValue(), 0, false);
                }
            }
            case Param.Variadic v -> out.append("...");
        }
    }

    private void staticDecl(Item.Static s) {
        out.append(s.isMutable() ? "static let mut " : "static let ").append(s.name().text()).append(": ");
        type(s.type());
        out.append(" = ");
        expr(s.value(), 0, false);
        out.append(';');
    }

    private void typeParams(List<TypeParam> params) {
        if (params.isEmpty()) return;
        out.append('<');
        commaSeparated(params, p -> {
            out.append(p.name().text());
            if (!p.bounds().isEmpty()) {
                out.append(": ");
                separated(p.bounds(), " + ", this::type);
            }
        });
        out.append('>');
    }

    private void bases(List<TypeExpr.Named> bases, String separator) {
        if (bases.isEmpty()) return;
        out.append(" : ");
        separated(bases, separator, this::type);
    }

    // ---- Statements ----

    private void block(Stmt.Block b) {
        if (b.stmts().isEmpty()) {
            out.append("{}");
            return;
        }
        boolean list = inList, head = inHead;
        inList = inHead = false;
        out.append('{');
        indent++;
        var stmts = b.stmts();
        for (int i = 0; i < stmts.size(); i++) {
            newline();
            stmt(stmts.get(i), i + 1 < stmts.size() ? stmts.get(i + 1) : null);
        }
        indent--;
        newline();
        out.append('}');
        inList = list;
        inHead = head;
    }

    /** {@code next} is the following statement in the same block, if any. */
    private void stmt(Stmt s, Stmt next) {
        switch (s) {
            case Stmt.Let l -> {
                out.append("let ");
                pattern(l.pattern());
                if (l.type() != null) {
                    out.append(": ");
                    type(l.type());
                }
                out.append(" = ");
                expr(l.value(), 0, false);
                out.append(';');
            }
            case Stmt.Block b -> block(b);
            case Stmt.If i -> ifStmt(i);
            case Stmt.While w -> {
                out.append("while ");
                binder(w.binder());
                head(w.condition());
                out.append(' ');
                block(w.body());
            }
            case Stmt.Loop l -> {
                out.append("loop ");
                block(l.body());
            }
            case Stmt.For f -> {
                out.append("for ");
                pattern(f.pattern());
                out.append(" in ");
                head(f.iterable());
                out.append(' ');
                block(f.body());
            }
            case Stmt.ExprStmt e when e.expr() instanceof Expr.Match m -> {
                expr(m, 0, false);
                // A match statement needs no `;` unless the next statement could continue it.
                if ((next instanceof Stmt.ExprStmt || next instanceof Stmt.Assign)
                        && startsLikeContinuation(render(() -> stmt(next, null)))) {
                    out.append(';');
                }
            }
            case Stmt.ExprStmt e -> {
                statementExpr(e.expr());
                out.append(';');
            }
            case Stmt.Break b -> out.append("break;");
            case Stmt.Continue c -> out.append("continue;");
            case Stmt.Return r -> {
                out.append("return");
                if (r.value() != null) {
                    out.append(' ');
                    expr(r.value(), 0, false);
                }
                out.append(';');
            }
            case Stmt.Defer d -> {
                out.append("defer ");
                if (d.body() instanceof Stmt.Block b) {
                    block(b);
                } else {
                    notBrace((Expr) d.body(), false);
                    out.append(';');
                }
            }
            case Stmt.Unsafe u -> {
                out.append("unsafe ");
                block(u.body());
            }
            case Stmt.Assign a -> {
                assign(a);
                out.append(';');
            }
        }
    }

    private static boolean startsLikeContinuation(String text) {
        return text.startsWith("(") || text.startsWith("[") || text.startsWith("-");
    }

    private void ifStmt(Stmt.If i) {
        out.append("if ");
        binder(i.binder());
        head(i.condition());
        out.append(' ');
        block(i.then());
        switch (i.otherwise()) {
            case null -> {}
            case Stmt.If elseIf -> {
                out.append(" else ");
                ifStmt(elseIf);
            }
            case Stmt.Block b -> {
                out.append(" else ");
                block(b);
            }
            default -> throw new IllegalStateException("else branch must be a block or if: " + i.otherwise());
        }
    }

    private void binder(Stmt.Binder b) {
        if (b == null) return;
        out.append(b.isMutable() ? "let mut " : "let ").append(b.name().text()).append(" = ");
    }

    private void head(Expr e) {
        boolean list = inList, head = inHead;
        inList = false;
        inHead = true;
        expr(e, 0, false);
        inList = list;
        inHead = head;
    }

    private void assign(Stmt.Assign a) {
        if (a.target() == null) {
            out.append('_');
        } else {
            String target = render(() -> expr(a.target(), POSTFIX, false));
            out.append(startsStatementAmbiguously(target) ? "(" + target + ")" : target);
        }
        out.append(' ').append(a.op().symbol()).append(' ');
        expr(a.value(), 0, false);
    }

    /** An expression in statement position, where a leading `{` is a block and a leading `match` a match statement. */
    private void statementExpr(Expr e) {
        String text = render(() -> expr(e, 0, false));
        out.append(startsStatementAmbiguously(text) ? "(" + text + ")" : text);
    }

    private static boolean startsStatementAmbiguously(String text) {
        return text.startsWith("{") || text.matches("(?s)match\\b.*");
    }

    /** An expression (or assignment) where a leading `{` would start a block: lambda bodies, match arms, `defer`. */
    private void notBrace(Body body, boolean comma) {
        String text = render(() -> {
            if (body instanceof Stmt.Assign a) assign(a);
            else expr((Expr) body, 0, false);
        });
        out.append(text.startsWith("{") ? "(" + text + ")" : text);
        if (comma) out.append(',');
    }

    // ---- Expressions ----

    private static int precedence(Expr e) {
        return switch (e) {
            case Expr.Lambda l -> LAMBDA;
            case Expr.Unsafe u -> LAMBDA;
            case Expr.Conditional c -> CONDITIONAL;
            case Expr.Binary b -> b.op().precedence();
            case Expr.Is i -> COMPARISON;
            case Expr.IsPattern i -> COMPARISON;
            case Expr.Cast c -> CAST;
            case Expr.Unary u -> PREFIX;
            case Expr.Member m -> POSTFIX;
            case Expr.Call c -> POSTFIX;
            case Expr.Index i -> POSTFIX;
            case Expr.Slice sl -> POSTFIX;
            case Expr.Propagate p -> POSTFIX;
            default -> PRIMARY;
        };
    }

    /**
     * Prints {@code e} where the grammar requires precedence {@code min} or tighter.
     * {@code typeFollows}: the next token is `|` or `<`, which would extend a trailing cast type.
     */
    private void expr(Expr e, int min, boolean typeFollows) {
        boolean parens = precedence(e) < min
                || (inHead && e instanceof Expr.Init)
                || (inHead && e instanceof Expr.IsPattern ip && ip.pattern() instanceof Pattern.Struct)
                || (inList && e instanceof Expr.Binary b && b.op() == Expr.BinaryOp.LT)
                || (typeFollows && e instanceof Expr.Cast);
        if (!parens) {
            exprNoParens(e, typeFollows);
            return;
        }
        out.append('(');
        nested(() -> exprNoParens(e, false));
        out.append(')');
    }

    private void exprNoParens(Expr e, boolean typeFollows) {
        switch (e) {
            case Expr.Literal l -> literal(l);
            case Expr.Unit u -> out.append("()");
            case Expr.FString f -> fstring(f);
            case Expr.Path p -> path(p);
            case Expr.Self s -> out.append("self");
            case Expr.Super s -> out.append("super");
            case Expr.Member m -> {
                // `1.0` would lex as a float.
                if (m.receiver() instanceof Expr.IntLit && Character.isDigit(m.name().text().charAt(0))) {
                    out.append('(');
                    nested(() -> expr(m.receiver(), 0, false));
                    out.append(')');
                } else {
                    expr(m.receiver(), POSTFIX, false);
                }
                out.append('.').append(m.name().text());
                typeArgs(m.typeArgs());
            }
            case Expr.Call c -> {
                expr(c.callee(), POSTFIX, false);
                out.append('(');
                list(c.args(), a -> {
                    if (a.name() != null) out.append(a.name().text()).append(": ");
                    expr(a.value(), 0, false);
                });
                out.append(')');
            }
            case Expr.Index i -> {
                expr(i.target(), POSTFIX, false);
                out.append('[');
                nested(() -> expr(i.index(), 0, false));
                out.append(']');
            }
            case Expr.Slice sl -> {
                expr(sl.target(), POSTFIX, false);
                out.append('[');
                int bound = Expr.BinaryOp.RANGE.precedence() + 1;
                nested(() -> {
                    if (sl.low() != null) expr(sl.low(), bound, false);
                    out.append(sl.isInclusive() ? "..=" : "..");
                    if (sl.high() != null) expr(sl.high(), bound, false);
                });
                out.append(']');
            }
            case Expr.Propagate p -> {
                expr(p.operand(), POSTFIX, false);
                out.append('?');
            }
            case Expr.Unary u -> {
                out.append(u.op().symbol());
                expr(u.operand(), PREFIX, typeFollows);
            }
            case Expr.Cast c -> {
                expr(c.operand(), CAST, false);
                out.append(c.isChecked() ? " as? " : " as ");
                type(c.type());
            }
            case Expr.Binary b -> binary(b, typeFollows);
            case Expr.Is i -> {
                expr(i.operand(), RANGE, false);
                out.append(" is ");
                type(i.type());
            }
            case Expr.IsPattern i -> {
                expr(i.operand(), RANGE, false);
                out.append(" is ");
                isPattern(i.pattern());
            }
            case Expr.Conditional c -> {
                expr(c.then(), COALESCE, false);
                out.append(" if ");
                expr(c.condition(), COALESCE, false);
                out.append(" else ");
                expr(c.otherwise(), 0, typeFollows);
            }
            case Expr.Lambda l -> lambda(l, typeFollows);
            case Expr.Unsafe u -> {
                out.append("unsafe => ");
                expr(u.operand(), 0, typeFollows);
            }
            case Expr.Match m -> match(m);
            case Expr.Init i -> init(i);
            case Expr.ListLit l -> {
                out.append('[');
                list(l.elements(), x -> expr(x, 0, false));
                out.append(']');
            }
            case Expr.RepeatLit r -> {
                out.append('[');
                nested(() -> {
                    expr(r.value(), 0, false);
                    out.append("; ");
                    expr(r.count(), 0, false);
                });
                out.append(']');
            }
            case Expr.MapLit m -> {
                if (m.entries().isEmpty()) {
                    out.append("[:]");
                    return;
                }
                out.append('[');
                list(m.entries(), entry -> {
                    expr(entry.key(), 0, false);
                    out.append(": ");
                    expr(entry.value(), 0, false);
                });
                out.append(']');
            }
            case Expr.Tuple t -> {
                out.append('(');
                list(t.elements(), x -> expr(x, 0, false));
                if (t.elements().size() == 1) out.append(',');
                out.append(')');
            }
            case Expr.Typeof t -> {
                out.append("typeof(");
                type(t.type());
                out.append(')');
            }
            case Expr.Nameof n -> {
                out.append("nameof(");
                type(n.type());
                out.append(')');
            }
        }
    }

    private void binary(Expr.Binary b, boolean typeFollows) {
        var op = b.op();
        int p = op.precedence();
        int left, right;
        if (op == Expr.BinaryOp.COALESCE) {             // right-associative
            left = p + 1;
            right = p;
        } else if (p == COMPARISON || p == RANGE) {     // non-associative
            left = right = p + 1;
        } else {
            left = p;
            right = p + 1;
        }
        boolean extendsType = op == Expr.BinaryOp.BIT_OR || op == Expr.BinaryOp.LT;
        expr(b.left(), left, extendsType);
        out.append(p == RANGE ? op.symbol() : " " + op.symbol() + " ");
        expr(b.right(), right, typeFollows);
    }

    private void lambda(Expr.Lambda l, boolean typeFollows) {
        var params = l.params();
        if (params.size() == 1 && params.getFirst().type() == null
                && params.getFirst().pattern() instanceof Pattern.Binding b && b.isType() == null) {
            lambdaParam(params.getFirst());
        } else {
            out.append('(');
            commaSeparated(params, this::lambdaParam);
            out.append(')');
        }
        out.append(" => ");
        switch (l.body()) {
            case Stmt.Block b -> block(b);
            case Stmt.Assign a -> notBrace(a, false);
            case Expr e -> {
                String text = render(() -> expr(e, 0, typeFollows));
                out.append(text.startsWith("{") ? "(" + text + ")" : text);
            }
        }
    }

    private void lambdaParam(Expr.LambdaParam p) {
        pattern(p.pattern());
        if (p.type() != null) {
            out.append(": ");
            type(p.type());
        }
    }

    private void match(Expr.Match m) {
        out.append("match ");
        head(m.scrutinee());
        if (m.arms().isEmpty()) {
            out.append(" {}");
            return;
        }
        out.append(" {");
        boolean list = inList, head = inHead;
        inList = inHead = false;
        indent++;
        for (var arm : m.arms()) {
            newline();
            pattern(arm.pattern());
            if (arm.guard() != null) {
                out.append(" if ");
                expr(arm.guard(), COALESCE, false);
            }
            out.append(" => ");
            if (arm.body() instanceof Stmt.Block b) block(b);
            else notBrace(arm.body(), true);
        }
        indent--;
        newline();
        out.append('}');
        inList = list;
        inHead = head;
    }

    private void init(Expr.Init i) {
        if (i.type() != null) {
            path(i.type());
            out.append(' ');
        }
        if (i.fields().isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{ ");
        list(i.fields(), f -> {
            switch (f) {
                case Expr.FieldInit fi when isShorthand(fi) -> out.append(fi.name().text());
                case Expr.FieldInit fi -> {
                    out.append(fi.name().text()).append(": ");
                    expr(fi.value(), 0, false);
                }
                case Expr.Spread s -> {
                    out.append("..");
                    expr(s.value(), 0, false);
                }
            }
        });
        out.append(" }");
    }

    private static boolean isShorthand(Expr.FieldInit f) {
        return f.value() instanceof Expr.Path p && p.segments().size() == 1
                && p.segments().getFirst().typeArgs().isEmpty()
                && p.segments().getFirst().name().text().equals(f.name().text());
    }

    private void path(Expr.Path p) {
        separated(p.segments(), "::", s -> {
            out.append(s.name().text());
            typeArgs(s.typeArgs());
        });
    }

    private void typeArgs(List<TypeExpr> args) {
        if (args.isEmpty()) return;
        out.append('<');
        nested(() -> commaSeparated(args, this::type));
        out.append('>');
    }

    private void fstring(Expr.FString f) {
        out.append("f\"");
        for (var part : f.parts()) {
            switch (part) {
                case Expr.Text t -> t.text().codePoints().forEach(cp -> {
                    if (cp == '{') out.append("{{");
                    else if (cp == '}') out.append("}}");
                    else escape(cp, '"', out);
                });
                case Expr.Interpolation i -> {
                    // `{{` would be an escaped brace.
                    String text = render(() -> nested(() -> expr(i.expr(), 0, false)));
                    out.append(text.startsWith("{") ? "{ " : "{").append(text).append('}');
                }
            }
        }
        out.append('"');
    }

    private void literal(Expr.Literal l) {
        switch (l) {
            case Expr.IntLit i -> {
                out.append(i.value());
                if (i.suffix() != null) out.append('_').append(i.suffix());
            }
            case Expr.FloatLit f -> {
                out.append(f.digits());
                if (f.suffix() != null) out.append('_').append(f.suffix());
            }
            case Expr.StrLit s -> quote(s.value(), '"', out);
            case Expr.CharLit c -> {
                out.append('\'');
                escape(c.codePoint(), '\'', out);
                out.append('\'');
            }
            case Expr.BoolLit b -> out.append(b.value());
            case Expr.NullLit n -> out.append("null");
        }
    }

    private static void quote(String s, char quote, StringBuilder out) {
        out.append(quote);
        s.codePoints().forEach(cp -> escape(cp, quote, out));
        out.append(quote);
    }

    private static void escape(int cp, char quote, StringBuilder out) {
        switch (cp) {
            case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");
            case '\t' -> out.append("\\t");
            case '\0' -> out.append("\\0");
            case '\\' -> out.append("\\\\");
            default -> {
                if (cp == quote) out.append('\\').appendCodePoint(cp);
                else if (Character.isISOControl(cp)) out.append(String.format("\\u{%X}", cp));
                else out.appendCodePoint(cp);
            }
        }
    }

    // ---- Types ----

    private void type(TypeExpr t) {
        switch (t) {
            case TypeExpr.Named n -> {
                names(n.path(), "::");
                typeArgs(n.typeArgs());
            }
            case TypeExpr.Literal l -> literal(l.value());
            case TypeExpr.Unit u -> out.append("()");
            case TypeExpr.Tuple tuple -> {
                out.append('(');
                commaSeparated(tuple.elements(), this::type);
                if (tuple.elements().size() == 1) out.append(',');
                out.append(')');
            }
            case TypeExpr.ListType l -> {
                out.append('[');
                type(l.element());
                out.append(']');
            }
            case TypeExpr.ArrayType a -> {
                out.append('[');
                type(a.element());
                out.append("; ");
                nested(() -> expr(a.size(), 0, false));
                out.append(']');
            }
            case TypeExpr.FnType f -> {
                out.append(f.isExtern() ? "extern fn(" : "fn(");
                commaSeparated(f.params(), this::type);
                out.append(')');
                if (f.returnType() != null) {
                    out.append(" -> ");
                    type(f.returnType());
                }
            }
            case TypeExpr.Never n -> out.append('!');
            case TypeExpr.Union u -> {
                var members = u.members();
                for (int i = 0; i < members.size(); i++) {
                    if (i > 0) out.append(" | ");
                    var m = members.get(i);
                    // A fn type's return type extends over a following `|` (§17.13).
                    boolean parens = m instanceof TypeExpr.Union
                            || (m instanceof TypeExpr.FnType f && f.returnType() != null && i + 1 < members.size());
                    if (parens) out.append('(');
                    type(m);
                    if (parens) out.append(')');
                }
            }
        }
    }

    // ---- Patterns ----

    /**
     * A pattern after `is` (§17.18). Only `[`, `(`, `Name {` and ranges read as patterns there, so
     * any other pattern is wrapped in parentheses.
     */
    private void isPattern(Pattern p) {
        boolean direct = p instanceof Pattern.ListPattern || p instanceof Pattern.Tuple || p instanceof Pattern.Unit
                || p instanceof Pattern.Struct || p instanceof Pattern.Range;
        if (!direct) out.append('(');
        pattern(p);
        if (!direct) out.append(')');
    }

    private void pattern(Pattern p) {
        switch (p) {
            case Pattern.Wildcard w -> out.append('_');
            case Pattern.Binding b -> {
                if (b.isMutable()) out.append("mut ");
                out.append(b.name().text());
                if (b.isType() != null) {
                    out.append(" is ");
                    type(b.isType());
                }
            }
            case Pattern.Is i -> {
                out.append("is ");
                type(i.type());
            }
            case Pattern.Literal l -> literal(l.value());
            case Pattern.Range r -> {
                literal(r.low());
                out.append(r.isInclusive() ? "..=" : "..");
                literal(r.high());
            }
            case Pattern.Unit u -> out.append("()");
            case Pattern.Tuple t -> {
                out.append('(');
                commaSeparated(t.elements(), this::pattern);
                if (t.elements().size() == 1) out.append(',');
                out.append(')');
            }
            case Pattern.Or or -> {
                var alternatives = or.alternatives();
                for (int i = 0; i < alternatives.size(); i++) {
                    if (i > 0) out.append(" | ");
                    var a = alternatives.get(i);
                    // `x is A | y` would read `A | y` as a union type.
                    boolean parens = a instanceof Pattern.Or
                            || (i + 1 < alternatives.size()
                                && (a instanceof Pattern.Is || (a instanceof Pattern.Binding b && b.isType() != null)));
                    if (parens) out.append('(');
                    pattern(a);
                    if (parens) out.append(')');
                }
            }
            case Pattern.ListPattern l -> {
                out.append('[');
                commaSeparated(l.elements(), this::pattern);
                out.append(']');
            }
            case Pattern.Rest r -> {
                out.append("..");
                if (r.name() != null) out.append(r.name().text());
            }
            case Pattern.Struct s -> {
                path(s.type());
                if (s.fields().isEmpty() && !s.hasRest()) {
                    out.append(" {}");
                    return;
                }
                out.append(" { ");
                commaSeparated(s.fields(), f -> {
                    if (f.pattern() instanceof Pattern.Binding b && b.isType() == null
                            && b.name().text().equals(f.field().text())) {
                        pattern(b);
                    } else {
                        out.append(f.field().text()).append(": ");
                        pattern(f.pattern());
                    }
                });
                if (s.hasRest()) out.append(s.fields().isEmpty() ? ".." : ", ..");
                out.append(" }");
            }
            case Pattern.Path path -> path(path.path());
        }
    }

    // ---- Helpers ----

    private void newline() {
        out.append('\n').append("    ".repeat(indent));
    }

    private void names(List<Name> names, String separator) {
        separated(names, separator, n -> out.append(n.text()));
    }

    private <T> void commaSeparated(List<T> items, Consumer<T> print) {
        separated(items, ", ", print);
    }

    private <T> void separated(List<T> items, String separator, Consumer<T> print) {
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) out.append(separator);
            print.accept(items.get(i));
        }
    }

    /** Elements of a comma-separated list in brackets: see {@link #inList}. */
    private <T> void list(List<T> items, Consumer<T> print) {
        boolean list = inList, head = inHead;
        inList = true;
        inHead = false;
        commaSeparated(items, print);
        inList = list;
        inHead = head;
    }

    /** Inside brackets that hold a single expression: neither list nor head rules apply. */
    private void nested(Runnable r) {
        boolean list = inList, head = inHead;
        inList = inHead = false;
        r.run();
        inList = list;
        inHead = head;
    }

    /** Runs {@code r} against a fresh buffer at the current indentation and returns what it printed. */
    private String render(Runnable r) {
        var saved = out;
        out = new StringBuilder();
        try {
            r.run();
            return out.toString();
        } finally {
            out = saved;
        }
    }
}
