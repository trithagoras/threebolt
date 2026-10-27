package dev.threebolt.parse;

import static dev.threebolt.parser.ThreeboltParser.*;

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
import dev.threebolt.diag.Code;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Source;
import dev.threebolt.diag.Span;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

/**
 * Builds the AST from an error-free parse tree. Purely structural, except that literal values
 * are decoded here (escapes, the scalar range of unicode escapes, integer radix and suffix), since the lexer
 * only checks their shape. Literal errors are reported and the literal is kept with a
 * placeholder value so the rest of the tree still builds.
 */
public final class AstBuilder {
    private static final java.util.regex.Pattern INT_SUFFIX =
            java.util.regex.Pattern.compile("_(i8|i16|i32|i64|isize|u8|u16|u32|u64|usize)$");
    private static final java.util.regex.Pattern FLOAT_SUFFIX = java.util.regex.Pattern.compile("_(f32|f64)$");
    private static final java.util.regex.Pattern TUPLE_INDEX = java.util.regex.Pattern.compile("0|[1-9][0-9]*");

    private final Source source;
    private final Diagnostics diagnostics;

    public AstBuilder(Source source, Diagnostics diagnostics) {
        this.source = source;
        this.diagnostics = diagnostics;
    }

    // ---- Entry points ----

    public CompilationUnit compilationUnit(CompilationUnitContext ctx) {
        return new CompilationUnit(map(ctx.item(), this::item), span(ctx));
    }

    public List<Member> memberList(MemberListContext ctx) {
        return map(ctx.member(), this::member);
    }

    public List<Stmt> stmtList(StmtListContext ctx) {
        return map(ctx.stmt(), this::stmt);
    }

    public Expr exprOnly(ExprOnlyContext ctx) {
        return expr(ctx.expr());
    }

    public TypeExpr typeOnly(TypeOnlyContext ctx) {
        return type(ctx.type());
    }

    // ---- Items ----

    private Item item(ItemContext ctx) {
        var attrs = map(ctx.attribute(), this::attribute);
        var vis = visibility(ctx.visibility());
        var k = ctx.itemKind();
        var span = span(ctx);
        if (k.useDecl() != null) return use(attrs, vis, k.useDecl(), span);
        if (k.fnDecl() != null) {
            var f = k.fnDecl();
            return fn(attrs, vis, Dispatch.NONE, f.UNSAFE() != null, false, f.fnSignature(), fnBody(f.fnBody()), span);
        }
        if (k.structDecl() != null) {
            var s = k.structDecl();
            return new Item.Struct(attrs, vis, name(s.IDENTIFIER()), typeParams(s.typeParams()), bases(s.baseList()),
                    s.memberBlock() == null ? List.of() : members(s.memberBlock()), span);
        }
        if (k.classDecl() != null) {
            var c = k.classDecl();
            return new Item.Class(attrs, vis, c.ABSTRACT() != null, name(c.IDENTIFIER()), typeParams(c.typeParams()),
                    bases(c.baseList()), members(c.memberBlock()), span);
        }
        if (k.traitDecl() != null) {
            var t = k.traitDecl();
            return new Item.Trait(attrs, vis, name(t.IDENTIFIER()), typeParams(t.typeParams()),
                    map(t.typePath(), this::typePath), members(t.memberBlock()), span);
        }
        if (k.implDecl() != null) {
            var i = k.implDecl();
            return new Item.Impl(attrs, vis, typeParams(i.typeParams()), typePath(i.typePath()), type(i.type()),
                    members(i.memberBlock()), span);
        }
        if (k.enumDecl() != null) {
            var e = k.enumDecl();
            var members = map(e.enumMember(), m -> new Item.EnumMember(name(m.IDENTIFIER()),
                    m.expr() == null ? null : expr(m.expr()), span(m)));
            return new Item.Enum(attrs, vis, name(e.IDENTIFIER()), e.type() == null ? null : type(e.type()), members, span);
        }
        if (k.typeAliasDecl() != null) {
            var t = k.typeAliasDecl();
            return new Item.TypeAlias(attrs, vis, name(t.IDENTIFIER()), typeParams(t.typeParams()), type(t.type()), span);
        }
        if (k.constDecl() != null) {
            var c = k.constDecl();
            return new Item.Const(attrs, vis, name(c.IDENTIFIER()), type(c.type()), expr(c.expr()), span);
        }
        if (k.staticDecl() != null) return staticDecl(attrs, vis, k.staticDecl(), span);
        var x = k.externBlock();
        var fns = map(x.externFn(), f -> fn(map(f.attribute(), this::attribute), visibility(f.visibility()),
                Dispatch.NONE, false, false, f.fnSignature(), null, span(f)));
        return new Item.Extern(attrs, vis, decodeString(x.STRING_LITERAL().getSymbol()), fns, span);
    }

    private Item.Use use(List<Attribute> attrs, Visibility vis, UseDeclContext ctx, Span span) {
        var ids = ctx.IDENTIFIER();
        List<Item.UseName> group = null;
        Name alias = null;
        if (ctx.useGroup() != null) {
            group = map(ctx.useGroup().useName(), u -> new Item.UseName(name(u.IDENTIFIER(0)),
                    u.IDENTIFIER().size() > 1 ? name(u.IDENTIFIER(1)) : null, span(u)));
        } else if (ctx.AS() != null) {
            alias = name(ids.getLast());
            ids = ids.subList(0, ids.size() - 1);
        }
        return new Item.Use(attrs, vis, map(ids, this::name), alias, group, ctx.STAR() != null, span);
    }

    private Attribute attribute(AttributeContext ctx) {
        return new Attribute(name(ctx.IDENTIFIER()), map(ctx.literal(), this::literal), span(ctx));
    }

    private static Visibility visibility(VisibilityContext ctx) {
        if (ctx == null) return Visibility.PRIVATE;
        return ctx.PUB() != null ? Visibility.PUB : Visibility.PROT;
    }

    private Item.Fn fn(List<Attribute> attrs, Visibility vis, Dispatch dispatch, boolean isUnsafe, boolean isGetter,
                       FnSignatureContext sig, Body body, Span span) {
        List<Param> params = sig.paramList() == null ? List.of() : map(sig.paramList().param(), this::param);
        return new Item.Fn(attrs, vis, dispatch, isUnsafe, isGetter, name(sig.IDENTIFIER()), typeParams(sig.typeParams()),
                params, sig.type() == null ? null : type(sig.type()), body, span);
    }

    private Param param(ParamContext ctx) {
        return switch (ctx) {
            case SelfParamContext c -> new Param.Receiver(c.MUT() != null, span(c));
            case NamedParamContext c -> new Param.Named(c.MUT() != null, name(c.IDENTIFIER()), type(c.type()),
                    c.expr() == null ? null : expr(c.expr()), span(c));
            case VariadicParamContext c -> new Param.Variadic(span(c));
            default -> throw unexpected(ctx);
        };
    }

    private Body fnBody(FnBodyContext ctx) {
        return switch (ctx) {
            case BlockBodyContext c -> block(c.block());
            case ExprBodyContext c -> assignOrExpr(c.assignOrExpr());
            case NoBodyContext c -> null;
            default -> throw unexpected(ctx);
        };
    }

    private Item.Static staticDecl(List<Attribute> attrs, Visibility vis, StaticDeclContext c, Span span) {
        return new Item.Static(attrs, vis, c.MUT() != null, name(c.IDENTIFIER()), type(c.type()), expr(c.expr()), span);
    }

    private List<TypeExpr.Named> bases(BaseListContext ctx) {
        return ctx == null ? List.of() : map(ctx.typePath(), this::typePath);
    }

    private List<Member> members(MemberBlockContext ctx) {
        return map(ctx.member(), this::member);
    }

    private Member member(MemberContext ctx) {
        var attrs = map(ctx.attribute(), this::attribute);
        var vis = visibility(ctx.visibility());
        var span = span(ctx);
        return switch (ctx.memberKind()) {
            case FieldMemberContext c -> new Member.Field(attrs, vis, name(c.IDENTIFIER()), type(c.type()),
                    c.expr() == null ? null : expr(c.expr()), span);
            case StaticMemberContext c -> staticDecl(attrs, vis, c.staticDecl(), span);
            case FnMemberContext c -> fn(attrs, vis, dispatch(c.dispatchModifier()), c.UNSAFE() != null,
                    c.getKw() != null, c.fnSignature(), fnBody(c.fnBody()), span);
            default -> throw unexpected(ctx);
        };
    }

    private static Dispatch dispatch(DispatchModifierContext ctx) {
        if (ctx == null) return Dispatch.NONE;
        if (ctx.ABSTRACT() != null) return Dispatch.ABSTRACT;
        return ctx.VIRTUAL() != null ? Dispatch.VIRTUAL : Dispatch.OVERRIDE;
    }

    private List<TypeParam> typeParams(TypeParamsContext ctx) {
        if (ctx == null) return List.of();
        return map(ctx.typeParam(), p -> new TypeParam(name(p.IDENTIFIER()), map(p.typePath(), this::typePath), span(p)));
    }

    // ---- Types ----

    private TypeExpr type(TypeContext ctx) {
        var members = map(ctx.nonUnionType(), this::nonUnionType);
        return members.size() == 1 ? members.getFirst() : new TypeExpr.Union(members, span(ctx));
    }

    private TypeExpr nonUnionType(NonUnionTypeContext ctx) {
        var span = span(ctx);
        return switch (ctx) {
            case NamedTypeContext c -> typePath(c.typePath());
            case LiteralTypeRefContext c -> {
                var l = c.literalType();
                yield new TypeExpr.Literal(signedLiteral(l, l.MINUS()), span);
            }
            case UnitTypeContext c -> new TypeExpr.Unit(span);
            case GroupTypeContext c -> type(c.type());
            case TupleTypeContext c -> new TypeExpr.Tuple(map(c.type(), this::type), span);
            case ListTypeContext c -> new TypeExpr.ListType(type(c.type()), span);
            case ArrayTypeContext c -> new TypeExpr.ArrayType(type(c.type()), expr(c.expr()), span);
            case FnTypeContext c -> {
                var types = c.type();
                TypeExpr ret = null;
                if (c.ARROW() != null) {
                    ret = type(types.getLast());
                    types = types.subList(0, types.size() - 1);
                }
                yield new TypeExpr.FnType(c.EXTERN() != null, map(types, this::type), ret, span);
            }
            case NeverTypeContext c -> new TypeExpr.Never(span);
            default -> throw unexpected(ctx);
        };
    }

    private TypeExpr.Named typePath(TypePathContext ctx) {
        var args = ctx.typeArgs() == null ? List.<TypeExpr>of() : typeArgs(ctx.typeArgs());
        return new TypeExpr.Named(map(ctx.typeName(), n -> name(n.getStart())), args, span(ctx));
    }

    private List<TypeExpr> typeArgs(TypeArgsContext ctx) {
        return ctx == null ? List.of() : map(ctx.type(), this::type);
    }

    // ---- Statements ----

    private Stmt.Block block(BlockContext ctx) {
        return new Stmt.Block(map(ctx.stmt(), this::stmt), span(ctx));
    }

    private Stmt stmt(StmtContext ctx) {
        var span = span(ctx);
        return switch (ctx) {
            case LetStmtContext c -> new Stmt.Let(pattern(c.pattern()), c.type() == null ? null : type(c.type()),
                    expr(c.expr()), span);
            case BlockStmtContext c -> block(c.block());
            case IfStmtAltContext c -> ifStmt(c.ifStmt());
            case WhileStmtContext c -> new Stmt.While(binder(c.LET(), c.MUT(), c.IDENTIFIER()),
                    expr(c.noStructExpr().expr()), block(c.block()), span);
            case LoopStmtContext c -> new Stmt.Loop(block(c.block()), span);
            case ForStmtContext c -> new Stmt.For(pattern(c.pattern()), expr(c.noStructExpr().expr()), block(c.block()), span);
            case MatchStmtContext c -> new Stmt.ExprStmt(match(c.matchExpr()), span);
            case BreakStmtContext c -> new Stmt.Break(span);
            case ContinueStmtContext c -> new Stmt.Continue(span);
            case ReturnStmtContext c -> new Stmt.Return(c.expr() == null ? null : expr(c.expr()), span);
            case DeferStmtContext c -> new Stmt.Defer(c.block() != null ? block(c.block()) : expr(c.expr()), span);
            case UnsafeStmtContext c -> new Stmt.Unsafe(block(c.block()), span);
            case AssignStmtContext c -> new Stmt.Assign(assignTarget(c.assignTarget()), assignOp(c.assignOp()),
                    expr(c.expr()), span);
            case ExprStmtContext c -> new Stmt.ExprStmt(expr(c.expr()), span);
            default -> throw unexpected(ctx);
        };
    }

    private Stmt.If ifStmt(IfStmtContext ctx) {
        Stmt otherwise = null;
        if (ctx.ifStmt() != null) otherwise = ifStmt(ctx.ifStmt());
        else if (ctx.block().size() > 1) otherwise = block(ctx.block(1));
        return new Stmt.If(binder(ctx.LET(), ctx.MUT(), ctx.IDENTIFIER()), expr(ctx.noStructExpr().expr()),
                block(ctx.block(0)), otherwise, span(ctx));
    }

    private Stmt.Binder binder(TerminalNode let, TerminalNode mut, TerminalNode id) {
        if (let == null) return null;
        return new Stmt.Binder(mut != null, name(id), span(let.getSymbol(), id.getSymbol()));
    }

    private Expr assignTarget(AssignTargetContext ctx) {
        return ctx.UNDERSCORE() != null ? null : postfix(ctx.postfixExpr());
    }

    private static Stmt.AssignOp assignOp(AssignOpContext ctx) {
        return switch (ctx.getStart().getType()) {
            case ASSIGN -> Stmt.AssignOp.ASSIGN;
            case PLUS_EQ -> Stmt.AssignOp.ADD;
            case MINUS_EQ -> Stmt.AssignOp.SUB;
            case STAR_EQ -> Stmt.AssignOp.MUL;
            case SLASH_EQ -> Stmt.AssignOp.DIV;
            case PERCENT_EQ -> Stmt.AssignOp.REM;
            case AMP_EQ -> Stmt.AssignOp.AND;
            case PIPE_EQ -> Stmt.AssignOp.OR;
            case CARET_EQ -> Stmt.AssignOp.XOR;
            case SHL_EQ -> Stmt.AssignOp.SHL;
            case GT -> Stmt.AssignOp.SHR;
            default -> throw unexpected(ctx);
        };
    }

    private Body assignOrExpr(AssignOrExprContext ctx) {
        var value = expr(ctx.expr());
        if (ctx.assignOp() == null) return value;
        return new Stmt.Assign(assignTarget(ctx.assignTarget()), assignOp(ctx.assignOp()), value, span(ctx));
    }

    // ---- Expressions ----

    private Expr expr(ExprContext ctx) {
        return switch (ctx) {
            case LambdaExprContext c -> lambda(c.lambda());
            case UnsafeExprContext c -> new Expr.Unsafe(expr(c.expr()), span(c));
            case ConditionalExprContext c -> c.IF() == null
                    ? coalesce(c.coalesceExpr(0))
                    : new Expr.Conditional(coalesce(c.coalesceExpr(0)), coalesce(c.coalesceExpr(1)), expr(c.expr()), span(c));
            default -> throw unexpected(ctx);
        };
    }

    private Expr lambda(LambdaContext ctx) {
        var p = ctx.lambdaParams();
        List<Expr.LambdaParam> params = p.LPAREN() == null
                ? List.of(new Expr.LambdaParam(p.MUT() != null, name(p.IDENTIFIER()), null, span(p)))
                : map(p.lambdaParam(), lp -> new Expr.LambdaParam(lp.MUT() != null, name(lp.IDENTIFIER()),
                        lp.type() == null ? null : type(lp.type()), span(lp)));
        var b = ctx.lambdaBody();
        Body body = b.block() != null ? block(b.block()) : assignOrExpr(b.assignOrExpr());
        return new Expr.Lambda(params, body, span(ctx));
    }

    private Expr coalesce(CoalesceExprContext ctx) {
        var left = or(ctx.orExpr());
        if (ctx.QQ() == null) return left;
        return new Expr.Binary(Expr.BinaryOp.COALESCE, left, coalesce(ctx.coalesceExpr()), span(ctx));
    }

    private Expr or(OrExprContext ctx) {
        if (ctx.orExpr() == null) return and(ctx.andExpr());
        return new Expr.Binary(Expr.BinaryOp.OR, or(ctx.orExpr()), and(ctx.andExpr()), span(ctx));
    }

    private Expr and(AndExprContext ctx) {
        if (ctx.andExpr() == null) return comparison(ctx.comparisonExpr());
        return new Expr.Binary(Expr.BinaryOp.AND, and(ctx.andExpr()), comparison(ctx.comparisonExpr()), span(ctx));
    }

    private Expr comparison(ComparisonExprContext ctx) {
        var left = range(ctx.rangeExpr(0));
        if (ctx.IS() != null) {
            return switch (ctx.isTarget()) {
                case PatternIsTargetContext c -> new Expr.IsPattern(left, patternAtom(c.patternAtom()), span(ctx));
                case TypeIsTargetContext c -> new Expr.Is(left, type(c.type()), span(ctx));
                default -> throw unexpected(ctx.isTarget());
            };
        }
        if (ctx.IN() != null) return new Expr.Binary(Expr.BinaryOp.IN, left, range(ctx.rangeExpr(1)), span(ctx));
        if (ctx.compareOp() == null) return left;
        var op = ctx.compareOp();
        var binOp = switch (op.getStart().getType()) {
            case EQ_EQ -> Expr.BinaryOp.EQ;
            case NOT_EQ -> Expr.BinaryOp.NE;
            case LT -> Expr.BinaryOp.LT;
            case LE -> Expr.BinaryOp.LE;
            case GT -> op.ASSIGN() != null ? Expr.BinaryOp.GE : Expr.BinaryOp.GT;
            default -> throw unexpected(op);
        };
        return new Expr.Binary(binOp, left, range(ctx.rangeExpr(1)), span(ctx));
    }

    private Expr range(RangeExprContext ctx) {
        var left = bitOr(ctx.bitOrExpr(0));
        if (ctx.bitOrExpr().size() == 1) return left;
        var op = ctx.DOTDOT() != null ? Expr.BinaryOp.RANGE : Expr.BinaryOp.RANGE_INCLUSIVE;
        return new Expr.Binary(op, left, bitOr(ctx.bitOrExpr(1)), span(ctx));
    }

    private Expr bitOr(BitOrExprContext ctx) {
        if (ctx.bitOrExpr() == null) return bitXor(ctx.bitXorExpr());
        return new Expr.Binary(Expr.BinaryOp.BIT_OR, bitOr(ctx.bitOrExpr()), bitXor(ctx.bitXorExpr()), span(ctx));
    }

    private Expr bitXor(BitXorExprContext ctx) {
        if (ctx.bitXorExpr() == null) return bitAnd(ctx.bitAndExpr());
        return new Expr.Binary(Expr.BinaryOp.BIT_XOR, bitXor(ctx.bitXorExpr()), bitAnd(ctx.bitAndExpr()), span(ctx));
    }

    private Expr bitAnd(BitAndExprContext ctx) {
        if (ctx.bitAndExpr() == null) return shift(ctx.shiftExpr());
        return new Expr.Binary(Expr.BinaryOp.BIT_AND, bitAnd(ctx.bitAndExpr()), shift(ctx.shiftExpr()), span(ctx));
    }

    private Expr shift(ShiftExprContext ctx) {
        if (ctx.shiftExpr() == null) return additive(ctx.additiveExpr());
        var op = ctx.shiftOp().SHL() != null ? Expr.BinaryOp.SHL : Expr.BinaryOp.SHR;
        return new Expr.Binary(op, shift(ctx.shiftExpr()), additive(ctx.additiveExpr()), span(ctx));
    }

    private Expr additive(AdditiveExprContext ctx) {
        if (ctx.additiveExpr() == null) return multiplicative(ctx.multiplicativeExpr());
        var op = ctx.PLUS() != null ? Expr.BinaryOp.ADD : Expr.BinaryOp.SUB;
        return new Expr.Binary(op, additive(ctx.additiveExpr()), multiplicative(ctx.multiplicativeExpr()), span(ctx));
    }

    private Expr multiplicative(MultiplicativeExprContext ctx) {
        if (ctx.multiplicativeExpr() == null) return cast(ctx.castExpr());
        var op = ctx.STAR() != null ? Expr.BinaryOp.MUL : ctx.SLASH() != null ? Expr.BinaryOp.DIV : Expr.BinaryOp.REM;
        return new Expr.Binary(op, multiplicative(ctx.multiplicativeExpr()), cast(ctx.castExpr()), span(ctx));
    }

    private Expr cast(CastExprContext ctx) {
        if (ctx.castExpr() == null) return unary(ctx.unaryExpr());
        var cast = new Expr.Cast(cast(ctx.castExpr()), type(ctx.type()), ctx.AS_Q() != null,
                span(ctx.getStart(), ctx.type().getStop()));
        // `x as T?` is `(x as T)?` (§17.6).
        return ctx.QUESTION() == null ? cast : new Expr.Propagate(cast, span(ctx));
    }

    private Expr unary(UnaryExprContext ctx) {
        if (ctx.postfixExpr() != null) return postfix(ctx.postfixExpr());
        var op = switch (ctx.getStart().getType()) {
            case MINUS -> Expr.UnaryOp.NEG;
            case BANG -> Expr.UnaryOp.NOT;
            case TILDE -> Expr.UnaryOp.BIT_NOT;
            default -> throw unexpected(ctx);
        };
        return new Expr.Unary(op, unary(ctx.unaryExpr()), span(ctx));
    }

    private Expr postfix(PostfixExprContext ctx) {
        var e = primary(ctx.primary());
        Token start = ctx.getStart();
        for (var op : ctx.postfixOp()) {
            var span = span(start, op.getStop());
            e = switch (op) {
                case MemberOpContext c -> new Expr.Member(e, memberName(c.memberName()), typeArgs(c.typeArgs()), span);
                case CallOpContext c -> new Expr.Call(e, map(c.callArgs().arg(), this::arg), span);
                case IndexOpContext c -> new Expr.Index(e, expr(c.nested().expr()), span);
                case SliceOpContext c -> {
                    var b = c.sliceBounds();
                    var bound = b.bitOrExpr() == null ? null : bitOr(b.bitOrExpr());
                    int first = b.getStart().getType();
                    boolean lowGiven = first != DOTDOT && first != DOTDOT_EQ;
                    yield new Expr.Slice(e, lowGiven ? bound : null, lowGiven ? null : bound,
                            b.DOTDOT_EQ() != null, span);
                }
                case PropagateOpContext c -> new Expr.Propagate(e, span);
                default -> throw unexpected(op);
            };
        }
        return e;
    }

    private Name memberName(MemberNameContext ctx) {
        var name = name(ctx.getStart());
        if (ctx.INT_LITERAL() != null && !TUPLE_INDEX.matcher(name.text()).matches()) {
            diagnostics.error(Code.INVALID_TUPLE_INDEX, name.span(),
                    "invalid tuple index '" + name.text() + "' (write a plain decimal like .0)");
        }
        return name;
    }

    private Expr.Arg arg(ArgContext ctx) {
        return new Expr.Arg(ctx.IDENTIFIER() == null ? null : name(ctx.IDENTIFIER()), expr(ctx.expr()), span(ctx));
    }

    private Expr primary(PrimaryContext ctx) {
        var span = span(ctx);
        return switch (ctx) {
            case LiteralExprContext c -> literal(c.literal());
            case FstringExprContext c -> fstring(c.fstring());
            case SelfExprContext c -> new Expr.Self(span);
            case SuperExprContext c -> new Expr.Super(span);
            case NamedInitExprContext c -> new Expr.Init(path(c.path()), initFields(c.initializer()), span);
            case TargetInitExprContext c -> new Expr.Init(null, initFields(c.initializer()), span);
            case CollectionExprContext c -> collection(c.collectionLiteral());
            case PathExprContext c -> path(c.path());
            case UnitExprContext c -> new Expr.Unit(span);
            case ParenExprContext c -> expr(c.nested().expr());
            case TupleExprContext c -> new Expr.Tuple(map(c.nested(), n -> expr(n.expr())), span);
            case MatchExprAltContext c -> match(c.matchExpr());
            case TypeofExprContext c -> new Expr.Typeof(type(c.type()), span);
            case NameofExprContext c -> new Expr.Nameof(type(c.type()), span);
            default -> throw unexpected(ctx);
        };
    }

    private Expr.Path path(PathContext ctx) {
        return path(ctx.pathSegment(), ctx.pathMember(), span(ctx));
    }

    private Expr.Path path(PathSegmentContext first, List<PathMemberContext> rest, Span span) {
        var segments = new ArrayList<Expr.PathSegment>();
        segments.add(new Expr.PathSegment(name(first.getStart()), typeArgs(first.typeArgs()), span(first)));
        for (var m : rest) {
            segments.add(new Expr.PathSegment(name(m.getStart()), typeArgs(m.typeArgs()), span(m)));
        }
        return new Expr.Path(List.copyOf(segments), span);
    }

    private List<Expr.InitField> initFields(InitializerContext ctx) {
        return map(ctx.initField(), f -> switch (f) {
            case NamedFieldContext c -> new Expr.FieldInit(name(c.IDENTIFIER()), expr(c.expr()), span(c));
            case ShorthandFieldContext c -> {
                var name = name(c.IDENTIFIER());
                var path = new Expr.Path(List.of(new Expr.PathSegment(name, List.of(), name.span())), name.span());
                yield new Expr.FieldInit(name, path, name.span());
            }
            case SpreadFieldContext c -> new Expr.Spread(expr(c.expr()), span(c));
            default -> throw unexpected(f);
        });
    }

    private Expr collection(CollectionLiteralContext ctx) {
        var span = span(ctx);
        return switch (ctx) {
            case EmptyCollectionContext c -> new Expr.ListLit(List.of(), span);
            case EmptyMapContext c -> new Expr.MapLit(List.of(), span);
            case RepeatCollectionContext c -> new Expr.RepeatLit(expr(c.expr(0)), expr(c.expr(1)), span);
            case MapCollectionContext c -> new Expr.MapLit(map(c.mapEntry(), e ->
                    new Expr.MapEntry(expr(e.expr(0)), expr(e.expr(1)), span(e))), span);
            case ListCollectionContext c -> new Expr.ListLit(map(c.expr(), this::expr), span);
            default -> throw unexpected(ctx);
        };
    }

    private Expr.Match match(MatchExprContext ctx) {
        var arms = map(ctx.matchBody().matchArm(), a -> {
            Body body = a.block() != null ? block(a.block()) : assignOrExpr(a.assignOrExpr());
            return new Expr.MatchArm(pattern(a.pattern()), a.coalesceExpr() == null ? null : coalesce(a.coalesceExpr()),
                    body, span(a));
        });
        return new Expr.Match(expr(ctx.noStructExpr().expr()), arms, span(ctx));
    }

    private Expr fstring(FstringContext ctx) {
        boolean triple = ctx.FSTRING_START().getText().startsWith("f\"\"\"");
        var parts = new ArrayList<Expr.FStringPart>();
        var text = new StringBuilder();
        Token textStart = null;
        Token textEnd = null;
        for (var part : ctx.fstringPart()) {
            if (part.FSTRING_TEXT() != null) {
                Token t = part.FSTRING_TEXT().getSymbol();
                if (textStart == null) textStart = t;
                textEnd = t;
                text.append(decode(t, 0, t.getText().length(), triple, true));
                continue;
            }
            if (textStart != null) {
                parts.add(new Expr.Text(text.toString(), span(textStart, textEnd)));
                text.setLength(0);
                textStart = null;
            }
            parts.add(new Expr.Interpolation(expr(part.expr()), span(part)));
        }
        if (textStart != null) parts.add(new Expr.Text(text.toString(), span(textStart, textEnd)));
        return new Expr.FString(List.copyOf(parts), span(ctx));
    }

    // ---- Patterns ----

    private Pattern pattern(PatternContext ctx) {
        var alternatives = map(ctx.patternAtom(), this::patternAtom);
        return alternatives.size() == 1 ? alternatives.getFirst() : new Pattern.Or(alternatives, span(ctx));
    }

    private Pattern patternAtom(PatternAtomContext ctx) {
        var span = span(ctx);
        return switch (ctx) {
            case WildcardPatternContext c -> new Pattern.Wildcard(span);
            case BindingPatternContext c -> new Pattern.Binding(c.MUT() != null, name(c.IDENTIFIER()),
                    c.type() == null ? null : type(c.type()), span);
            case IsPatternContext c -> new Pattern.Is(type(c.type()), span);
            case LiteralOrRangePatternContext c -> {
                var low = signedLiteral(c.literalPattern(0), c.literalPattern(0).MINUS());
                if (c.literalPattern().size() == 1) yield new Pattern.Literal(low, span);
                var high = signedLiteral(c.literalPattern(1), c.literalPattern(1).MINUS());
                yield new Pattern.Range(low, high, c.DOTDOT_EQ() != null, span);
            }
            case UnitPatternContext c -> new Pattern.Unit(span);
            case GroupPatternContext c -> pattern(c.pattern());
            case TuplePatternContext c -> new Pattern.Tuple(map(c.pattern(), this::pattern), span);
            case ListPatternContext c -> new Pattern.ListPattern(map(c.listPatternElem(), e -> e.pattern() != null
                    ? pattern(e.pattern())
                    : new Pattern.Rest(e.IDENTIFIER() == null ? null : name(e.IDENTIFIER()), span(e))), span);
            case StructPatternContext c -> {
                var fields = map(c.fieldPattern(), f -> {
                    var field = name(f.IDENTIFIER());
                    var p = f.pattern() != null
                            ? pattern(f.pattern())
                            : new Pattern.Binding(f.MUT() != null, field, null, span(f));
                    return new Pattern.FieldPattern(field, p, span(f));
                });
                yield new Pattern.Struct(path(c.path()), fields, c.DOTDOT() != null, span);
            }
            case PathPatternContext c -> new Pattern.Path(path(c.pathSegment(), c.pathMember(), span), span);
            default -> throw unexpected(ctx);
        };
    }

    // ---- Literals ----

    private Expr.Literal literal(LiteralContext ctx) {
        return literalToken(ctx.getStart(), false);
    }

    /** A literal pattern/type: an optional `-` followed by a literal token, folded into one literal. */
    private Expr.Literal signedLiteral(ParserRuleContext ctx, TerminalNode minus) {
        Token t = minus == null ? ctx.getStart() : ctx.getStop();
        var lit = literalToken(t, minus != null);
        if (minus == null) return lit;
        var span = span(ctx);
        return switch (lit) {
            case Expr.IntLit i -> new Expr.IntLit(i.value(), i.suffix(), span);
            case Expr.FloatLit f -> new Expr.FloatLit(f.digits(), f.suffix(), span);
            default -> throw unexpected(ctx);
        };
    }

    private Expr.Literal literalToken(Token t, boolean negate) {
        var span = span(t, t);
        String text = t.getText();
        return switch (t.getType()) {
            case INT_LITERAL -> {
                String suffix = null;
                var m = INT_SUFFIX.matcher(text);
                if (m.find()) {
                    suffix = m.group(1);
                    text = text.substring(0, m.start());
                }
                text = text.replace("_", "");
                int radix = 10;
                if (text.length() > 1 && text.charAt(0) == '0' && "xob".indexOf(text.charAt(1)) >= 0) {
                    radix = switch (text.charAt(1)) {
                        case 'x' -> 16;
                        case 'o' -> 8;
                        default -> 2;
                    };
                    text = text.substring(2);
                }
                var value = new BigInteger(text, radix);
                yield new Expr.IntLit(negate ? value.negate() : value, suffix, span);
            }
            case FLOAT_LITERAL -> {
                String suffix = null;
                var m = FLOAT_SUFFIX.matcher(text);
                if (m.find()) {
                    suffix = m.group(1);
                    text = text.substring(0, m.start());
                }
                text = text.replace("_", "");
                yield new Expr.FloatLit(negate ? "-" + text : text, suffix, span);
            }
            case STRING_LITERAL -> new Expr.StrLit(decodeString(t), span);
            case TRIPLE_STRING_LITERAL -> new Expr.StrLit(decodeString(t), span);
            case CHAR_LITERAL -> {
                String value = decode(t, 1, text.length() - 1, false, false);
                yield new Expr.CharLit(value.codePointAt(0), span);
            }
            case TRUE -> new Expr.BoolLit(true, span);
            case FALSE -> new Expr.BoolLit(false, span);
            case NULL -> new Expr.NullLit(span);
            default -> throw new IllegalStateException("not a literal: " + t);
        };
    }

    private String decodeString(Token t) {
        String text = t.getText();
        boolean triple = t.getType() == TRIPLE_STRING_LITERAL;
        int quotes = triple ? 3 : 1;
        return decode(t, quotes, text.length() - quotes, triple, false);
    }

    /**
     * Decodes {@code text[from, to)} of {@code t}: escapes, CRLF → LF in multi-line literals, and
     * for f-string text {@code {{ }}} → {@code { }}. The lexer has checked escape syntax; only
     * unicode escapes outside the scalar range are reported here.
     */
    private String decode(Token t, int from, int to, boolean multiLine, boolean fstringText) {
        String text = t.getText();
        var out = new StringBuilder();
        int i = from;
        while (i < to) {
            char c = text.charAt(i);
            if (fstringText && (c == '{' || c == '}')) {
                out.append(c);
                i += 2;
            } else if (multiLine && c == '\r' && i + 1 < to && text.charAt(i + 1) == '\n') {
                out.append('\n');
                i += 2;
            } else if (c != '\\') {
                out.append(c);
                i++;
            } else {
                char e = text.charAt(i + 1);
                if (e != 'u') {
                    out.append(switch (e) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case '0' -> '\0';
                        default -> e; // \\ \" \'
                    });
                    i += 2;
                    continue;
                }
                int close = text.indexOf('}', i);
                int cp = Integer.parseInt(text.substring(i + 3, close), 16);
                if (cp > Character.MAX_CODE_POINT || (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE)) {
                    int start = t.getStartIndex() + text.codePointCount(0, i);
                    int end = t.getStartIndex() + text.codePointCount(0, close + 1);
                    diagnostics.error(Code.INVALID_LITERAL, new Span(source, start, end),
                            "'" + text.substring(i, close + 1) + "' is not a Unicode scalar value");
                    cp = 0xFFFD;
                }
                out.appendCodePoint(cp);
                i = close + 1;
            }
        }
        return out.toString();
    }

    // ---- Helpers ----

    private Name name(TerminalNode node) {
        return name(node.getSymbol());
    }

    private Name name(Token t) {
        return new Name(t.getText(), span(t, t));
    }

    private Span span(ParserRuleContext ctx) {
        return span(ctx.getStart(), ctx.getStop());
    }

    /** From the start of {@code first} to the end of {@code last}; empty if {@code last} precedes {@code first}. */
    private Span span(Token first, Token last) {
        int start = first.getStartIndex();
        int end = last == null ? start : Math.max(start, last.getStopIndex() + 1);
        return new Span(source, start, end);
    }

    private static <C extends ParseTree, R> List<R> map(List<C> contexts, java.util.function.Function<C, R> f) {
        var out = new ArrayList<R>(contexts.size());
        for (C c : contexts) out.add(f.apply(c));
        return List.copyOf(out);
    }

    private static IllegalStateException unexpected(ParseTree ctx) {
        return new IllegalStateException("unexpected parse tree node " + ctx.getClass().getSimpleName()
                + " (the AST builder only accepts error-free trees)");
    }
}
