package dev.threebolt.types;

import dev.threebolt.ast.Expr;
import dev.threebolt.ast.Expr.BinaryOp;
import dev.threebolt.ast.Name;
import dev.threebolt.ast.Pattern;
import dev.threebolt.ast.Stmt;
import dev.threebolt.ast.TypeExpr;
import dev.threebolt.ast.Visibility;
import dev.threebolt.diag.Code;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Span;
import dev.threebolt.interp.Ops;
import dev.threebolt.interp.Values;
import dev.threebolt.ir.Ir;
import dev.threebolt.ir.Local;
import dev.threebolt.resolve.Module;
import dev.threebolt.types.Checker.Cond;
import dev.threebolt.types.Checker.PlaceInfo;
import dev.threebolt.types.Checker.Typed;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Expression typing (§7) and lowering. Calls are in {@link Calls}, `match` in {@link Patterns}. */
final class Exprs {
    private final Checker c;
    private final TypeSystem ts;
    private final Diagnostics diag;
    private final TypeResolver types;
    private final Names names;

    Exprs(Checker c) {
        this.c = c;
        this.ts = c.ts;
        this.diag = c.diag;
        this.types = c.types;
        this.names = c.names;
    }

    static Typed error(Ir.Expr ir) {
        return new Typed(ir, Type.ERROR);
    }

    static Typed error() {
        return new Typed(new Ir.Lit(Values.Unit.UNIT), Type.ERROR);
    }

    Typed expr(Expr e, Type expected) {
        return switch (e) {
            case Expr.IntLit i -> intLit(i.value(), i.suffix(), expected, i.span());
            case Expr.FloatLit f -> floatLit(f.digits(), f.suffix(), expected, f.span(), false);
            case Expr.StrLit s -> new Typed(new Ir.Lit(Values.Str.of(s.value())), new Type.Lit(Prim.STR, s.value()));
            case Expr.CharLit ch -> new Typed(new Ir.Lit(new Values.Char(ch.codePoint())), new Type.Lit(Prim.CHAR, ch.codePoint()));
            case Expr.BoolLit b -> new Typed(new Ir.Lit(b.value()), new Type.Lit(Prim.BOOL, b.value()));
            case Expr.NullLit n -> new Typed(new Ir.Lit(null), Type.NULL);
            case Expr.Unit u -> new Typed(new Ir.Lit(Values.Unit.UNIT), Type.UNIT);
            case Expr.FString f -> fstring(f);
            case Expr.Path p -> path(p, expected);
            case Expr.Self s -> self(s.span());
            case Expr.Super s -> {
                diag.error(Code.BAD_SUPER, s.span(), "`super` can only be used to call a base class method: `super.m()`");
                yield error();
            }
            case Expr.Member m -> member(m);
            case Expr.Call call -> c.calls.call(call, expected);
            case Expr.Index i -> {
                var r = recv(i.target());
                if (isRange(i.index()) && sliceable(r)) {
                    var b = (Expr.Binary) i.index();
                    yield slice(r, b.left(), b.right(), b.op() == BinaryOp.RANGE_INCLUSIVE, i.span());
                }
                var p = indexPlace(i, r);
                yield p == null ? error() : new Typed(new Ir.Read(p.place()), p.type());
            }
            case Expr.Slice sl -> {
                var r = recv(sl.target());
                if (sliceable(r) || r.value().type() == Type.ERROR) {
                    yield slice(r, sl.low(), sl.high(), sl.isInclusive(), sl.span());
                }
                diag.error(Code.BAD_OPERAND, sl.target().span(), "'" + TypeSystem.widen(r.value().type())
                        + "' cannot be sliced; open-ended ranges only slice lists, arrays and strings (§7.6)");
                yield error();
            }
            case Expr.Propagate p -> propagate(p);
            case Expr.Unary u -> unary(u, expected);
            case Expr.Cast cast -> cast(cast);
            case Expr.Binary b -> binaryExpr(b, expected);
            case Expr.Is is -> {
                var cond = condition(is);
                yield new Typed(cond.ir(), Prim.BOOL);
            }
            case Expr.IsPattern is -> {
                var cond = condition(is);
                yield new Typed(cond.ir(), Prim.BOOL);
            }
            case Expr.Conditional cond -> conditional(cond, expected);
            case Expr.Lambda l -> lambda(l, expected);
            case Expr.Unsafe u -> {
                c.ctx.unsafeDepth++;
                var t = expr(u.operand(), expected);
                c.ctx.unsafeDepth--;
                yield t;
            }
            case Expr.Match m -> c.patterns.match(m, expected, false);
            case Expr.Init init -> init(init, expected);
            case Expr.ListLit l -> listLit(l, expected);
            case Expr.RepeatLit r -> repeat(r, expected);
            case Expr.MapLit m -> mapLit(m, expected);
            case Expr.ListComp l -> listComp(l, expected);
            case Expr.MapComp m -> mapComp(m, expected);
            case Expr.Tuple t -> tuple(t, expected);
            case Expr.Typeof t -> new Typed(new Ir.TypeOf(types.resolve(t.type(), c.ctx.typeScope)), typeType());
            case Expr.Nameof n -> {
                var t = types.resolve(n.type(), c.ctx.typeScope);
                Ir.Expr ir = TypeSystem.isGeneric(t) ? new Ir.NameOf(t) : new Ir.Lit(Values.Str.of(TypeSystem.name(t)));
                yield new Typed(ir, Prim.STR);
            }
        };
    }

    Type typeType() {
        return new Type.Nominal(ts.typeDecl, List.of());
    }

    // ---- Literals (§3.6) ----

    static boolean isNumericLiteral(Expr e) {
        return e instanceof Expr.IntLit || e instanceof Expr.FloatLit
                || (e instanceof Expr.Unary u && u.op() == Expr.UnaryOp.NEG && isNumericLiteral(u.operand()));
    }

    Typed intLit(BigInteger value, String suffix, Type expected, Span span) {
        if (suffix != null) {
            var p = Prim.named(suffix);
            if (p.isFloat()) return floatLit(value.toString(), suffix, expected, span, false);
            return intOf(value, p, span);
        }
        var param = numParam(expected, ts.num);
        if (param != null) {
            if (!Prim.I64.fits(value)) {
                diag.error(Code.LITERAL_OUT_OF_RANGE, span, "literal " + value + " does not fit in 'i64', which `"
                        + param + "::from_i64` takes");
                return error();
            }
            return numLiteral(param, ts.num, "from_i64", new Values.Int(Prim.I64, value.longValue()), span);
        }
        var target = integerTarget(value, expected);
        if (target != null && target.isFloat()) return floatOf(value.doubleValue(), target);
        return intOf(value, target == null ? Prim.I32 : target, span);
    }

    /** The integer or float type an unsuffixed integer literal adopts from {@code expected}. */
    private Prim integerTarget(BigInteger value, Type expected) {
        if (expected == null) return null;
        var members = TypeSystem.members(expected);
        for (Type m : members) {
            if (m instanceof Type.Lit l && l.value().equals(value) && l.base().isInteger()) return l.base();
        }
        Prim fallback = null;
        for (Type m : members) {
            var p = TypeSystem.prim(m);
            if (p == null || !p.isNumeric() || m instanceof Type.Lit) continue;
            if (members.size() == 1) return p;
            if (p == Prim.I32 && p.fits(value)) return p;
            if (fallback == null && (p.isFloat() || p.fits(value))) fallback = p;
        }
        return fallback;
    }

    private Typed intOf(BigInteger value, Prim p, Span span) {
        if (!p.fits(value)) {
            diag.error(Code.LITERAL_OUT_OF_RANGE, span, "literal " + value + " does not fit in '" + p + "' (range "
                    + p.min() + ".." + p.max() + ")");
            return new Typed(new Ir.Lit(new Values.Int(p, 0)), p);
        }
        return new Typed(new Ir.Lit(new Values.Int(p, value.longValue())), new Type.Lit(p, value));
    }

    Typed floatLit(String digits, String suffix, Type expected, Span span, boolean negate) {
        Prim p = suffix != null ? Prim.named(suffix) : null;
        if (p == null && expected != null) {
            for (Type m : TypeSystem.members(expected)) {
                var mp = TypeSystem.prim(m);
                if (mp != null && mp.isFloat()) {
                    p = mp;
                    break;
                }
            }
        }
        double v = Double.parseDouble(digits);
        var param = p == null ? numParam(expected, ts.floatTrait) : null;
        if (param != null) return numLiteral(param, ts.floatTrait, "from_f64", Ops.flt(Prim.F64, negate ? -v : v), span);
        if (p == null) p = Prim.F64;
        return floatOf(negate ? -v : v, p);
    }

    /** {@code expected} when it is a type parameter implementing {@code trait} (§10.1), else null. */
    private Type.Param numParam(Type expected, TypeInfo trait) {
        if (expected == null || !(TypeSystem.expand(expected) instanceof Type.Param p)) return null;
        return ts.implementsAs(p, trait) != null ? p : null;
    }

    /** A literal of a numeric type parameter: {@code T::from_i64(n)} or {@code T::from_f64(x)}. */
    private Typed numLiteral(Type.Param param, TypeInfo trait, String fn, Object value, Span span) {
        var from = trait.methods(fn).getFirst();
        return new Typed(new Ir.Call(from, Ir.CallKind.TRAIT_STATIC, null, null, List.of(new Ir.Lit(value)),
                new int[] {0}, List.of(), List.of(), param, span), param);
    }

    private Typed floatOf(double v, Prim p) {
        return new Typed(new Ir.Lit(Ops.flt(p, v)), p);
    }

    private Typed fstring(Expr.FString f) {
        var parts = new ArrayList<Object>();
        for (var part : f.parts()) {
            switch (part) {
                case Expr.Text t -> parts.add(t.text());
                case Expr.Interpolation i -> {
                    var t = expr(i.expr(), null);
                    if (t.type() != Type.ERROR && ts.implementsAs(t.type(), ts.display) == null) {
                        diag.error(Code.NOT_DISPLAY, i.expr().span(), "'" + t.type() + "' does not implement `Display`");
                    }
                    parts.add(t.ir());
                }
            }
        }
        return new Typed(new Ir.FString(parts, f.span()), Prim.STR);
    }

    // ---- Names and paths ----

    sealed interface Target {}

    record LocalTarget(Local local) implements Target {}

    record ValueTarget(Typed value) implements Target {}

    record StaticTarget(StaticInfo info) implements Target {}

    record FnTarget(List<Calls.Candidate> candidates, String name, List<TypeExpr> typeArgs) implements Target {}

    /** A type; {@code args} is null for a generic type named without type arguments. */
    record TypeTarget(TypeInfo decl, Type type, boolean argsGiven) implements Target {}

    record ModuleTarget(Module module) implements Target {}

    record ErrorTarget() implements Target {}

    private Typed self(Span span) {
        if (c.ctx.self == null) {
            diag.error(Code.UNRESOLVED_NAME, span, "`self` is only available in methods");
            return error();
        }
        return c.readLocal(c.ctx.self, span);
    }

    private Typed path(Expr.Path p, Type expected) {
        return switch (pathTarget(p, false)) {
            case LocalTarget(Local l) -> c.readLocal(l, p.span());
            case ValueTarget(Typed v) -> v;
            case StaticTarget(StaticInfo s) -> new Typed(new Ir.Read(new Ir.StaticPlace(s)), s.type);
            case FnTarget f -> c.calls.fnValue(f, expected, p.span());
            case TypeTarget t -> unitStruct(t, p.span());
            case ModuleTarget m -> {
                diag.error(Code.WRONG_KIND, p.span(), "expected a value, found module '" + m.module().path() + "'");
                yield error();
            }
            case ErrorTarget e -> error();
        };
    }

    /** A unit struct's name is its value: {@code Done}. */
    private Typed unitStruct(TypeTarget t, Span span) {
        if (t.decl() != null && t.decl().isStruct() && t.decl().allFields.isEmpty()
                && t.type() instanceof Type.Nominal n) {
            return new Typed(new Ir.New(t.decl(), n.args(), null, List.of(), span), n);
        }
        diag.error(Code.WRONG_KIND, span, "expected a value, found type '" + t.type() + "'");
        return error();
    }

    /**
     * What a path names (§2.2, §7.5): a local, a module-level item, or a member of a type
     * ({@code Type::f}, {@code Enum::Member}, {@code T::default}).
     */
    Target pathTarget(Expr.Path p, boolean forCall) {
        var segs = p.segments();
        var first = segs.getFirst();
        String firstName = first.name().text();
        var scope = c.ctx.typeScope;
        if (segs.size() == 1 && c.completer != null && c.completer.at(first.name())) {
            c.completer.names(first.name());
            return new ErrorTarget();
        }
        if (segs.size() == 1) {
            var local = c.lookupLocal(firstName);
            if (local != null) {
                if (!first.typeArgs().isEmpty()) {
                    // Most likely a comparison that §7.5 read as generic arguments: `f(a < b, c > (d))`.
                    diag.error(Code.WRONG_TYPE_ARGUMENTS, first.span(), "local '" + firstName
                            + "' takes no type arguments: this `<...>` was parsed as generic arguments (§7.5); "
                            + "to compare, parenthesize: `(a < b)`");
                    return new ErrorTarget();
                }
                return new LocalTarget(local);
            }
        }
        Type qualifier;
        TypeInfo qualifierDecl;
        boolean argsGiven;
        if (firstName.equals("Self") || scope.params().containsKey(firstName)) {
            qualifier = firstName.equals("Self") ? scope.self() : scope.params().get(firstName).type();
            if (!firstName.equals("Self")) c.index.ref(first.name().span(), scope.params().get(firstName), null);
            if (qualifier == null) {
                diag.error(Code.UNRESOLVED_NAME, first.span(), "`Self` is only valid inside a type or trait");
                return new ErrorTarget();
            }
            noTypeArgs(first);
            qualifierDecl = qualifier instanceof Type.Nominal n ? n.decl()
                    : qualifier instanceof Prim pr ? ts.prims.get(pr) : null;
            argsGiven = true;
            if (segs.size() == 1) return new TypeTarget(qualifierDecl, qualifier, true);
            if (segs.size() > 2) return tooLong(segs, 2);
        } else {
            var r = names.resolve(c.ctx.module, segs.stream().map(Expr.PathSegment::name).toList());
            if (r == null) return new ErrorTarget();
            var seg = segs.get(r.consumed() - 1);
            if (r.consumed() == segs.size()) return entityTarget(r.entity(), seg, p.span());
            if (r.consumed() < segs.size() - 1) return tooLong(segs, r.consumed() + 1);
            switch (r.entity()) {
                case Names.TypeEntity(TypeInfo t) -> {
                    if (t.kind() == TypeInfo.Kind.ALIAS) {
                        qualifier = types.instantiate(t, typeArgs(seg), seg.span());
                        qualifierDecl = qualifier instanceof Type.Nominal n ? n.decl()
                                : qualifier instanceof Prim pr ? ts.prims.get(pr) : null;
                        argsGiven = true;
                    } else {
                        qualifierDecl = t;
                        argsGiven = !seg.typeArgs().isEmpty() || t.typeParams.isEmpty();
                        qualifier = argsGiven ? types.instantiate(t, typeArgs(seg), seg.span())
                                : new Type.Nominal(t, t.typeParams.stream().map(TypeParamInfo::type).toList());
                    }
                }
                case Names.PrimEntity(Prim pr) -> {
                    noTypeArgs(seg);
                    qualifier = pr;
                    qualifierDecl = ts.prims.get(pr);
                    argsGiven = true;
                }
                default -> {
                    diag.error(Code.WRONG_KIND, seg.span(), "'" + seg.name().text() + "' is not a type or module");
                    return new ErrorTarget();
                }
            }
        }
        if (qualifier == Type.ERROR) return new ErrorTarget();
        if (c.completer != null && c.completer.at(segs.getLast().name())) {
            c.completer.statics(qualifier, qualifierDecl, argsGiven, segs.getLast());
            return new ErrorTarget();
        }
        return member(qualifier, qualifierDecl, argsGiven, segs.getLast(), forCall);
    }

    private Target tooLong(List<Expr.PathSegment> segs, int valid) {
        var bad = segs.get(valid);
        diag.error(Code.UNRESOLVED_NAME, bad.span(), "'" + bad.name().text() + "' cannot be reached through '"
                + segs.get(valid - 1).name().text() + "'");
        return new ErrorTarget();
    }

    List<Type> typeArgs(Expr.PathSegment seg) {
        return seg.typeArgs().stream().map(c::typeArg).toList();
    }

    private void noTypeArgs(Expr.PathSegment seg) {
        if (!seg.typeArgs().isEmpty()) {
            diag.error(Code.WRONG_TYPE_ARGUMENTS, seg.span(), "'" + seg.name().text() + "' takes no type arguments");
        }
    }

    private Target entityTarget(Names.Entity entity, Expr.PathSegment seg, Span span) {
        return switch (entity) {
            case Names.FnEntity(List<FnInfo> fns) -> new FnTarget(
                    fns.stream().map(f -> Calls.Candidate.direct(f)).toList(), seg.name().text(), seg.typeArgs());
            case Names.ConstEntity(ConstInfo k) -> {
                noTypeArgs(seg);
                yield new ValueTarget(c.consts.reference(k, span));
            }
            case Names.StaticEntity(StaticInfo s) -> {
                noTypeArgs(seg);
                yield new StaticTarget(s);
            }
            case Names.TypeEntity(TypeInfo t) -> {
                if (t.kind() == TypeInfo.Kind.ALIAS) {
                    var type = types.instantiate(t, typeArgs(seg), seg.span());
                    yield new TypeTarget(type instanceof Type.Nominal n ? n.decl() : null, type, true);
                }
                boolean given = !seg.typeArgs().isEmpty() || t.typeParams.isEmpty();
                var type = given ? types.instantiate(t, typeArgs(seg), seg.span())
                        : new Type.Nominal(t, t.typeParams.stream().map(TypeParamInfo::type).toList());
                yield type == Type.ERROR ? new ErrorTarget() : new TypeTarget(t, type, given);
            }
            case Names.PrimEntity(Prim pr) -> new TypeTarget(ts.prims.get(pr), pr, true);
            case Names.ModuleEntity(Module m) -> new ModuleTarget(m);
        };
    }

    /** {@code Type::name}: an enum member, a static, a static function, or (traits) a method. */
    private Target member(Type owner, TypeInfo decl, boolean argsGiven, Expr.PathSegment seg, boolean forCall) {
        var target = memberTarget(owner, decl, argsGiven, seg, forCall);
        if (target instanceof FnTarget f) c.index.ref(seg.name().span(), f.candidates().getFirst().fn(), null);
        return target;
    }

    Target memberTarget(Type owner, TypeInfo decl, boolean argsGiven, Expr.PathSegment seg, boolean forCall) {
        String name = seg.name().text();
        if (owner instanceof Type.Param param) {
            var cands = new ArrayList<Calls.Candidate>();
            for (var bound : param.param().bounds) {
                for (var tr : TypeSystem.selfAndSupers(bound)) {
                    for (FnInfo f : tr.decl().methods(name)) {
                        if (!f.isStatic()) continue;
                        var env = TypeSystem.bind(tr.decl().typeParams, tr.args());
                        env.put(tr.decl().selfParam, owner);
                        cands.add(new Calls.Candidate(f, Ir.CallKind.TRAIT_STATIC, env, Set.of(), owner, false));
                    }
                }
            }
            if (cands.isEmpty()) {
                diag.error(Code.NO_SUCH_MEMBER, seg.span(), "no static function '" + name + "' in the bounds of '" + owner + "'");
                return new ErrorTarget();
            }
            return new FnTarget(cands, name, seg.typeArgs());
        }
        if (decl == null) {
            diag.error(Code.NO_SUCH_MEMBER, seg.span(), "'" + owner + "' has no member '" + name + "'");
            return new ErrorTarget();
        }
        if (decl.isEnum()) {
            var values = c.consts.enumValues(decl);
            if (values.containsKey(name)) {
                noTypeArgs(seg);
                c.index.ref(seg.name().span(), new SymbolIndex.EnumMember(decl, name), null);
                return new ValueTarget(new Typed(new Ir.Lit(new Values.EnumVal(decl, values.get(name))), owner));
            }
        }
        if (decl.kind() == TypeInfo.Kind.PRIMITIVE) {
            var constant = primConstant(decl.prim, name);
            if (constant != null) return new ValueTarget(new Typed(new Ir.Lit(constant), decl.prim));
        }
        for (TypeInfo t = decl; t != null; t = t.baseDecl()) {
            var s = t.statics.get(name);
            if (s != null) {
                c.index.ref(seg.name().span(), s, null);
                checkAccess(t, s.ast.visibility(), "static '" + name + "'", seg.span());
                return new StaticTarget(s);
            }
        }
        var statics = decl.methods(name).stream().filter(FnInfo::isStatic).toList();
        if (!statics.isEmpty()) {
            Map<TypeParamInfo, Type> env = new HashMap<>();
            Set<TypeParamInfo> vars = Set.of();
            if (argsGiven && owner instanceof Type.Nominal n) env = TypeSystem.bind(decl.typeParams, n.args());
            else if (!argsGiven) vars = new HashSet<>(decl.typeParams);
            if (decl.isTrait()) {
                diag.error(Code.WRONG_KIND, seg.span(), "static trait function '" + name
                        + "' must be called through a type: `T::" + name + "()`");
                return new ErrorTarget();
            }
            var cands = new ArrayList<Calls.Candidate>();
            for (FnInfo f : statics) {
                cands.add(new Calls.Candidate(f, Ir.CallKind.DIRECT, env, vars, null, false));
            }
            return new FnTarget(cands, name, seg.typeArgs());
        }
        if (!decl.isTrait() && argsGiven) {
            // A static member of a trait the type implements, e.g. a default body: `Mod7::zero()`.
            var cands = new ArrayList<Calls.Candidate>();
            var seen = new HashSet<FnInfo>();
            for (TypeInfo t = decl; t != null; t = t.baseDecl()) {
                for (ImplInfo impl : ts.implsOf(t)) {
                    var inst = ts.implementsAs(owner, impl.trait.decl());
                    if (inst == null) continue;
                    for (var tr : TypeSystem.selfAndSupers(inst)) {
                        for (FnInfo f : tr.decl().methods(name)) {
                            if (!f.isStatic() || !seen.add(f)) continue;
                            var env = TypeSystem.bind(tr.decl().typeParams, tr.args());
                            env.put(tr.decl().selfParam, owner);
                            cands.add(new Calls.Candidate(f, Ir.CallKind.TRAIT_STATIC, env, Set.of(), owner, false));
                        }
                    }
                }
            }
            if (!cands.isEmpty()) return new FnTarget(cands, name, seg.typeArgs());
        }
        var instance = decl.methods(name);
        if (decl.isTrait() && !instance.isEmpty()) {
            // Trait::m(x, ...) calls m on x (§8.5).
            var cands = new ArrayList<Calls.Candidate>();
            for (FnInfo f : instance) cands.add(new Calls.Candidate(f, Ir.CallKind.TRAIT, Map.of(), Set.of(), null, true));
            return new FnTarget(cands, name, seg.typeArgs());
        }
        if (!instance.isEmpty()) {
            diag.error(Code.WRONG_KIND, seg.span(), "'" + name + "' is an instance method of '" + decl.name()
                    + "'; call it on a value");
        } else {
            diag.error(Code.NO_SUCH_MEMBER, seg.span(), "'" + owner + "' has no member '" + name + "'");
        }
        return new ErrorTarget();
    }

    private static Object primConstant(Prim p, String name) {
        if (p.isInteger()) {
            return switch (name) {
                case "MIN" -> new Values.Int(p, p.min().longValue());
                case "MAX" -> new Values.Int(p, p.max().longValue());
                default -> null;
            };
        }
        if (p.isFloat()) {
            boolean f32 = p == Prim.F32;
            return switch (name) {
                case "MIN" -> Ops.flt(p, f32 ? -Float.MAX_VALUE : -Double.MAX_VALUE);
                case "MAX" -> Ops.flt(p, f32 ? Float.MAX_VALUE : Double.MAX_VALUE);
                case "EPSILON" -> Ops.flt(p, f32 ? Math.ulp(1.0f) : Math.ulp(1.0));
                case "INFINITY" -> Ops.flt(p, Double.POSITIVE_INFINITY);
                case "NEG_INFINITY" -> Ops.flt(p, Double.NEGATIVE_INFINITY);
                case "NAN" -> Ops.flt(p, Double.NaN);
                default -> null;
            };
        }
        return null;
    }

    /** Member visibility (§2.3); reports and returns false when not accessible. */
    boolean checkAccess(TypeInfo owner, Visibility vis, String what, Span span) {
        return checkAccess(owner, vis, what, span, c.ctx.enclosing);
    }

    /** {@link #checkAccess} from code enclosed by {@code here}. */
    boolean checkAccess(TypeInfo owner, Visibility vis, String what, Span span, TypeInfo here) {
        return checkAccess(owner.module, owner, vis, what, span, here);
    }

    /** {@link #checkAccess} for a member declared in module {@code declaring}. */
    boolean checkAccess(Module declaring, TypeInfo owner, Visibility vis, String what, Span span, TypeInfo here) {
        if (vis == Visibility.PUB) return true;
        boolean ok = switch (vis) {
            case PRIVATE -> here == owner;
            case PROT -> here != null && here.isSubclassOf(owner);
            case INTERNAL -> declaring.samePackage(c.ctx.module);
            case PUB -> true;
        };
        if (!ok) {
            String how = switch (vis) {
                case PROT -> "protected in '" + owner.name() + "'";
                case INTERNAL -> declaring.isStd() ? "internal to the standard library" : "internal to package '" + declaring.pkg().name() + "'";
                default -> "private in '" + owner.name() + "'";
            };
            diag.error(Code.PRIVATE_MEMBER, span, what + " is " + how);
        }
        return ok;
    }

    // ---- Members (§7.5) ----

    /** A receiver: typed once, and its place when it is one. */
    record Recv(Typed value, PlaceInfo place) {}

    Recv recv(Expr e) {
        var place = isPlaceSyntax(e) ? c.place(e) : null;
        if (place != null) return new Recv(new Typed(readIr(place), TypeSystem.literalBase(place.type())), place);
        var t = expr(e, null);
        return new Recv(new Typed(t.ir(), TypeSystem.literalBase(t.type())), null);
    }

    private Ir.Expr readIr(PlaceInfo p) {
        Ir.Expr ir = new Ir.Read(p.place());
        if (p.local() != null && p.type() != p.local().type && TypeSystem.isStruct(p.type())) ir = new Ir.Unbox(ir);
        return ir;
    }

    /** Indexing by a range is not a receiver place: on a list, array or str it is a slice, a copy (§7.6). */
    private static boolean isPlaceSyntax(Expr e) {
        return e instanceof Expr.Self || e instanceof Expr.Member || (e instanceof Expr.Index i && !isRange(i.index()))
                || (e instanceof Expr.Path p && p.segments().size() == 1);
    }

    private Typed member(Expr.Member m) {
        if (m.receiver() instanceof Expr.Super) {
            diag.error(Code.BAD_SUPER, m.span(), "`super` can only be used to call a base class method");
            return error();
        }
        var r = recv(m.receiver());
        if (c.completer != null && c.completer.at(m.name())) {
            c.completer.members(r.value().type(), m.name());
            return error();
        }
        var field = field(r, m.name());
        if (field != null) return new Typed(readIr(field), field.type());
        if (TypeSystem.expand(r.value().type()) instanceof Type.Tuple && m.name().text().chars().allMatch(Character::isDigit)) {
            return error();
        }
        return getter(r, m);
    }

    /** {@code receiver.f} as a place, or null if {@code f} is not a field (a getter, perhaps). */
    PlaceInfo fieldPlace(Expr.Member m) {
        if (m.receiver() instanceof Expr.Super) return null;
        return field(recv(m.receiver()), m.name());
    }

    private PlaceInfo field(Recv r, Name name) {
        var t = TypeSystem.expand(r.value().type());
        var basePlace = r.place() == null ? null : r.place().place();
        var baseExpr = r.place() == null ? r.value().ir() : null;
        boolean mutable = r.place() != null && r.place().isMutable();
        String why = r.place() == null ? "cannot assign to a field of a temporary value"
                : r.place().immutableWhy();
        String text = name.text();
        if (t instanceof Type.Tuple tu && text.chars().allMatch(Character::isDigit)) {
            int i = Integer.parseInt(text);
            if (i >= tu.elements().size()) {
                diag.error(Code.NO_SUCH_MEMBER, name.span(), "tuple '" + t + "' has no element " + i);
                return null;
            }
            return new PlaceInfo(new Ir.FieldPlace(basePlace, baseExpr, i, text), tu.elements().get(i), mutable, why, null);
        }
        if (t instanceof Type.Nominal n && (n.decl().isStruct() || n.decl().isClass())) {
            var f = n.decl().field(text);
            if (f == null) return null;
            if (!f.owner().isStruct()) checkAccess(f.owner(), f.visibility, "field '" + text + "'", name.span());
            var base = n.decl() == f.owner() ? n : TypeSystem.baseInstance(n, f.owner());
            var type = TypeSystem.subst(f.type, TypeSystem.bind(f.owner().typeParams, base.args()));
            c.index.ref(name.span(), f, type);
            return new PlaceInfo(new Ir.FieldPlace(basePlace, baseExpr, f.slot, text), type, mutable, why, null);
        }
        return null;
    }

    private Typed getter(Recv r, Expr.Member m) {
        var t = r.value().type();
        if (t == Type.ERROR) return error();
        var cands = c.calls.methodCandidates(t, m.name().text());
        if (cands.isEmpty() && TypeSystem.expand(t) instanceof Type.Union) {
            diag.error(Code.UNION_MEMBER_ACCESS, m.name().span(), "cannot access '" + m.name().text()
                    + "' on a value of union type '" + t
                    + "': no union-safe trait that every member implements has it (§3.4); narrow it first (§9)");
            return error();
        }
        var getters = cands.stream().filter(k -> k.fn().isGetter()).toList();
        if (getters.isEmpty()) {
            if (!cands.isEmpty()) {
                diag.error(Code.WRONG_KIND, m.name().span(), "'" + m.name().text() + "' is a method; call it: `"
                        + m.name().text() + "()`, or wrap it in a lambda to use it as a value (§7.8)");
            } else {
                diag.error(Code.NO_SUCH_MEMBER, m.name().span(), "'" + t + "' has no field or getter '" + m.name().text() + "'");
            }
            return error();
        }
        var g = getters.getFirst();
        c.calls.checkMethodAccess(g.fn(), m.name().span());
        var ret = TypeSystem.subst(g.fn().ret, g.env());
        c.index.call(m.name().span(), g.fn(), g.env());
        return new Typed(new Ir.Call(g.fn(), g.kind(), r.value().ir(), null, List.of(), new int[0], List.of(), null,
                null, m.span()), ret);
    }

    // ---- Indexing and slicing (§7.6) ----

    private static boolean isRange(Expr e) {
        return e instanceof Expr.Binary b && (b.op() == BinaryOp.RANGE || b.op() == BinaryOp.RANGE_INCLUSIVE);
    }

    /** The element type of a list, array or str receiver, or null. */
    private Type sliceElement(Recv r) {
        return switch (TypeSystem.widen(TypeSystem.expand(r.value().type()))) {
            case Type.Array a -> a.element();
            case Type.Nominal n when n.decl().isList() -> n.args().getFirst();
            case Prim p when p == Prim.STR -> Prim.CHAR;
            default -> null;
        };
    }

    private boolean sliceable(Recv r) {
        return sliceElement(r) != null;
    }

    /** {@code xs[low..high]}: a copy of the selected elements as a new list, or a str of a str's. */
    private Typed slice(Recv r, Expr low, Expr high, boolean inclusive, Span span) {
        var lo = low == null ? null : c.check(low, Prim.USIZE);
        var hi = high == null ? null : c.check(high, Prim.USIZE);
        var element = sliceElement(r);
        if (element == null) return error();
        var result = element == Prim.CHAR && TypeSystem.widen(TypeSystem.expand(r.value().type())) == Prim.STR
                ? Prim.STR : ts.listOf(element);
        return new Typed(new Ir.Slice(r.value().ir(), lo, hi, inclusive, span), result);
    }

    /** {@code c[i]} as a place (§7.6); null after an error. */
    PlaceInfo indexPlace(Expr.Index i) {
        var r = recv(i.target());
        if (isRange(i.index()) && sliceable(r)) {
            diag.error(Code.NOT_A_PLACE, i.span(), "a slice is a copy, not a place: cannot assign to it (§7.6)");
            return null;
        }
        return indexPlace(i, r);
    }

    private PlaceInfo indexPlace(Expr.Index i, Recv r) {
        var t = TypeSystem.expand(r.value().type());
        if (t == Type.ERROR) {
            expr(i.index(), null);
            return null;
        }
        Type keyType;
        Type valueType;
        boolean writable;
        if (t instanceof Type.Array a) {
            keyType = Prim.USIZE;
            valueType = a.element();
            writable = r.place() != null && r.place().isMutable();
        } else if (t instanceof Type.Union) {
            diag.error(Code.UNION_MEMBER_ACCESS, i.target().span(), "cannot index a value of union type '" + t + "'");
            expr(i.index(), null);
            return null;
        } else {
            var inst = ts.implementsAs(t, ts.index);
            if (inst == null) {
                diag.error(Code.BAD_OPERAND, i.target().span(), "'" + t + "' cannot be indexed: it does not implement `Index`");
                expr(i.index(), null);
                return null;
            }
            keyType = inst.args().get(0);
            valueType = inst.args().get(1);
            // As for calling `IndexMut::set` (`mut self`): a mutable place, or a temporary reference (§5.1).
            writable = ts.implementsAs(t, ts.indexMut) != null
                    && (r.place() != null ? r.place().isMutable() : TypeSystem.isReference(t));
        }
        var index = c.check(i.index(), keyType);
        var basePlace = r.place() == null ? null : r.place().place();
        var baseExpr = r.place() == null ? r.value().ir() : null;
        String why = ts.implementsAs(t, ts.indexMut) == null && !(t instanceof Type.Array)
                ? "'" + t + "' does not implement `IndexMut`"
                : r.place() == null ? "cannot assign into a temporary value" : r.place().immutableWhy();
        return new PlaceInfo(new Ir.IndexPlace(basePlace, baseExpr, index, i.span()), valueType, writable, why, null);
    }

    // ---- `?` (§7.10) ----

    private Typed propagate(Expr.Propagate p) {
        var t = expr(p.operand(), null);
        if (t.type() == Type.ERROR) return t;
        var fail = new ArrayList<Type>();
        var rest = new ArrayList<Type>();
        // Errors when there are any, else null: with an error member, null is a value.
        var members = TypeSystem.members(t.type());
        boolean errors = members.stream().anyMatch(this::isError);
        for (Type m : members) {
            if (errors ? isError(m) : m == Type.NULL) fail.add(m);
            else rest.add(m);
        }
        if (fail.isEmpty()) {
            diag.error(Code.BAD_PROPAGATION, p.span(), "`?` needs a value that may be null or an error, found '" + t.type() + "'");
            return t;
        }
        var ctx = c.ctx;
        if (ctx.inDefer) diag.error(Code.BAD_DEFER, p.span(), "`?` cannot propagate out of a deferred block");
        if (ctx.returnType == null) {
            ctx.inferredReturns.addAll(fail);
        } else if (ctx.fn == null && ctx.depth == 0) {
            diag.error(Code.BAD_PROPAGATION, p.span(), "`?` is only valid inside a function");
        } else {
            for (Type f : fail) {
                if (!ts.assignable(f, ctx.returnType)) {
                    diag.error(Code.BAD_PROPAGATION, p.span(), "`?` would return '" + f + "', which is not assignable to the return type '"
                            + ctx.returnType + "'");
                }
            }
        }
        return new Typed(new Ir.Propagate(t.ir(), fail, p.span()), TypeSystem.union(rest));
    }

    private boolean isError(Type m) {
        return m != Type.NULL && !(m instanceof Type.Param) && ts.implementsAs(m, ts.error) != null;
    }

    // ---- Operators (§7.3) ----

    private Typed unary(Expr.Unary u, Type expected) {
        switch (u.op()) {
            case NEG -> {
                if (u.operand() instanceof Expr.IntLit i) {
                    if (i.suffix() != null && Prim.named(i.suffix()).isFloat()) {
                        return floatLit(i.value().toString(), i.suffix(), expected, u.span(), true);
                    }
                    var neg = i.value().negate();
                    if (i.suffix() != null && !Prim.named(i.suffix()).isSigned()) {
                        diag.error(Code.LITERAL_OUT_OF_RANGE, u.span(), "cannot negate an unsigned literal");
                    }
                    return intLit(neg, i.suffix(), expected, u.span());
                }
                if (u.operand() instanceof Expr.FloatLit f) return floatLit(f.digits(), f.suffix(), expected, u.span(), true);
                var t = expr(u.operand(), expected);
                var p = TypeSystem.prim(t.type());
                if (p != null && p.isNumeric()) {
                    if (p.isInteger() && !p.isSigned()) {
                        diag.error(Code.BAD_OPERAND, u.span(), "cannot negate a value of unsigned type '" + p + "'");
                    }
                    return new Typed(new Ir.Unary(Ir.UnOp.NEG, p, t.ir(), u.span()), p);
                }
                return operatorTrait("Neg", "neg", "-", t, null, u.span());
            }
            case NOT -> {
                var cond = condition(u);
                return new Typed(cond.ir(), Prim.BOOL);
            }
            case BIT_NOT -> {
                var t = expr(u.operand(), expected);
                var p = TypeSystem.prim(t.type());
                if (p != null && p.isInteger()) return new Typed(new Ir.Unary(Ir.UnOp.BIT_NOT, p, t.ir(), u.span()), p);
                if (TypeSystem.isFlags(t.type())) {
                    var n = (Type.Nominal) TypeSystem.expand(t.type());
                    return new Typed(new Ir.FlagsNot(n.decl(), t.ir()), n);
                }
                return operatorTrait("Not", "not", "~", t, null, u.span());
            }
        }
        throw new IllegalStateException();
    }

    /** A user-defined operator through its trait (§7.3), e.g. `Add::add`. */
    private Typed operatorTrait(String trait, String method, String symbol, Typed left, Expr right, Span span) {
        var lt = left.type();
        if (lt == Type.ERROR) {
            if (right != null) expr(right, null);
            return error();
        }
        var traitDecl = ts.operatorTraits.get(trait);
        var inst = ts.implementsAs(lt, traitDecl);
        if (inst == null) {
            if (right != null) expr(right, null);
            diag.error(Code.BAD_OPERAND, span, "operator '" + symbol + "' is not defined for '" + lt + "'");
            return error();
        }
        var self = TypeSystem.widen(lt);
        var fn = traitDecl.methods(method).getFirst();
        var args = right == null ? List.<Ir.Expr>of() : List.of(c.check(right, self));
        int[] order = right == null ? new int[0] : new int[] {0};
        return new Typed(new Ir.Call(fn, Ir.CallKind.TRAIT, left.ir(), null, args, order, List.of(), null, null, span), self);
    }

    private Typed binaryExpr(Expr.Binary b, Type expected) {
        switch (b.op()) {
            case AND, OR -> {
                var cond = condition(b);
                return new Typed(cond.ir(), Prim.BOOL);
            }
            case COALESCE -> {
                return coalesce(b, expected);
            }
            case RANGE, RANGE_INCLUSIVE -> {
                return range(b);
            }
            case IN -> {
                return membership(b);
            }
            default -> {}
        }
        // An expected numeric type flows into arithmetic operands, so literals adopt it (§3.6).
        Type hint = arithmetic(b.op()) && expected != null && TypeSystem.isNumeric(expected) ? expected : null;
        boolean shift = b.op() == BinaryOp.SHL || b.op() == BinaryOp.SHR;
        if (!shift && isNumericLiteral(b.left()) && !isNumericLiteral(b.right())) {
            var r = expr(b.right(), hint);
            var l = expr(b.left(), operandHint(r.type()));
            return operator(b.op(), l, new Typed(r.ir(), r.type()), null, b.span());
        }
        var l = expr(b.left(), hint);
        return binary(b.op(), l, b.right(), b.span(), null);
    }

    /** {@code x in xs} is {@code Contains::contains(xs, x)}, evaluating {@code xs} first (§7.3). */
    private Typed membership(Expr.Binary b) {
        var coll = expr(b.right(), null);
        if (coll.type() == Type.ERROR) {
            expr(b.left(), null);
            return error();
        }
        var inst = ts.implementsAs(coll.type(), ts.contains);
        if (inst == null) {
            expr(b.left(), null);
            diag.error(Code.BAD_OPERAND, b.span(), "operator 'in' is not defined for '" + TypeSystem.widen(coll.type()) + "': it does not implement `Contains`");
            return error();
        }
        var x = c.check(b.left(), inst.args().getFirst());
        var fn = ts.contains.methods("contains").getFirst();
        return new Typed(new Ir.Call(fn, Ir.CallKind.TRAIT, coll.ir(), null, List.of(x), new int[] {0}, List.of(), null, null,
                b.span()), Prim.BOOL);
    }

    private static boolean arithmetic(BinaryOp op) {
        return switch (op) {
            case ADD, SUB, MUL, DIV, REM, BIT_AND, BIT_OR, BIT_XOR, SHL, SHR -> true;
            default -> false;
        };
    }

    private static Type operandHint(Type t) {
        if (t == Type.ERROR) return null;
        return TypeSystem.widen(t);
    }

    /** {@code left op right} with {@code left} already typed (also compound assignment). */
    Typed binary(BinaryOp op, Typed left, Expr right, Span span, Type unused) {
        Type hint = op == BinaryOp.SHL || op == BinaryOp.SHR ? null : operandHint(left.type());
        var r = expr(right, hint);
        return operator(op, left, r, right, span);
    }

    private Typed operator(BinaryOp op, Typed l, Typed r, Expr rightAst, Span span) {
        var lt = TypeSystem.expand(l.type());
        var rt = TypeSystem.expand(r.type());
        if (lt == Type.ERROR || rt == Type.ERROR) {
            boolean cmp = switch (op) {
                case EQ, NE, LT, LE, GT, GE -> true;
                default -> false;
            };
            return new Typed(new Ir.Lit(null), cmp ? Prim.BOOL : Type.ERROR);
        }
        var pl = TypeSystem.prim(lt);
        var pr = TypeSystem.prim(rt);
        switch (op) {
            case ADD, SUB, MUL, DIV, REM -> {
                if (pl != null && pr != null && pl.isNumeric() && pr.isNumeric()) return numeric(op, l, r, pl, pr, span);
                if (op == BinaryOp.ADD && pl == Prim.STR && pr == Prim.STR) {
                    return new Typed(new Ir.Binary(op, Prim.STR, null, l.ir(), r.ir(), span), Prim.STR);
                }
                return operatorCall(op, l, r, span);
            }
            case BIT_AND, BIT_OR, BIT_XOR -> {
                if (pl != null && pr != null && pl.isInteger() && pr.isInteger()) return numeric(op, l, r, pl, pr, span);
                if (TypeSystem.isFlags(lt) && TypeSystem.widen(lt).equals(TypeSystem.widen(rt))) {
                    var n = (Type.Nominal) lt;
                    return new Typed(new Ir.Binary(op, null, n.decl(), l.ir(), r.ir(), span), n);
                }
                return operatorCall(op, l, r, span);
            }
            case SHL, SHR -> {
                if (pl != null && pr != null && pl.isInteger() && pr.isInteger()) {
                    return new Typed(new Ir.Binary(op, pl, null, l.ir(), r.ir(), span), pl);
                }
                return operatorCall(op, l, r, span);
            }
            case EQ, NE -> {
                return equality(op, l, r, span);
            }
            case LT, LE, GT, GE -> {
                if (pl != null && pr != null && pl.isNumeric() && pr.isNumeric()) {
                    var t = numeric(op, l, r, pl, pr, span);
                    return new Typed(t.ir(), Prim.BOOL);
                }
                if (pl != null && pl == pr && (pl == Prim.CHAR || pl == Prim.STR)) {
                    return new Typed(new Ir.Binary(op, pl, null, l.ir(), r.ir(), span), Prim.BOOL);
                }
                var wl = TypeSystem.widen(lt);
                if (wl instanceof Type.Nominal n && n.decl().isEnum() && wl.equals(TypeSystem.widen(rt))) {
                    return new Typed(new Ir.Compare(op, l.ir(), r.ir(), span), Prim.BOOL);
                }
                if (ts.implementsAs(wl, ts.ord) != null && ts.assignable(rt, wl)) {
                    return new Typed(new Ir.Compare(op, l.ir(), c.coerce(r, wl, span), span), Prim.BOOL);
                }
                diag.error(Code.BAD_OPERAND, span, "cannot compare '" + lt + "' and '" + rt + "' with '" + op.symbol()
                        + "': `Ord` is required");
                return new Typed(new Ir.Lit(false), Prim.BOOL);
            }
            default -> throw new IllegalStateException(op.toString());
        }
    }

    private Typed numeric(BinaryOp op, Typed l, Typed r, Prim pl, Prim pr, Span span) {
        Prim common = pl == pr ? pl : pl.widensTo(pr) ? pr : pr.widensTo(pl) ? pl : null;
        if (common == null) {
            diag.error(Code.BAD_OPERAND, span, "mismatched operand types '" + pl + "' and '" + pr + "' for '"
                    + op.symbol() + "'; convert one with `as`");
            return error();
        }
        var li = pl == common ? l.ir() : new Ir.Coerce(l.ir(), common);
        var ri = pr == common ? r.ir() : new Ir.Coerce(r.ir(), common);
        return new Typed(new Ir.Binary(op, common, null, li, ri, span), common);
    }

    private Typed operatorCall(BinaryOp op, Typed l, Typed r, Span span) {
        String trait = switch (op) {
            case ADD -> "Add";
            case SUB -> "Sub";
            case MUL -> "Mul";
            case DIV -> "Div";
            case REM -> "Rem";
            case BIT_AND -> "BitAnd";
            case BIT_OR -> "BitOr";
            case BIT_XOR -> "BitXor";
            case SHL -> "Shl";
            case SHR -> "Shr";
            default -> throw new IllegalStateException();
        };
        var lt = l.type();
        var traitDecl = ts.operatorTraits.get(trait);
        if (ts.implementsAs(lt, traitDecl) == null) {
            diag.error(Code.BAD_OPERAND, span, "operator '" + op.symbol() + "' is not defined for '" + lt + "' and '" + r.type() + "'");
            return error();
        }
        var self = TypeSystem.widen(lt);
        var fn = traitDecl.methods.values().iterator().next().getFirst();
        var arg = c.coerce(r, self, span);
        return new Typed(new Ir.Call(fn, Ir.CallKind.TRAIT, l.ir(), null, List.of(arg), new int[] {0}, List.of(), null,
                null, span), self);
    }

    private Typed equality(BinaryOp op, Typed l, Typed r, Span span) {
        boolean negate = op == BinaryOp.NE;
        var lt = l.type();
        var rt = r.type();
        var pl = TypeSystem.prim(lt);
        var pr = TypeSystem.prim(rt);
        if (pl != null && pr != null && pl.isNumeric() && pr.isNumeric()) {
            var t = numeric(op, l, r, pl, pr, span);
            return new Typed(t.ir(), Prim.BOOL);
        }
        boolean nullCompare = (lt == Type.NULL && TypeSystem.containsNull(rt)) || (rt == Type.NULL && TypeSystem.containsNull(lt));
        if (!nullCompare) {
            if (!ts.assignable(lt, rt) && !ts.assignable(rt, lt)) {
                diag.error(Code.BAD_OPERAND, span, "cannot compare '" + lt + "' with '" + rt + "'");
            } else if (!supportsEq(TypeSystem.widen(lt)) || !supportsEq(TypeSystem.widen(rt))) {
                diag.error(Code.BAD_OPERAND, span, "'" + (supportsEq(TypeSystem.widen(lt)) ? rt : lt)
                        + "' cannot be compared with '" + op.symbol() + "': `Eq` is required");
            }
        }
        return new Typed(new Ir.Eq(l.ir(), r.ir(), negate, span), Prim.BOOL);
    }

    /** Whether `==` is defined (§7.3): `Eq`, classes and trait references by identity, unions memberwise. */
    boolean supportsEq(Type t) {
        t = TypeSystem.expand(t);
        return switch (t) {
            case Type.Special s -> true;
            case Prim p -> true;
            case Type.Lit l -> true;
            case Type.Nominal n -> n.decl().isClass() || n.decl().isTrait() || n.decl().isEnum()
                    || ts.implementsAs(n, ts.eq) != null;
            case Type.Union u -> u.members().stream().allMatch(this::supportsEq);
            case Type.Tuple tu -> tu.elements().stream().allMatch(this::supportsEq);
            case Type.Param p -> ts.implementsAs(p, ts.eq) != null;
            case Type.Array a -> supportsEq(a.element());
            case Type.Fn f -> false;
            default -> false;
        };
    }

    private Typed coalesce(Expr.Binary b, Type expected) {
        var l = expr(b.left(), expected == null ? null : TypeSystem.union(expected, Type.NULL));
        var nonNull = ts.minus(l.type(), Type.NULL);
        var r = expr(b.right(), expected != null ? expected : TypeSystem.widenInferred(nonNull));
        var type = TypeSystem.union(nonNull, r.type());
        if (l.type() == Type.ERROR || r.type() == Type.ERROR) type = Type.ERROR;
        return new Typed(new Ir.Coalesce(l.ir(), r.ir()), type);
    }

    private Typed range(Expr.Binary b) {
        Typed l;
        Typed r;
        if (isNumericLiteral(b.left()) && !isNumericLiteral(b.right())) {
            r = expr(b.right(), null);
            l = expr(b.left(), operandHint(r.type()));
        } else {
            l = expr(b.left(), null);
            r = expr(b.right(), operandHint(l.type()));
        }
        if (l.type() == Type.ERROR || r.type() == Type.ERROR) return error();
        var pl = TypeSystem.prim(l.type());
        var pr = TypeSystem.prim(r.type());
        Prim common = pl == null || pr == null ? null : pl == pr ? pl : pl.widensTo(pr) ? pr : pr.widensTo(pl) ? pl : null;
        if (common == null || !(common.isInteger() || common == Prim.CHAR)) {
            diag.error(Code.BAD_OPERAND, b.span(), "a range needs integer or char bounds of one type, found '"
                    + l.type() + "' and '" + r.type() + "'");
            return error();
        }
        var li = pl == common ? l.ir() : new Ir.Coerce(l.ir(), common);
        var ri = pr == common ? r.ir() : new Ir.Coerce(r.ir(), common);
        var type = new Type.Nominal(ts.range, List.of(common));
        var fields = List.of(new Ir.FieldInit(0, li), new Ir.FieldInit(1, ri),
                new Ir.FieldInit(2, new Ir.Lit(b.op() == BinaryOp.RANGE_INCLUSIVE)));
        return new Typed(new Ir.New(ts.range, List.of(common), null, fields, b.span()), type);
    }

    // ---- Conditions and narrowing (§6.6, §9) ----

    /** The local a narrowing test applies to: a plain local name or `self`. */
    private Local narrowable(Expr e) {
        if (e instanceof Expr.Self) return c.ctx.self;
        if (e instanceof Expr.Path p && p.segments().size() == 1 && p.segments().getFirst().typeArgs().isEmpty()) {
            return c.lookupLocal(p.segments().getFirst().name().text());
        }
        return null;
    }

    Cond condition(Expr e) {
        var ctx = c.ctx;
        switch (e) {
            case Expr.Binary b when b.op() == BinaryOp.AND -> {
                var saved = ctx.narrowed;
                var a = condition(b.left());
                ctx.narrowed = Checker.with(saved, a.whenTrue());
                var r = condition(b.right());
                ctx.narrowed = saved;
                var whenTrue = Checker.with(a.whenTrue(), r.whenTrue());
                var whenFalse = c.merge(Checker.with(saved, a.whenFalse()),
                        Checker.with(saved, Checker.with(a.whenTrue(), r.whenFalse())));
                return new Cond(new Ir.And(a.ir(), r.ir()), whenTrue, whenFalse);
            }
            case Expr.Binary b when b.op() == BinaryOp.OR -> {
                var saved = ctx.narrowed;
                var a = condition(b.left());
                ctx.narrowed = Checker.with(saved, a.whenFalse());
                var r = condition(b.right());
                ctx.narrowed = saved;
                var whenTrue = c.merge(Checker.with(saved, a.whenTrue()),
                        Checker.with(saved, Checker.with(a.whenFalse(), r.whenTrue())));
                var whenFalse = Checker.with(a.whenFalse(), r.whenFalse());
                return new Cond(new Ir.Or(a.ir(), r.ir()), whenTrue, whenFalse);
            }
            case Expr.Unary u when u.op() == Expr.UnaryOp.NOT -> {
                var inner = condition(u.operand());
                return new Cond(new Ir.Not(inner.ir()), inner.whenFalse(), inner.whenTrue());
            }
            case Expr.Is is -> {
                var target = types.resolve(is.type(), ctx.typeScope);
                var local = narrowable(is.operand());
                var operand = expr(is.operand(), null);
                var ir = new Ir.Is(operand.ir(), target);
                if (operand.type() != Type.ERROR && target != Type.ERROR && !castPossible(operand.type(), target)) {
                    diag.error(Code.IMPOSSIBLE_CAST, is.span(), "a value of type '" + operand.type() + "' is never a '" + target + "'");
                }
                if (local == null || target == Type.ERROR) return new Cond(ir, Map.of(), Map.of());
                var t = operand.type();
                return new Cond(ir, Map.of(local, narrowIs(t, target)), Map.of(local, ts.minus(t, target)));
            }
            case Expr.IsPattern is -> {
                var local = narrowable(is.operand());
                var operand = expr(is.operand(), null);
                var t = operand.type();
                var bindings = new LinkedHashMap<String, Local>();
                var pat = c.patterns.test(is.pattern(), t, bindings);
                var whenTrue = new HashMap<Local, Type>();
                for (Local b : bindings.values()) whenTrue.put(b, b.type);
                Map<Local, Type> whenFalse = Map.of();
                if (local != null && t != Type.ERROR) {
                    whenTrue.put(local, c.patterns.possible(pat, t));
                    whenFalse = Map.of(local, c.patterns.remainder(pat, t));
                }
                return new Cond(new Ir.IsPattern(operand.ir(), pat), whenTrue, whenFalse);
            }
            case Expr.Binary b when b.op() == BinaryOp.EQ || b.op() == BinaryOp.NE -> {
                var typed = binaryExpr(b, null);
                var ir = typed.ir();
                Map<Local, Type> yes = Map.of();
                Map<Local, Type> no = Map.of();
                Local local = narrowable(b.left());
                Expr other = b.right();
                if (local == null) {
                    local = narrowable(b.right());
                    other = b.left();
                }
                if (local != null) {
                    var t = c.typeOf(local);
                    if (other instanceof Expr.NullLit && TypeSystem.containsNull(t)) {
                        yes = Map.of(local, Type.NULL);
                        no = Map.of(local, ts.minus(t, Type.NULL));
                    } else if (other instanceof Expr.Literal lit && !(other instanceof Expr.FloatLit)) {
                        var litType = literalTypeIn(lit, t);
                        if (litType != null) {
                            yes = Map.of(local, litType);
                            no = Map.of(local, ts.minus(t, litType));
                        }
                    }
                }
                return b.op() == BinaryOp.EQ ? new Cond(ir, yes, no) : new Cond(ir, no, yes);
            }
            default -> {
                var local = narrowable(e);
                var t = expr(e, Prim.BOOL);
                var ir = truthy(t, e.span());
                if (local != null && TypeSystem.containsNull(t.type())) {
                    return new Cond(ir, Map.of(local, ts.minus(t.type(), Type.NULL)), Map.of());
                }
                return new Cond(ir, Map.of(), Map.of());
            }
        }
    }

    /** The literal type of {@code lit} when it is a member of {@code t} (`x == "quick"` narrowing). */
    private Type literalTypeIn(Expr.Literal lit, Type t) {
        var lt = TypeResolver.literal(lit);
        for (Type m : TypeSystem.members(t)) {
            if (m instanceof Type.Lit ml && lt instanceof Type.Lit ll && ml.value().equals(ll.value())
                    && (ml.base() == ll.base() || (ml.base().isInteger() && ll.base().isInteger()))) return m;
        }
        return null;
    }

    /** A condition operand as a bool (§6.6). */
    Ir.Expr truthy(Typed t, Span span) {
        var type = t.type();
        if (type == Type.ERROR) return t.ir();
        if (TypeSystem.prim(type) == Prim.BOOL && !(TypeSystem.expand(type) instanceof Type.Union)) return t.ir();
        for (Type m : TypeSystem.members(type)) {
            if (m == Type.NULL || TypeSystem.prim(m) == Prim.BOOL || m == Type.NEVER) continue;
            if (ts.implementsAs(m, ts.truthy) == null) {
                diag.error(Code.BAD_CONDITION, span, "a condition must be `bool` or implement `Truthy`, found '" + type + "'");
                return t.ir();
            }
        }
        return new Ir.Truthy(t.ir(), span);
    }

    /** {@code x is T} narrows {@code x} to the members (or subtypes) of its type matching T (§9). */
    Type narrowIs(Type x, Type t) {
        var out = new ArrayList<Type>();
        for (Type m : TypeSystem.members(x)) {
            if (ts.assignable(m, t)) {
                out.add(m);
            } else {
                for (Type tm : TypeSystem.members(t)) if (ts.assignable(tm, m)) out.add(tm);
            }
        }
        return out.isEmpty() ? t : TypeSystem.union(out);
    }

    private Typed conditional(Expr.Conditional e, Type expected) {
        var ctx = c.ctx;
        var saved = ctx.narrowed;
        var cond = condition(e.condition());
        ctx.narrowed = Checker.with(saved, cond.whenTrue());
        var a = expr(e.then(), expected);
        ctx.narrowed = Checker.with(saved, cond.whenFalse());
        var b = expr(e.otherwise(), expected);
        ctx.narrowed = saved;
        // Without an expected type, literal arms join as their base types (§3.6): `1 if c else 2` is `i32`.
        var type = a.type() == Type.ERROR || b.type() == Type.ERROR ? Type.ERROR
                : expected == null ? TypeSystem.union(TypeSystem.widenInferred(a.type()), TypeSystem.widenInferred(b.type()))
                : TypeSystem.union(a.type(), b.type());
        return new Typed(new Ir.Cond(cond.ir(), a.ir(), b.ir()), type);
    }

    // ---- Casts (§7.9) ----

    private Typed cast(Expr.Cast e) {
        var target = types.resolve(e.type(), c.ctx.typeScope);
        // Collection literals take the cast's type as their expected type (§7.9): `[] as [i32]`.
        boolean literal = e.operand() instanceof Expr.ListLit || e.operand() instanceof Expr.MapLit
                || e.operand() instanceof Expr.RepeatLit || e.operand() instanceof Expr.ListComp
                || e.operand() instanceof Expr.MapComp;
        var t = expr(e.operand(), literal && target != Type.ERROR ? target : null);
        var resultType = e.isChecked() ? TypeSystem.union(target, Type.NULL) : target;
        if (t.type() == Type.ERROR || target == Type.ERROR) return new Typed(t.ir(), target == Type.ERROR ? Type.ERROR : resultType);
        var from = TypeSystem.prim(t.type());
        var to = TypeSystem.prim(target);
        boolean scalar = from != null && to != null && !(TypeSystem.expand(target) instanceof Type.Lit)
                && (from.isNumeric() || from == Prim.CHAR || from == Prim.BOOL)
                && (to.isNumeric() || to == Prim.CHAR) && !(to == Prim.CHAR && (from.isFloat() || from == Prim.BOOL));
        boolean enumCast = (from != null && from.isInteger() && isEnum(target))
                || (isEnum(t.type()) && to != null && to.isInteger());
        if (!scalar && !enumCast && !castPossible(t.type(), target)) {
            diag.error(Code.IMPOSSIBLE_CAST, e.span(), "a value of type '" + t.type() + "' can never be a '" + target + "'");
        }
        return new Typed(new Ir.Cast(t.ir(), target, e.isChecked(), e.span()), resultType);
    }

    private static boolean isEnum(Type t) {
        return TypeSystem.expand(t) instanceof Type.Nominal n && n.decl().isEnum();
    }

    /** Whether some value of type {@code from} could be a {@code to} at runtime. */
    boolean castPossible(Type from, Type to) {
        for (Type m : TypeSystem.members(from)) {
            for (Type t : TypeSystem.members(to)) {
                if (m == Type.ERROR || t == Type.ERROR) return true;
                if (ts.assignable(m, t) || ts.assignable(t, m)) return true;
                if (m instanceof Type.Param || t instanceof Type.Param) return true;
                if (TypeSystem.isTraitRef(m) || TypeSystem.isTraitRef(t)) return true;
                if (m instanceof Type.Nominal a && t instanceof Type.Nominal b && a.decl() == b.decl()) return true;
            }
        }
        return false;
    }

    // ---- Lambdas (§7.8) ----

    Typed lambda(Expr.Lambda l, Type expected) {
        var expectedFn = expected == null ? null : TypeSystem.expand(expected) instanceof Type.Fn f ? f : null;
        if (expectedFn == null && expected != null) {
            for (Type m : TypeSystem.members(expected)) if (m instanceof Type.Fn f) expectedFn = f;
        }
        if (expectedFn != null && expectedFn.isExtern()) {
            diag.error(Code.BAD_CALLBACK, l.span(), "a lambda cannot convert to '" + expectedFn
                    + "'; pass a top-level function instead (§14.7)");
            return error();
        }
        if (expectedFn != null && expectedFn.params().size() != l.params().size()) {
            diag.error(Code.BAD_LAMBDA, l.span(), "expected a function of " + expectedFn.params().size()
                    + " parameter(s), this lambda takes " + l.params().size());
            return error();
        }
        var paramTypes = new ArrayList<Type>();
        for (int i = 0; i < l.params().size(); i++) {
            var p = l.params().get(i);
            Type t;
            if (p.type() != null) t = types.resolve(p.type(), c.ctx.typeScope);
            else if (expectedFn != null) t = expectedFn.params().get(i);
            else if (p.pattern() instanceof Pattern.Binding b) {
                diag.error(Code.CANNOT_INFER, p.span(), "cannot infer the type of lambda parameter '" + b.name().text()
                        + "'; annotate it: `" + b.name().text() + ": T`");
                t = Type.ERROR;
            } else {
                diag.error(Code.CANNOT_INFER, p.span(), "cannot infer the type of this lambda parameter; annotate it");
                t = Type.ERROR;
            }
            paramTypes.add(t);
        }
        Type ret = expectedFn != null && !TypeSystem.isGeneric(expectedFn.ret()) ? expectedFn.ret() : null;
        if (expectedFn != null && ret == null && !mentionsUnbound(expectedFn.ret())) ret = expectedFn.ret();
        var ctx = c.lambdaCtx(ret);
        final Type knownRet = ret;
        return c.inCtx(ctx, () -> {
            var params = new ArrayList<Ir.Pat>();
            for (int i = 0; i < l.params().size(); i++) {
                var p = l.params().get(i);
                if (p.pattern() instanceof Pattern.Binding b && b.isType() == null) {
                    var local = c.declare(b.name().text(), b.isMutable(), paramTypes.get(i), p.span());
                    params.add(new Ir.PBind(local, null, false));
                } else {
                    params.add(c.patterns.irrefutable(p.pattern(), paramTypes.get(i)));
                }
            }
            Ir.Stmt body = null;
            Ir.Expr value = null;
            Type inferred;
            switch (l.body()) {
                case Expr e -> {
                    if (knownRet != null) {
                        value = c.check(e, knownRet);
                        inferred = knownRet;
                    } else {
                        var t = expr(e, null);
                        value = t.ir();
                        var all = new ArrayList<>(ctx.inferredReturns);
                        all.add(TypeSystem.widenInferred(t.type()));
                        inferred = TypeSystem.union(all);
                    }
                }
                case Stmt.Block b -> {
                    body = c.block(b);
                    if (knownRet != null) {
                        if (ctx.reachable && !ts.assignable(Type.UNIT, knownRet)) {
                            diag.error(Code.MISSING_RETURN, b.span(), "missing `return`: this lambda returns '" + knownRet + "'");
                        }
                        inferred = knownRet;
                    } else {
                        var all = new ArrayList<>(ctx.inferredReturns);
                        if (ctx.reachable) all.add(Type.UNIT);
                        inferred = all.isEmpty() ? Type.UNIT : TypeSystem.union(all);
                    }
                }
                case Stmt.Assign a -> {
                    body = c.assign(a);
                    inferred = Type.UNIT;
                    if (knownRet != null && !ts.assignable(Type.UNIT, knownRet)) {
                        diag.error(Code.TYPE_MISMATCH, a.span(), "an assignment body yields `()`, expected '" + knownRet + "'");
                    }
                }
            }
            var type = new Type.Fn(paramTypes, inferred, false);
            return new Typed(new Ir.Lambda(params, body, value, type), type);
        });
    }

    private static boolean mentionsUnbound(Type t) {
        return TypeSystem.isGeneric(t);
    }

    // ---- Initializers (§4.7) ----

    private Typed init(Expr.Init e, Type expected) {
        Type.Nominal target;
        if (e.type() != null) {
            var tt = pathTarget(e.type(), false);
            if (tt instanceof ErrorTarget) {
                initFieldsUnchecked(e);
                return error();
            }
            if (!(tt instanceof TypeTarget t) || !(t.type() instanceof Type.Nominal n)
                    || !(n.decl().isStruct() || n.decl().isClass())) {
                diag.error(Code.WRONG_KIND, e.type().span(), "an initializer needs a struct or class type");
                initFieldsUnchecked(e);
                return error();
            }
            target = n;
            if (!t.argsGiven()) return initInferred(e, n.decl());
        } else {
            target = initTarget(expected);
            if (target == null) {
                diag.error(Code.NO_TARGET_TYPE, e.span(), expected == null
                        ? "cannot tell what this initializer creates; name the type: `Type { ... }`"
                        : "an initializer cannot create a '" + expected + "'");
                initFieldsUnchecked(e);
                return error();
            }
        }
        return initFor(e, target);
    }

    private void initFieldsUnchecked(Expr.Init e) {
        for (var f : e.fields()) {
            switch (f) {
                case Expr.FieldInit fi -> expr(fi.value(), null);
                case Expr.Spread s -> expr(s.value(), null);
            }
        }
    }

    /** The struct/class a target-typed `{ ... }` creates. */
    private Type.Nominal initTarget(Type expected) {
        if (expected == null) return null;
        Type.Nominal found = null;
        for (Type m : TypeSystem.members(expected)) {
            if (m instanceof Type.Nominal n && (n.decl().isStruct() || n.decl().isClass())) {
                if (found != null) return null;
                found = n;
            }
        }
        return found;
    }

    /** {@code Generic { ... }} without type arguments: infer them from the field values (§10). */
    private Typed initInferred(Expr.Init e, TypeInfo decl) {
        var vars = new HashSet<>(decl.typeParams);
        var env = new HashMap<TypeParamInfo, Type>();
        for (var f : e.fields()) {
            if (f instanceof Expr.FieldInit fi) {
                var field = decl.field(fi.name().text());
                if (field == null) continue;
                var t = diag.muted(() -> expr(fi.value(), null));
                ts.unify(field.type, t.type(), env, vars);
            } else if (f instanceof Expr.Spread s) {
                var t = diag.muted(() -> expr(s.value(), null));
                ts.unify(new Type.Nominal(decl, decl.typeParams.stream().map(TypeParamInfo::type).toList()), t.type(), env, vars);
            }
        }
        var args = new ArrayList<Type>();
        for (var p : decl.typeParams) {
            var a = env.get(p);
            if (a == null) {
                diag.error(Code.CANNOT_INFER, e.type().span(), "cannot infer type argument '" + p.name() + "' of '"
                        + decl.name() + "'; write `" + decl.name() + "<...> { ... }`");
                a = Type.ERROR;
            }
            args.add(a);
        }
        types.checkBounds(decl.typeParams, args, e.type().span());
        return initFor(e, new Type.Nominal(decl, args));
    }

    private Typed initFor(Expr.Init e, Type.Nominal target) {
        var decl = target.decl();
        if (decl.isClass() && c.ctx.enclosing != decl) {
            diag.error(Code.INITIALIZER_NOT_ALLOWED, e.span(), "an initializer for class '" + decl.name()
                    + "' may only appear in its own body; call a constructor function such as `" + decl.name() + "::new`");
        }
        var env = TypeSystem.bind(decl.typeParams, target.args());
        Ir.Expr spread = null;
        TypeInfo spreadDecl = null;
        var explicit = new ArrayList<Ir.FieldInit>();
        var given = new HashSet<String>();
        for (var f : e.fields()) {
            switch (f) {
                case Expr.Spread s -> {
                    if (spread != null) {
                        diag.error(Code.BAD_FIELD, s.span(), "at most one spread per initializer");
                        expr(s.value(), null);
                        continue;
                    }
                    var t = expr(s.value(), target);
                    if (t.type() == Type.ERROR) {
                        spread = t.ir();
                        spreadDecl = decl;
                        continue;
                    }
                    var st = TypeSystem.expand(t.type());
                    if (decl.isStruct()) {
                        spread = c.coerce(t, target, s.span());
                        spreadDecl = decl;
                    } else if (st instanceof Type.Nominal sn && sn.decl().isClass() && decl.isSubclassOf(sn.decl())) {
                        var expectedBase = TypeSystem.baseInstance(target, sn.decl());
                        if (!sn.equals(expectedBase)) {
                            diag.error(Code.TYPE_MISMATCH, s.span(), "expected '" + expectedBase + "', found '" + sn + "'");
                        }
                        spread = t.ir();
                        spreadDecl = sn.decl();
                    } else {
                        diag.error(Code.TYPE_MISMATCH, s.span(), "cannot spread a '" + t.type() + "' into a '" + target + "'");
                        spread = t.ir();
                    }
                }
                case Expr.FieldInit fi -> {
                    String name = fi.name().text();
                    var field = decl.field(name);
                    c.index.ref(fi.name().span(), field, null);
                    if (field == null) {
                        diag.error(Code.BAD_FIELD, fi.name().span(), "'" + decl.name() + "' has no field '" + name + "'");
                        expr(fi.value(), null);
                        continue;
                    }
                    if (!given.add(name)) {
                        diag.error(Code.BAD_FIELD, fi.name().span(), "field '" + name + "' is given twice");
                    }
                    if (field.owner() != decl && field.visibility == Visibility.PRIVATE) {
                        diag.error(Code.PRIVATE_MEMBER, fi.name().span(), "field '" + name + "' is private in '"
                                + field.owner().name() + "'; supply it with a spread");
                    } else if (field.owner() == decl && !decl.isStruct()) {
                        checkAccess(decl, field.visibility, "field '" + name + "'", fi.name().span());
                    }
                    var base = field.owner() == decl ? target : TypeSystem.baseInstance(target, field.owner());
                    var ft = TypeSystem.subst(field.type, TypeSystem.bind(field.owner().typeParams, base.args()));
                    explicit.add(new Ir.FieldInit(field.slot, c.check(fi.value(), ft)));
                }
            }
        }
        var missing = new ArrayList<String>();
        for (FieldInfo f : decl.allFields) {
            boolean supplied = given.contains(f.name()) || f.defaultValue != null
                    || (spreadDecl != null && spreadDecl.field(f.name()) != null);
            if (!supplied) missing.add(f.name());
        }
        if (!missing.isEmpty()) {
            diag.error(Code.MISSING_FIELD, e.span(), "missing field" + (missing.size() == 1 ? " " : "s ")
                    + String.join(", ", missing.stream().map(m -> "'" + m + "'").toList()) + " in initializer of '" + decl.name() + "'");
        }
        return new Typed(new Ir.New(decl, target.args(), spread, explicit, e.span()), target);
    }

    // ---- Collections (§7.7) ----

    private enum Want { LIST, SET, ARRAY, MAP }

    private record CollectionTarget(Want want, Type element, Type key, Type value, int size) {}

    /** The written shape of a collection literal: it picks which collection member of a union it builds. */
    private enum Form { ELEMENTS, ENTRIES }

    private CollectionTarget collectionTarget(Type expected, Form form, Span span) {
        if (expected == null) return null;
        var all = new ArrayList<CollectionTarget>();
        for (Type m : TypeSystem.members(expected)) {
            switch (TypeSystem.expand(m)) {
                case Type.Array a -> all.add(new CollectionTarget(Want.ARRAY, a.element(), null, null, a.size()));
                case Type.Nominal n when n.decl() == ts.list ->
                        all.add(new CollectionTarget(Want.LIST, n.args().getFirst(), null, null, -1));
                case Type.Nominal n when n.decl() == ts.set ->
                        all.add(new CollectionTarget(Want.SET, n.args().getFirst(), null, null, -1));
                case Type.Nominal n when n.decl() == ts.map ->
                        all.add(new CollectionTarget(Want.MAP, null, n.args().get(0), n.args().get(1), -1));
                default -> {}
            }
        }
        if (all.size() <= 1) return all.isEmpty() ? null : all.getFirst();
        // §7.7: in a union, `[k: v]` targets the map member; `[a, b]` and `[]` the list, set or array member.
        var fits = all.stream().filter(t -> (t.want() == Want.MAP) == (form == Form.ENTRIES)).toList();
        if (fits.isEmpty()) return all.getFirst();
        if (fits.size() > 1) {
            diag.error(Code.NO_TARGET_TYPE, span, "ambiguous collection literal for '" + expected
                    + "': it could build any of its collection members; pick one with `as`");
        }
        return fits.getFirst();
    }

    private Typed listLit(Expr.ListLit l, Type expected) {
        var target = collectionTarget(expected, Form.ELEMENTS, l.span());
        if (target != null && target.want() == Want.MAP) {
            if (!l.elements().isEmpty()) {
                diag.error(Code.TYPE_MISMATCH, l.span(), "expected a map literal `[k: v]` for '" + expected + "'");
                for (var e : l.elements()) expr(e, null);
                return error();
            }
            var type = new Type.Nominal(ts.map, List.of(target.key(), target.value()));
            return new Typed(new Ir.NewMap(target.key(), target.value(), List.of(), List.of()), type);
        }
        Type element;
        var irs = new ArrayList<Ir.Expr>();
        if (target != null) {
            element = target.element();
            for (var e : l.elements()) irs.add(c.check(e, element));
        } else {
            if (l.elements().isEmpty()) {
                diag.error(Code.NO_TARGET_TYPE, l.span(), "cannot infer the element type of `[]`; annotate the type");
                return error();
            }
            var typed = l.elements().stream().map(e -> expr(e, null)).toList();
            element = TypeSystem.union(typed.stream().map(t -> TypeSystem.widenInferred(t.type())).toList());
            for (int i = 0; i < typed.size(); i++) irs.add(c.coerce(typed.get(i), element, l.elements().get(i).span()));
        }
        if (target != null && target.want() == Want.ARRAY) {
            if (irs.size() != target.size()) {
                diag.error(Code.TYPE_MISMATCH, l.span(), "expected " + target.size() + " elements for '" + expected
                        + "', found " + irs.size());
            }
            return new Typed(new Ir.NewCollection(Ir.CollectionKind.ARRAY, element, irs), new Type.Array(element, target.size()));
        }
        if (target != null && target.want() == Want.SET) {
            requireHashEq(element, l.span());
            return new Typed(new Ir.NewCollection(Ir.CollectionKind.SET, element, irs), new Type.Nominal(ts.set, List.of(element)));
        }
        return new Typed(new Ir.NewCollection(Ir.CollectionKind.LIST, element, irs), ts.listOf(element));
    }

    private void requireHashEq(Type t, Span span) {
        if (t == Type.ERROR) return;
        for (var tr : List.of(ts.hash, ts.eq)) {
            if (ts.implementsAs(t, tr) == null) {
                diag.error(Code.UNSATISFIED_BOUND, span, "'" + t + "' does not implement '" + tr.name() + "' (required for set elements and map keys)");
                return;
            }
        }
    }

    private Typed repeat(Expr.RepeatLit r, Type expected) {
        var target = collectionTarget(expected, Form.ELEMENTS, r.span());
        Type element = target != null && target.element() != null ? target.element() : null;
        var value = element != null ? c.check(r.value(), element) : null;
        if (element == null) {
            var t = expr(r.value(), null);
            element = TypeSystem.widenInferred(t.type());
            value = t.ir();
        }
        if (target != null && target.want() == Want.ARRAY) {
            Long n = c.consts.constLong(r.count(), Prim.USIZE);
            if (n != null && n != target.size()) {
                diag.error(Code.TYPE_MISMATCH, r.span(), "expected " + target.size() + " elements for '" + expected
                        + "', found " + n);
            }
            return new Typed(new Ir.Repeat(Ir.CollectionKind.ARRAY, element, value, new Ir.Lit(new Values.Int(Prim.USIZE, n == null ? 0 : n)),
                    r.span()), new Type.Array(element, target.size()));
        }
        var count = c.check(r.count(), Prim.USIZE);
        return new Typed(new Ir.Repeat(Ir.CollectionKind.LIST, element, value, count, r.span()), ts.listOf(element));
    }

    private Typed mapLit(Expr.MapLit m, Type expected) {
        var target = collectionTarget(expected, Form.ENTRIES, m.span());
        Type key;
        Type value;
        var keys = new ArrayList<Ir.Expr>();
        var values = new ArrayList<Ir.Expr>();
        if (target != null && target.want() == Want.MAP) {
            key = target.key();
            value = target.value();
            for (var e : m.entries()) {
                keys.add(c.check(e.key(), key));
                values.add(c.check(e.value(), value));
            }
        } else {
            if (target != null) {
                diag.error(Code.TYPE_MISMATCH, m.span(), "a map literal cannot create a '" + expected + "'");
            }
            if (m.entries().isEmpty()) {
                if (target == null) diag.error(Code.NO_TARGET_TYPE, m.span(), "cannot infer the types of `[:]`; annotate the type");
                return error();
            }
            var ks = m.entries().stream().map(e -> expr(e.key(), null)).toList();
            var vs = m.entries().stream().map(e -> expr(e.value(), null)).toList();
            key = TypeSystem.union(ks.stream().map(t -> TypeSystem.widenInferred(t.type())).toList());
            value = TypeSystem.union(vs.stream().map(t -> TypeSystem.widenInferred(t.type())).toList());
            for (int i = 0; i < ks.size(); i++) {
                keys.add(c.coerce(ks.get(i), key, m.entries().get(i).key().span()));
                values.add(c.coerce(vs.get(i), value, m.entries().get(i).value().span()));
            }
        }
        requireHashEq(key, m.span());
        return new Typed(new Ir.NewMap(key, value, keys, values), new Type.Nominal(ts.map, List.of(key, value)));
    }

    // ---- Comprehensions (§7.7) ----

    private Typed listComp(Expr.ListComp l, Type expected) {
        var target = collectionTarget(expected, Form.ELEMENTS, l.span());
        if (target != null && (target.want() == Want.ARRAY || target.want() == Want.MAP)) {
            diag.error(Code.TYPE_MISMATCH, l.span(), "a comprehension cannot create a '" + expected + "'"
                    + (target.want() == Want.MAP ? "; write `[k: v for ...]`" : ": its length is not known"));
            return comprehension(l.clauses(), new ArrayList<>(), () -> {
                expr(l.element(), null);
                return error();
            });
        }
        var want = target == null ? Want.LIST : target.want();
        var element = target == null ? null : target.element();
        var clauses = new ArrayList<Ir.CompClause>();
        return comprehension(l.clauses(), clauses, () -> {
            Ir.Expr value;
            Type type = element;
            if (type != null) {
                value = c.check(l.element(), type);
            } else {
                var t = expr(l.element(), null);
                if (t.type() == Type.ERROR) return error();
                type = TypeSystem.widenInferred(t.type());
                value = c.coerce(t, type, l.element().span());
            }
            if (want == Want.SET) {
                requireHashEq(type, l.span());
                return new Typed(new Ir.ListComp(Ir.CollectionKind.SET, type, clauses, value), new Type.Nominal(ts.set, List.of(type)));
            }
            return new Typed(new Ir.ListComp(Ir.CollectionKind.LIST, type, clauses, value), ts.listOf(type));
        });
    }

    private Typed mapComp(Expr.MapComp m, Type expected) {
        var target = collectionTarget(expected, Form.ENTRIES, m.span());
        if (target != null && target.want() != Want.MAP) {
            diag.error(Code.TYPE_MISMATCH, m.span(), "a map comprehension cannot create a '" + expected + "'");
            return comprehension(m.clauses(), new ArrayList<>(), () -> {
                expr(m.entry().key(), null);
                expr(m.entry().value(), null);
                return error();
            });
        }
        var known = target;
        var clauses = new ArrayList<Ir.CompClause>();
        return comprehension(m.clauses(), clauses, () -> {
            var entry = m.entry();
            Type key;
            Type value;
            Ir.Expr keyIr;
            Ir.Expr valueIr;
            if (known != null) {
                key = known.key();
                value = known.value();
                keyIr = c.check(entry.key(), key);
                valueIr = c.check(entry.value(), value);
            } else {
                var k = expr(entry.key(), null);
                var v = expr(entry.value(), null);
                if (k.type() == Type.ERROR || v.type() == Type.ERROR) return error();
                key = TypeSystem.widenInferred(k.type());
                value = TypeSystem.widenInferred(v.type());
                keyIr = c.coerce(k, key, entry.key().span());
                valueIr = c.coerce(v, value, entry.value().span());
            }
            requireHashEq(key, m.span());
            return new Typed(new Ir.MapComp(key, value, clauses, keyIr, valueIr), new Type.Nominal(ts.map, List.of(key, value)));
        });
    }

    /**
     * Checks the clauses into {@code out}, each in a scope nested in the previous one's, with the
     * filters' narrowing applied to what follows them, then the element ({@code body}) inside them all.
     */
    private Typed comprehension(List<Expr.CompClause> clauses, List<Ir.CompClause> out, Supplier<Typed> body) {
        var ctx = c.ctx;
        var outer = ctx.scope;
        var saved = ctx.narrowed;
        try {
            for (var clause : clauses) {
                var iteration = c.iteration(clause.iterable());
                ctx.scope = new Checker.Scope(ctx.scope);
                var pattern = c.patterns.irrefutable(clause.pattern(), iteration.element());
                Ir.Expr filter = null;
                if (clause.filter() != null) {
                    var cond = condition(clause.filter());
                    filter = cond.ir();
                    ctx.narrowed = Checker.with(ctx.narrowed, cond.whenTrue());
                }
                out.add(new Ir.CompClause(pattern, iteration.iterator(), filter));
            }
            return body.get();
        } finally {
            ctx.scope = outer;
            ctx.narrowed = saved;
        }
    }

    private Typed tuple(Expr.Tuple t, Type expected) {
        List<Type> expectedElems = null;
        if (expected != null) {
            for (Type m : TypeSystem.members(expected)) {
                if (TypeSystem.expand(m) instanceof Type.Tuple tu && tu.elements().size() == t.elements().size()) {
                    expectedElems = tu.elements();
                }
            }
        }
        var irs = new ArrayList<Ir.Expr>();
        var typesOut = new ArrayList<Type>();
        boolean bad = false;
        for (int i = 0; i < t.elements().size(); i++) {
            var e = t.elements().get(i);
            if (expectedElems != null) {
                irs.add(c.check(e, expectedElems.get(i)));
                typesOut.add(expectedElems.get(i));
            } else {
                // Literal element types widen (§3.6), so `[(1, "a")]` is a `[(i32, str)]`.
                var typed = expr(e, null);
                irs.add(typed.ir());
                typesOut.add(TypeSystem.widenInferred(typed.type()));
                bad |= typed.type() == Type.ERROR;
            }
        }
        return new Typed(new Ir.Tuple(irs), bad ? Type.ERROR : new Type.Tuple(typesOut));
    }
}
