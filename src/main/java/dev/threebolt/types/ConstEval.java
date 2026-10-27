package dev.threebolt.types;

import dev.threebolt.ast.Expr;
import dev.threebolt.ast.Item;
import dev.threebolt.diag.Code;
import dev.threebolt.diag.Span;
import dev.threebolt.interp.Ops;
import dev.threebolt.interp.Panic;
import dev.threebolt.interp.Values;
import dev.threebolt.ir.Ir;
import dev.threebolt.types.Checker.Typed;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compile-time evaluation (§4.2): `const` initializers, enum member values (§4.11) and array
 * sizes. A constant expression is checked like any expression, then its IR is evaluated; IR other
 * than literals, operators and numeric casts is not constant.
 */
final class ConstEval {
    private final Checker c;
    private final Map<TypeInfo, Map<String, Long>> enums = new HashMap<>();
    private final java.util.Set<TypeInfo> evaluatingEnums = new java.util.HashSet<>();

    ConstEval(Checker c) {
        this.c = c;
    }

    private static final class NotConstant extends RuntimeException {
        NotConstant() {
            super(null, null, false, false);
        }
    }

    /** A reference to a const as an expression. */
    Typed reference(ConstInfo k, Span span) {
        var v = value(k);
        if (k.failed) return Exprs.error();
        return new Typed(new Ir.Lit(v), k.type);
    }

    Object value(ConstInfo k) {
        if (k.evaluated) return k.value;
        if (k.evaluating) {
            c.diag.error(Code.CYCLE, k.ast.name().span(), "const '" + k.name() + "' depends on itself");
            k.failed = true;
            k.evaluated = true;
            return null;
        }
        k.evaluating = true;
        var ctx = c.moduleCtx(k.module);
        ctx.scope = new Checker.Scope(null);
        var saved = c.ctx;
        c.ctx = ctx;
        try {
            var ir = c.check(k.ast.value(), k.type);
            var v = eval(ir, k.ast.value().span());
            if (v == FAILED) k.failed = true;
            else k.value = v;
        } finally {
            c.ctx = saved;
            k.evaluating = false;
            k.evaluated = true;
        }
        return k.value;
    }

    private static final Object FAILED = new Object();

    /** Evaluates checked IR as a constant; reports and returns {@link #FAILED} if it is not one. */
    private Object eval(Ir.Expr ir, Span span) {
        try {
            return run(ir);
        } catch (NotConstant e) {
            c.diag.error(Code.NOT_CONSTANT, span, "not a constant expression");
        } catch (Panic p) {
            c.diag.error(Code.NOT_CONSTANT, span, "constant evaluation failed: " + p.getMessage());
        }
        return FAILED;
    }

    private Object run(Ir.Expr ir) {
        return switch (ir) {
            case Ir.Lit l -> l.value();
            case Ir.Unary u -> {
                var v = run(u.operand());
                yield u.op() == Ir.UnOp.NEG ? Ops.negate(v) : Ops.bitNot((Values.Int) v);
            }
            case Ir.Binary b when b.flags() != null -> {
                var l = (Values.EnumVal) run(b.left());
                var r = (Values.EnumVal) run(b.right());
                long v = switch (b.op()) {
                    case BIT_AND -> l.v() & r.v();
                    case BIT_OR -> l.v() | r.v();
                    default -> l.v() ^ r.v();
                };
                yield new Values.EnumVal(b.flags(), v);
            }
            case Ir.Binary b -> Ops.binary(b.op(), b.prim(), run(b.left()), run(b.right()));
            case Ir.Not n -> !(Boolean) run(n.operand());
            case Ir.And a -> (Boolean) run(a.left()) && (Boolean) run(a.right());
            case Ir.Or o -> (Boolean) run(o.left()) || (Boolean) run(o.right());
            case Ir.Truthy t -> {
                var v = run(t.operand());
                if (!(v instanceof Boolean)) throw new NotConstant();
                yield v;
            }
            case Ir.Cond k -> (Boolean) run(k.condition()) ? run(k.then()) : run(k.otherwise());
            case Ir.Coerce co -> {
                var v = run(co.operand());
                var p = TypeSystem.prim(co.type());
                yield p != null && p.isNumeric() && (v instanceof Values.Int || v instanceof Values.Flt) ? Ops.cast(v, p) : v;
            }
            case Ir.Cast cast -> {
                var v = run(cast.operand());
                var p = TypeSystem.prim(cast.type());
                if (p == null || !(v instanceof Values.Int || v instanceof Values.Flt)) throw new NotConstant();
                yield Ops.cast(v, p);
            }
            case Ir.Eq e -> {
                var l = run(e.left());
                var r = run(e.right());
                yield e.negate() != java.util.Objects.equals(l, r);
            }
            default -> throw new NotConstant();
        };
    }

    /** An array size (§3.2) or other `usize` constant; null after an error. */
    Long arraySize(Expr e, TypeResolver.Scope scope) {
        var ctx = c.moduleCtx(scope.module());
        ctx.scope = new Checker.Scope(null);
        var saved = c.ctx;
        c.ctx = ctx;
        try {
            return constLong(e, Prim.USIZE);
        } finally {
            c.ctx = saved;
        }
    }

    Long constLong(Expr e, Prim type) {
        var v = eval(c.check(e, type), e.span());
        return v instanceof Values.Int i ? i.v() : null;
    }

    // ---- Enums (§4.11) ----

    /** Member values of an enum, by name, in declaration order. */
    Map<String, Long> enumValues(TypeInfo t) {
        var done = enums.get(t);
        if (done != null) return done;
        if (!evaluatingEnums.add(t)) {
            c.diag.error(Code.CYCLE, t.span(), "the values of enum '" + t.name() + "' depend on themselves");
            return Map.of();
        }
        var values = new LinkedHashMap<String, Long>();
        var ctx = c.moduleCtx(t.module);
        ctx.scope = new Checker.Scope(null);
        var saved = c.ctx;
        c.ctx = ctx;
        BigInteger previous = null;
        try {
            for (Item.EnumMember m : ((Item.Enum) t.item).members()) {
                BigInteger v;
                if (m.value() != null) {
                    var r = eval(c.check(m.value(), t.backing), m.value().span());
                    v = r instanceof Values.Int i ? i.big() : BigInteger.ZERO;
                } else if (t.isFlags) {
                    v = BigInteger.ONE;
                    while (previous != null && v.compareTo(previous) <= 0) v = v.shiftLeft(1);
                } else {
                    v = previous == null ? BigInteger.ZERO : previous.add(BigInteger.ONE);
                }
                if (!t.backing.fits(v)) {
                    c.diag.error(Code.BAD_ENUM, m.span(), "value " + v + " of '" + m.name().text()
                            + "' does not fit in '" + t.backing + "'");
                }
                if (values.containsKey(m.name().text())) {
                    c.diag.error(Code.BAD_ENUM, m.name().span(), "'" + m.name().text() + "' is declared twice");
                }
                values.put(m.name().text(), Ops.wrapBig(t.backing, v).v());
                previous = v;
            }
        } finally {
            c.ctx = saved;
            evaluatingEnums.remove(t);
        }
        enums.put(t, values);
        t.enumMembers.putAll(values);
        return values;
    }

    String enumName(Values.EnumVal v) {
        for (var e : enumValues(v.decl()).entrySet()) if (e.getValue() == v.v()) return e.getKey();
        return Long.toString(v.v());
    }
}
