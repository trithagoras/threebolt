package dev.threebolt.types;

import dev.threebolt.ast.Expr;
import dev.threebolt.ast.Pattern;
import dev.threebolt.ast.Stmt;
import dev.threebolt.diag.Code;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Span;
import dev.threebolt.interp.Values;
import dev.threebolt.ir.Ir;
import dev.threebolt.ir.Local;
import dev.threebolt.types.Checker.Typed;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Patterns (§11), `match` (§7.11) and exhaustiveness. */
final class Patterns {
    private final Checker c;
    private final TypeSystem ts;
    private final Diagnostics diag;

    Patterns(Checker c) {
        this.c = c;
        this.ts = c.ts;
        this.diag = c.diag;
    }

    /** Added to the error for a binding named after a type (§11), where a type test was likely meant. */
    private String typeNameHint;

    /** A `let`/`for`/lambda-parameter pattern: must match every value of {@code type} (§11). */
    Ir.Pat irrefutable(Pattern p, Type type) {
        int before = diag.count() + diag.dropped();
        var pat = pattern(p, type, new LinkedHashMap<>(), null);
        // A pattern that is already wrong (e.g. the wrong tuple arity) needs no second error.
        boolean reported = diag.count() + diag.dropped() > before;
        if (type != Type.ERROR && !reported && !covers(pat, type)) {
            diag.error(Code.REFUTABLE_PATTERN, p.span(), "this pattern does not match every '" + type
                    + "'; `let`, `for` and lambda parameters need irrefutable patterns");
        }
        return pat;
    }

    /**
     * Checks {@code p} against values of type {@code type}. Bindings are declared in the current
     * scope; {@code bindings} collects them by name, and in an or-pattern {@code reuse} supplies the
     * first alternative's locals so every alternative binds the same ones.
     */
    private Ir.Pat pattern(Pattern p, Type type, Map<String, Local> bindings, Map<String, Local> reuse) {
        return switch (p) {
            case Pattern.Wildcard w -> new Ir.PWild();
            case Pattern.Binding b -> {
                Ir.Pat sub = null;
                Type bound = type;
                boolean unbox = false;
                if (b.isType() != null) {
                    var t = c.types.resolve(b.isType(), c.ctx.typeScope);
                    sub = new Ir.PIs(t);
                    if (type != Type.ERROR && t != Type.ERROR && !c.exprs.castPossible(type, t)) {
                        diag.error(Code.IMPOSSIBLE_CAST, b.span(), "a value of type '" + type + "' is never a '" + t + "'");
                    }
                    if (b.isNot()) {
                        bound = remainder(sub, type);
                        sub = new Ir.PNot(sub);
                    } else {
                        bound = c.exprs.narrowIs(type, t);
                    }
                    unbox = TypeSystem.isStruct(bound);
                }
                yield new Ir.PBind(bind(b.name().text(), b.isMutable(), TypeSystem.widenInferred(bound), b.span(), bindings, reuse, typeNameHint),
                        sub, unbox);
            }
            case Pattern.Is is -> {
                var t = c.types.resolve(is.type(), c.ctx.typeScope);
                if (type != Type.ERROR && t != Type.ERROR && !c.exprs.castPossible(type, t)) {
                    diag.error(Code.IMPOSSIBLE_CAST, is.span(), "a value of type '" + type + "' is never a '" + t + "'");
                }
                yield new Ir.PIs(t);
            }
            case Pattern.Not n -> {
                var inner = new LinkedHashMap<String, Local>();
                var pat = pattern(n.pattern(), type, inner, null);
                for (var name : inner.keySet()) {
                    diag.error(Code.BAD_PATTERN, n.pattern().span(), "'" + name + "' cannot be bound under `not`, "
                            + "which matches only when the pattern after it does not (§11)");
                }
                yield new Ir.PNot(pat);
            }
            case Pattern.Relational r -> {
                var value = literal(r.value(), type, r.value().span());
                boolean ordered = value instanceof Values.Int || value instanceof Values.Flt
                        || value instanceof Values.Char || value instanceof Values.Str;
                if (type != Type.ERROR && !ordered) {
                    diag.error(Code.BAD_PATTERN, r.span(), "relational patterns match numbers, chars or strings, not '" + type + "'");
                }
                yield new Ir.PRel(r.op(), value);
            }
            case Pattern.Literal l -> new Ir.PLit(literal(l.value(), type, l.span()));
            case Pattern.Range r -> {
                var lo = literal(r.low(), type, r.low().span());
                var hi = literal(r.high(), type, r.high().span());
                var p0 = TypeSystem.prim(type instanceof Type.Union ? Type.ERROR : type);
                if (type != Type.ERROR && (p0 == null || !(p0.isInteger() || p0 == Prim.CHAR))) {
                    diag.error(Code.BAD_PATTERN, r.span(), "range patterns match integers or chars, not '" + type + "'");
                }
                yield new Ir.PRange(lo, hi, r.isInclusive());
            }
            case Pattern.Unit u -> new Ir.PLit(Values.Unit.UNIT);
            case Pattern.Tuple t -> {
                var tuple = memberOf(type, Type.Tuple.class, tu -> tu.elements().size() == t.elements().size());
                if (tuple == null && type != Type.ERROR) {
                    diag.error(Code.BAD_PATTERN, t.span(), "a " + t.elements().size() + "-tuple pattern cannot match '" + type + "'");
                }
                var pats = new ArrayList<Ir.Pat>();
                for (int i = 0; i < t.elements().size(); i++) {
                    pats.add(pattern(t.elements().get(i), tuple == null ? Type.ERROR : tuple.elements().get(i), bindings, reuse));
                }
                yield new Ir.PTuple(pats);
            }
            case Pattern.Or o -> {
                var alts = new ArrayList<Ir.Pat>();
                Map<String, Local> first = null;
                for (var alt : o.alternatives()) {
                    var mine = new LinkedHashMap<String, Local>();
                    alts.add(pattern(alt, type, mine, first == null ? reuse : first));
                    if (first == null) {
                        first = mine;
                    } else if (!mine.keySet().equals(first.keySet())) {
                        diag.error(Code.BAD_PATTERN, alt.span(), "every alternative must bind the same names: "
                                + first.keySet() + " vs " + mine.keySet());
                    }
                }
                if (first != null) bindings.putAll(first);
                yield new Ir.POr(alts);
            }
            case Pattern.ListPattern l -> listPattern(l, type, bindings, reuse);
            case Pattern.Rest r -> {
                diag.error(Code.BAD_PATTERN, r.span(), "`..` is only allowed in list patterns");
                yield new Ir.PWild();
            }
            case Pattern.Struct s -> structPattern(s, type, bindings, reuse);
            case Pattern.Path pp -> pathPattern(pp, type);
        };
    }

    private Local bind(String name, boolean mutable, Type type, Span span, Map<String, Local> bindings,
                       Map<String, Local> reuse, String hint) {
        if (bindings.containsKey(name)) {
            diag.error(Code.BAD_PATTERN, span, "'" + name + "' is bound twice in this pattern");
        }
        Local local;
        if (reuse != null && reuse.containsKey(name)) {
            local = reuse.get(name);
            if (!local.type.equals(type)) local.type = TypeSystem.union(local.type, type);
        } else {
            local = c.declare(name, mutable, type, span, hint);
        }
        bindings.put(name, local);
        return local;
    }

    /**
     * The type patterns are checked against: a single literal type is widened (`match 5` matches
     * `i32` patterns), a union of literals is kept for exhaustiveness.
     */
    private static Type subject(Type t) {
        return t instanceof Type.Lit l ? l.base() : t;
    }

    // ---- Pattern tests after `is` (§7.3) ----

    /**
     * {@code x is p}: checks {@code p} against {@code type}. Its bindings, collected in
     * {@code bindings}, are declared in the current scope as conditional locals: unbound until the
     * test's true branch says otherwise.
     */
    Ir.Pat test(Pattern p, Type type, Map<String, Local> bindings) {
        var saved = typeNameHint;
        typeNameHint = "after `is`, `[` and `(` start a pattern (§7.3); test a list type with `List<T>`, "
                + "a tuple's element types with `(is A, is B)`";
        var pat = pattern(p, subject(type), bindings, null);
        typeNameHint = saved;
        for (Local l : bindings.values()) l.isConditional = true;
        return pat;
    }

    /** What {@code x} is known to be when {@code x is pat} holds: the members it may match. */
    Type possible(Ir.Pat pat, Type type) {
        if (pat instanceof Ir.PIs is) return c.exprs.narrowIs(type, is.type());
        if (pat instanceof Ir.PBind b && b.sub() instanceof Ir.PIs is) return c.exprs.narrowIs(type, is.type());
        var out = new ArrayList<Type>();
        for (Type m : TypeSystem.members(type)) if (mayMatch(pat, m)) out.add(m);
        return out.isEmpty() ? type : TypeSystem.union(out);
    }

    private boolean mayMatch(Ir.Pat pat, Type m) {
        var e = TypeSystem.expand(m);
        return switch (pat) {
            case Ir.PWild w -> true;
            case Ir.PBind b -> b.sub() == null || mayMatch(b.sub(), m);
            case Ir.PIs is -> mayBe(m, is.type());
            case Ir.PLit l -> l.value() == null ? m == Type.NULL : m != Type.NULL;
            case Ir.PRange r -> e instanceof Prim || e instanceof Type.Lit;
            case Ir.PRel r -> e instanceof Prim || e instanceof Type.Lit;
            case Ir.PNot n -> !coversAtom(n.pattern(), m);
            case Ir.PTuple t -> e instanceof Type.Tuple tu && tu.elements().size() == t.elements().size();
            case Ir.PList l -> e instanceof Type.Array || (e instanceof Type.Nominal n && n.decl() == ts.list);
            case Ir.PStruct st -> e instanceof Type.Nominal;
            case Ir.POr o -> o.alternatives().stream().anyMatch(a -> mayMatch(a, m));
        };
    }

    /**
     * Whether a value of member type {@code m} may be a {@code t}. Only a class, trait or type
     * parameter can hold a subtype implementing more than {@code m} declares.
     */
    private boolean mayBe(Type m, Type t) {
        var e = TypeSystem.expand(m);
        boolean open = e instanceof Type.Param || TypeSystem.isTraitRef(e) || (e instanceof Type.Nominal n && n.decl().isClass());
        return open ? c.exprs.castPossible(m, t) : ts.assignable(m, t);
    }

    @SuppressWarnings("unchecked")
    private <T extends Type> T memberOf(Type type, Class<T> kind, java.util.function.Predicate<T> ok) {
        for (Type m : TypeSystem.members(type)) {
            var e = TypeSystem.expand(m);
            if (kind.isInstance(e) && ok.test((T) e)) return (T) e;
        }
        return null;
    }

    /** A literal pattern's value, typed against the scrutinee (integer literals adopt its type). */
    private Object literal(Expr.Literal lit, Type type, Span span) {
        var t = c.exprs.expr(lit, type == Type.ERROR ? null : type);
        if (type != Type.ERROR && t.type() != Type.ERROR && !ts.assignable(t.type(), type)
                && !(lit instanceof Expr.NullLit && TypeSystem.containsNull(type))) {
            diag.error(Code.BAD_PATTERN, span, "a '" + t.type() + "' pattern cannot match '" + type + "'");
        }
        return ((Ir.Lit) t.ir()).value();
    }

    private Ir.Pat listPattern(Pattern.ListPattern l, Type type, Map<String, Local> bindings, Map<String, Local> reuse) {
        Type element = null;
        for (Type m : TypeSystem.members(type)) {
            var e = TypeSystem.expand(m);
            if (e instanceof Type.Nominal n && n.decl() == ts.list) element = n.args().getFirst();
            if (e instanceof Type.Array a) element = a.element();
        }
        if (element == null) {
            if (type != Type.ERROR) diag.error(Code.BAD_PATTERN, l.span(), "a list pattern cannot match '" + type + "'");
            element = Type.ERROR;
        }
        var head = new ArrayList<Ir.Pat>();
        var tail = new ArrayList<Ir.Pat>();
        boolean rest = false;
        Local restLocal = null;
        for (var e : l.elements()) {
            if (e instanceof Pattern.Rest r) {
                if (rest) diag.error(Code.BAD_PATTERN, r.span(), "only one `..` per list pattern");
                rest = true;
                if (r.name() != null) {
                    restLocal = bind(r.name().text(), false, ts.listOf(element), r.span(), bindings, reuse, typeNameHint);
                }
                continue;
            }
            (rest ? tail : head).add(pattern(e, element, bindings, reuse));
        }
        return new Ir.PList(head, rest, restLocal, tail, element);
    }

    private Ir.Pat structPattern(Pattern.Struct s, Type type, Map<String, Local> bindings, Map<String, Local> reuse) {
        var target = c.exprs.pathTarget(s.type(), false);
        if (!(target instanceof Exprs.TypeTarget tt) || tt.decl() == null || !(tt.decl().isStruct() || tt.decl().isClass())) {
            if (!(target instanceof Exprs.ErrorTarget)) {
                diag.error(Code.BAD_PATTERN, s.type().span(), "a struct pattern needs a struct or class type");
            }
            return new Ir.PWild();
        }
        var decl = tt.decl();
        // The scrutinee's instance of the type, for its type arguments.
        Type.Nominal inst = memberOf(type, Type.Nominal.class, n -> n.decl() == decl);
        if (inst == null && type != Type.ERROR) {
            if (!c.exprs.castPossible(type, tt.type())) {
                diag.error(Code.BAD_PATTERN, s.type().span(), "a '" + decl.name() + "' pattern cannot match '" + type + "'");
            }
            inst = tt.type() instanceof Type.Nominal n ? n : null;
        }
        var env = inst == null ? Map.<TypeParamInfo, Type>of() : TypeSystem.bind(decl.typeParams, inst.args());
        var fields = new ArrayList<FieldInfo>();
        var pats = new ArrayList<Ir.Pat>();
        for (var fp : s.fields()) {
            var f = decl.field(fp.field().text());
            if (f == null) {
                diag.error(Code.BAD_FIELD, fp.field().span(), "'" + decl.name() + "' has no field '" + fp.field().text() + "'");
                continue;
            }
            c.index.ref(fp.field().span(), f, null);
            if (!f.owner().isStruct()) c.exprs.checkAccess(f.owner(), f.visibility, "field '" + f.name() + "'", fp.field().span());
            var base = inst == null ? null : f.owner() == decl ? inst : TypeSystem.baseInstance(inst, f.owner());
            var ft = base == null ? Type.ERROR : TypeSystem.subst(f.type, TypeSystem.bind(f.owner().typeParams, base.args()));
            fields.add(f);
            pats.add(pattern(fp.pattern(), ft, bindings, reuse));
        }
        if (!s.hasRest()) {
            var missing = decl.allFields.stream().filter(f -> fields.stream().noneMatch(g -> g == f)).map(FieldInfo::name).toList();
            if (!missing.isEmpty()) {
                diag.error(Code.BAD_PATTERN, s.span(), "pattern does not mention field(s) " + missing + "; add `..` to ignore them");
            }
        }
        return new Ir.PStruct(decl, fields, pats);
    }

    private Ir.Pat pathPattern(Pattern.Path pp, Type type) {
        var target = c.exprs.pathTarget(pp.path(), false);
        return switch (target) {
            case Exprs.ValueTarget v when v.value().ir() instanceof Ir.Lit lit -> {
                if (type != Type.ERROR && !ts.assignable(v.value().type(), type) && !c.exprs.castPossible(type, v.value().type())) {
                    diag.error(Code.BAD_PATTERN, pp.span(), "a '" + v.value().type() + "' pattern cannot match '" + type + "'");
                }
                yield new Ir.PLit(lit.value());
            }
            case Exprs.TypeTarget t when t.decl() != null && t.decl().isStruct() && t.decl().allFields.isEmpty() ->
                    new Ir.PIs(t.type());
            case Exprs.ErrorTarget e -> new Ir.PWild();
            default -> {
                diag.error(Code.BAD_PATTERN, pp.span(), "expected an enum member or constant in this pattern");
                yield new Ir.PWild();
            }
        };
    }

    // ---- match (§7.11) ----

    /**
     * {@code statement}: the match is an expression statement, so arm values are discarded and
     * block arms need not diverge.
     */
    Typed match(Expr.Match m, Type expected, boolean statement) {
        var ctx = c.ctx;
        var scrutinee = c.exprs.expr(m.scrutinee(), null);
        var scrType = subject(scrutinee.type());
        Local subject = m.scrutinee() instanceof Expr.Path p && p.segments().size() == 1
                ? c.lookupLocal(p.segments().getFirst().name().text()) : null;
        if (m.scrutinee() instanceof Expr.Self) subject = ctx.self;
        var saved = ctx.narrowed;
        var arms = new ArrayList<Ir.Arm>();
        var armTypes = new ArrayList<Type>();
        var unguarded = new ArrayList<Ir.Pat>();
        boolean anyReach = false;
        Type remaining = scrType;
        var seenLiterals = new ArrayList<Object>();
        for (var arm : m.arms()) {
            if (!unguarded.isEmpty() && scrType != Type.ERROR && missing(unguarded, scrType) == null) {
                c.lints.unreachableArm(arm, "the arms before it match every value");
            }
            var outer = ctx.scope;
            ctx.scope = new Checker.Scope(outer);
            ctx.narrowed = saved;
            int errors = diag.count() + diag.dropped();
            var hint = typeNameHint;
            if (arm.pattern() instanceof Pattern.Binding b && b.isType() == null) {
                typeNameHint = "to test the type, write `is " + b.name().text() + "`";
            }
            var pat = pattern(arm.pattern(), remaining == Type.NEVER ? scrType : remaining, new LinkedHashMap<>(), null);
            typeNameHint = hint;
            boolean patternOk = diag.count() + diag.dropped() == errors;
            if (subject != null) {
                var narrowedTo = matchedType(pat, remaining);
                if (narrowedTo != null) c.narrow(subject, narrowedTo);
            }
            Ir.Expr guard = null;
            if (arm.guard() != null) {
                var cond = c.exprs.condition(arm.guard());
                guard = cond.ir();
                ctx.narrowed = Checker.with(ctx.narrowed, cond.whenTrue());
            }
            ctx.reachable = true;
            Ir.Stmt body = null;
            Ir.Expr value = null;
            switch (arm.body()) {
                case Expr e -> {
                    var t = c.exprs.expr(e, statement ? null : expected);
                    value = t.ir();
                    if (t.type() == Type.NEVER) ctx.reachable = false;
                    else armTypes.add(t.type());
                    if (statement) c.mustUse(t.type(), e.span());
                }
                case Stmt.Block b -> {
                    body = c.block(b);
                    if (ctx.reachable && !statement) {
                        diag.error(Code.TYPE_MISMATCH, b.span(), "a block arm yields no value: it must `return`, `break`, `continue` or panic");
                    }
                }
                case Stmt.Assign a -> {
                    body = c.assign(a);
                    armTypes.add(Type.UNIT);
                }
            }
            anyReach |= ctx.reachable;
            ctx.scope = outer;
            arms.add(new Ir.Arm(pat, guard, body, value));
            var literals = literals(pat);
            if (literals != null && !literals.isEmpty() && seenLiterals.containsAll(literals)
                    && patternOk
                    && (unguarded.isEmpty() || missing(unguarded, scrType) != null)) {
                c.lints.unreachableArm(arm, "earlier arms match " + (literals.size() == 1 ? "this value" : "these values"));
            }
            if (arm.guard() == null) {
                if (literals != null) seenLiterals.addAll(literals);
                unguarded.add(pat);
                remaining = remainder(pat, remaining);
            }
        }
        ctx.narrowed = saved;
        if (scrType != Type.ERROR) {
            var missing = missing(unguarded, scrType);
            if (missing != null) {
                diag.error(Code.NON_EXHAUSTIVE, m.scrutinee().span(), "match is not exhaustive: " + missing
                        + " is not covered; add an arm or `_ =>`");
            }
        }
        ctx.reachable = anyReach;
        Type type = statement ? Type.UNIT
                : TypeSystem.union(expected == null ? armTypes.stream().map(TypeSystem::widenInferred).toList() : armTypes);
        if (!statement && !anyReach) type = Type.NEVER;
        if (armTypes.contains(Type.ERROR)) type = Type.ERROR;
        return new Typed(new Ir.Match(scrutinee.ir(), arms, m.span()), type);
    }

    /** The values a pattern of literals (`1`, `1 | 2`, `Color::Red`) matches; null for any other pattern. */
    private static List<Object> literals(Ir.Pat pat) {
        return switch (pat) {
            case Ir.PLit l -> new ArrayList<>(Collections.singletonList(l.value()));
            case Ir.POr o -> {
                var out = new ArrayList<Object>();
                for (var a : o.alternatives()) {
                    var ls = literals(a);
                    if (ls == null) yield null;
                    out.addAll(ls);
                }
                yield out;
            }
            default -> null;
        };
    }

    /** What the scrutinee is known to be inside an arm (for narrowing the scrutinee local). */
    private Type matchedType(Ir.Pat pat, Type remaining) {
        return switch (pat) {
            case Ir.PIs is -> c.exprs.narrowIs(remaining, is.type());
            case Ir.PBind b when b.sub() instanceof Ir.PIs is -> c.exprs.narrowIs(remaining, is.type());
            case Ir.PBind b when b.sub() instanceof Ir.PNot n -> remainder(n.pattern(), remaining);
            case Ir.PBind b -> remaining;
            case Ir.PWild w -> remaining;
            case Ir.PLit l when l.value() == null -> Type.NULL;
            case Ir.PNot n -> remainder(n.pattern(), remaining);
            case Ir.PRel r -> possible(r, remaining);
            default -> null;
        };
    }

    /** The scrutinee's type after an unguarded arm with this pattern did not match. */
    Type remainder(Ir.Pat pat, Type t) {
        var out = new ArrayList<Type>();
        for (Type m : TypeSystem.members(t)) {
            if (!coversAtom(pat, m)) out.add(m);
        }
        return TypeSystem.union(out);
    }

    // ---- Exhaustiveness ----

    /** Whether {@code pat} matches every value of {@code type}. */
    boolean covers(Ir.Pat pat, Type type) {
        return missing(List.of(pat), type) == null;
    }

    /** A description of a value no pattern covers, or null when exhaustive. */
    private String missing(List<Ir.Pat> pats, Type type) {
        for (Type m : TypeSystem.members(type)) {
            for (Object atom : atoms(m)) {
                boolean covered = false;
                for (var p : pats) if (coversValue(p, atom, m)) covered = true;
                if (covered) continue;
                if (atom instanceof ListAtom) {
                    var gap = listGap(pats, m);
                    if (gap == null) continue;
                    return gap;
                }
                return describe(atom, m);
            }
        }
        return null;
    }

    private record ListAtom() {}

    /** The atoms a member type splits into: bool values, enum members, else the type itself. */
    private List<Object> atoms(Type m) {
        var e = TypeSystem.expand(m);
        if (e == Prim.BOOL) return List.of(true, false);
        if (e instanceof Type.Nominal n && n.decl().isEnum() && !n.decl().isFlags) {
            var vals = c.consts.enumValues(n.decl());
            if (!vals.isEmpty()) return new ArrayList<>(vals.entrySet().stream()
                    .map(en -> (Object) new Values.EnumVal(n.decl(), en.getValue())).toList());
        }
        if (e instanceof Type.Nominal n && n.decl() == ts.list) return List.of(new ListAtom());
        return List.of(e);
    }

    private String describe(Object atom, Type m) {
        return switch (atom) {
            case Boolean b -> "`" + b + "`";
            case Values.EnumVal v -> "`" + v.decl().name() + "::" + c.consts.enumName(v) + "`";
            case Type.Lit l -> "`" + l + "`";
            default -> m == Type.NULL ? "`null`" : "a value of type '" + m + "'";
        };
    }

    private boolean coversAtom(Ir.Pat pat, Type m) {
        for (Object atom : atoms(m)) {
            if (atom instanceof ListAtom) {
                if (listGap(List.of(pat), m) != null) return false;
            } else if (!coversValue(pat, atom, m)) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code pat} matches every value of the atom (a member type or a single value). */
    private boolean coversValue(Ir.Pat pat, Object atom, Type member) {
        return switch (pat) {
            case Ir.PWild w -> true;
            case Ir.PBind b -> b.sub() == null || coversValue(b.sub(), atom, member);
            case Ir.PIs is -> ts.assignable(member, is.type());
            case Ir.POr o -> o.alternatives().stream().anyMatch(a -> coversValue(a, atom, member));
            case Ir.PLit l -> switch (atom) {
                case Boolean b -> Objects.equals(l.value(), b);
                case Values.EnumVal v -> v.equals(l.value());
                case Type.Lit lit -> sameLiteral(l.value(), lit);
                case Type.Special s when s == Type.Special.NULL -> l.value() == null;
                case Type.Special s when s == Type.Special.UNIT -> l.value() == Values.Unit.UNIT;
                default -> false;
            };
            case Ir.PTuple t -> member instanceof Type.Tuple tu && tu.elements().size() == t.elements().size()
                    && allCover(t.elements(), tu.elements());
            case Ir.PStruct s -> member instanceof Type.Nominal n && n.decl() == s.decl() && structCovers(s, n);
            case Ir.PList l -> member instanceof Type.Array a && l.head().size() + l.tail().size() <= a.size()
                    && (l.hasRest() || l.head().size() == a.size())
                    && l.head().stream().allMatch(p -> covers(p, a.element()))
                    && l.tail().stream().allMatch(p -> covers(p, a.element()));
            case Ir.PRange r -> false;
            case Ir.PRel r -> false;
            case Ir.PNot n -> neverMatches(n.pattern(), atom, member);
        };
    }

    /** Whether {@code pat} matches no value of the atom: what makes `not pat` cover it. Conservative. */
    private boolean neverMatches(Ir.Pat pat, Object atom, Type member) {
        if (!mayMatch(pat, member)) return true;
        boolean single = atom instanceof Boolean || atom instanceof Values.EnumVal || atom instanceof Type.Lit
                || atom == Type.NULL || atom == Type.UNIT;
        return switch (pat) {
            case Ir.PLit l -> single && !coversValue(l, atom, member);
            case Ir.POr o -> o.alternatives().stream().allMatch(a -> neverMatches(a, atom, member));
            case Ir.PNot n -> coversValue(n.pattern(), atom, member);
            case Ir.PBind b -> b.sub() != null && neverMatches(b.sub(), atom, member);
            default -> false;
        };
    }

    private boolean allCover(List<Ir.Pat> pats, List<Type> types) {
        for (int i = 0; i < pats.size(); i++) if (!covers(pats.get(i), types.get(i))) return false;
        return true;
    }

    private boolean structCovers(Ir.PStruct s, Type.Nominal n) {
        for (int i = 0; i < s.fields().size(); i++) {
            var f = s.fields().get(i);
            var base = f.owner() == n.decl() ? n : TypeSystem.baseInstance(n, f.owner());
            var ft = TypeSystem.subst(f.type, TypeSystem.bind(f.owner().typeParams, base.args()));
            if (!covers(s.patterns().get(i), ft)) return false;
        }
        return true;
    }

    private static boolean sameLiteral(Object value, Type.Lit lit) {
        return switch (value) {
            case Values.Str s -> s.toString().equals(lit.value());
            case Values.Int i -> lit.value() instanceof BigInteger b && i.big().equals(b);
            case Boolean b -> b.equals(lit.value());
            case Values.Char ch -> lit.value() instanceof Integer cp && ch.cp() == cp;
            case null, default -> false;
        };
    }

    /** List patterns cover `[T]` when some `[p.., ..]` covers lengths ≥ k and exact ones the rest. */
    private String listGap(List<Ir.Pat> pats, Type m) {
        var n = (Type.Nominal) TypeSystem.expand(m);
        var element = n.args().getFirst();
        int restMin = Integer.MAX_VALUE;
        var exact = new java.util.HashSet<Integer>();
        for (var p : pats) {
            var lp = listOf(p);
            if (lp == null) {
                if (p instanceof Ir.PWild || (p instanceof Ir.PBind b && (b.sub() == null || coversValue(b.sub(), m, m)))
                        || (p instanceof Ir.PIs is && ts.assignable(m, is.type()))
                        || (p instanceof Ir.PNot && coversValue(p, new ListAtom(), m))) return null;
                continue;
            }
            boolean irrefutable = lp.head().stream().allMatch(x -> covers(x, element))
                    && lp.tail().stream().allMatch(x -> covers(x, element));
            if (!irrefutable) continue;
            int len = lp.head().size() + lp.tail().size();
            if (lp.hasRest()) restMin = Math.min(restMin, len);
            else exact.add(len);
        }
        if (restMin == Integer.MAX_VALUE) return "a list of length " + firstMissing(exact);
        for (int i = 0; i < restMin; i++) if (!exact.contains(i)) return "a list of length " + i;
        return null;
    }

    private static Ir.PList listOf(Ir.Pat p) {
        if (p instanceof Ir.PList l) return l;
        if (p instanceof Ir.PBind b && b.sub() instanceof Ir.PList l) return l;
        return null;
    }

    private static int firstMissing(java.util.Set<Integer> s) {
        int i = 0;
        while (s.contains(i)) i++;
        return i;
    }

    static Map<String, Local> none() {
        return new HashMap<>();
    }
}
