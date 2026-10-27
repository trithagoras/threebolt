package dev.threebolt.types;

import dev.threebolt.ast.Dispatch;
import dev.threebolt.ast.Expr;
import dev.threebolt.ast.TypeExpr;
import dev.threebolt.ast.Visibility;
import dev.threebolt.diag.Code;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Span;
import dev.threebolt.ir.Ir;
import dev.threebolt.types.Checker.Typed;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Calls (§7.5): method lookup (§8.5), overload resolution (§4.4) and generic inference (§10). */
final class Calls {
    /**
     * A function that a call might invoke. {@code env} binds the owner's type parameters (and a
     * trait's Self) as far as known; {@code vars} are owner parameters still to infer.
     * {@code ufcs}: {@code Trait::m(x, ...)}, the first argument is the receiver.
     */
    record Candidate(FnInfo fn, Ir.CallKind kind, Map<TypeParamInfo, Type> env, Set<TypeParamInfo> vars,
                     Type staticSelf, boolean ufcs) {
        static Candidate direct(FnInfo f) {
            return new Candidate(f, Ir.CallKind.DIRECT, Map.of(), Set.of(), null, false);
        }
    }

    /** The outcome of binding arguments to a candidate. */
    record Bound(Candidate cand, List<Ir.Expr> args, int[] order, List<Type> typeArgs, List<Type> ownerArgs, Type ret) {}

    private final Checker c;
    private final TypeSystem ts;
    private final Diagnostics diag;

    Calls(Checker c) {
        this.c = c;
        this.ts = c.ts;
        this.diag = c.diag;
    }

    Typed call(Expr.Call e, Type expected) {
        return switch (e.callee()) {
            case Expr.Member m when m.receiver() instanceof Expr.Super -> superCall(e, m);
            case Expr.Member m -> methodCall(e, m);
            case Expr.Path p -> pathCall(e, p);
            default -> valueCall(e, c.exprs.expr(e.callee(), null));
        };
    }

    private Typed valueCall(Expr.Call e, Typed fn) {
        var t = TypeSystem.expand(fn.type());
        if (t == Type.ERROR) {
            for (var a : e.args()) c.exprs.expr(a.value(), null);
            return Exprs.error();
        }
        if (!(t instanceof Type.Fn f)) {
            diag.error(Code.NOT_CALLABLE, e.callee().span(), "'" + fn.type() + "' is not a function");
            for (var a : e.args()) c.exprs.expr(a.value(), null);
            return Exprs.error();
        }
        if (f.isExtern() && !c.inUnsafe()) {
            diag.error(Code.UNSAFE_REQUIRED, e.span(), "calling an `extern fn` pointer requires an unsafe context");
        }
        var args = new ArrayList<Ir.Expr>();
        for (int i = 0; i < e.args().size(); i++) {
            var a = e.args().get(i);
            if (a.name() != null) {
                diag.error(Code.BAD_ARGUMENTS, a.span(), "function values take only positional arguments");
            }
            var pt = i < f.params().size() ? f.params().get(i) : null;
            args.add(pt == null ? c.exprs.expr(a.value(), null).ir() : c.check(a.value(), pt));
        }
        if (e.args().size() != f.params().size()) {
            diag.error(Code.BAD_ARGUMENTS, e.span(), "expected " + f.params().size() + " argument(s), found " + e.args().size());
        }
        return new Typed(new Ir.CallValue(fn.ir(), args, e.span()), f.ret());
    }

    private Typed pathCall(Expr.Call e, Expr.Path p) {
        var target = c.exprs.pathTarget(p, true);
        return switch (target) {
            case Exprs.LocalTarget l -> valueCall(e, c.readLocal(l.local(), p.span()));
            case Exprs.ValueTarget v -> valueCall(e, v.value());
            case Exprs.StaticTarget s -> valueCall(e, new Typed(new Ir.Read(new Ir.StaticPlace(s.info())), s.info().type));
            case Exprs.FnTarget f -> {
                var bound = resolve(f.candidates(), null, e.args(), f.typeArgs(), e.span(), f.name());
                yield bound == null ? Exprs.error() : build(bound, null, null, e.span());
            }
            case Exprs.TypeTarget t -> {
                diag.error(Code.NOT_CALLABLE, p.span(), "'" + t.type() + "' is a type, not a function; use `"
                        + t.type() + " { ... }` or a constructor function");
                for (var a : e.args()) c.exprs.expr(a.value(), null);
                yield Exprs.error();
            }
            case Exprs.ModuleTarget m -> {
                diag.error(Code.NOT_CALLABLE, p.span(), "'" + m.module().path() + "' is a module");
                yield Exprs.error();
            }
            case Exprs.ErrorTarget x -> {
                for (var a : e.args()) c.exprs.expr(a.value(), null);
                yield Exprs.error();
            }
        };
    }

    private Typed methodCall(Expr.Call e, Expr.Member m) {
        String name = m.name().text();
        var r = c.exprs.recv(m.receiver());
        var t = r.value().type();
        if (t == Type.ERROR) {
            for (var a : e.args()) c.exprs.expr(a.value(), null);
            return Exprs.error();
        }
        if (name.equals("type") && e.args().isEmpty() && m.typeArgs().isEmpty()) {
            return new Typed(new Ir.TypeOfValue(r.value().ir()), c.exprs.typeType());
        }
        var cands = methodCandidates(t, name);
        if (cands.isEmpty() && TypeSystem.expand(t) instanceof Type.Union) {
            diag.error(Code.UNION_MEMBER_ACCESS, m.name().span(), "cannot call '" + name + "' on a value of union type '"
                    + t + "': no union-safe trait that every member implements has it (§3.4); narrow it first (§9)");
            for (var a : e.args()) c.exprs.expr(a.value(), null);
            return Exprs.error();
        }
        if (cands.isEmpty()) {
            // A field holding a function: `self.f(x)`.
            var field = c.exprs.fieldPlace(m);
            if (field != null && TypeSystem.expand(field.type()) instanceof Type.Fn) {
                return valueCall(e, new Typed(new Ir.Read(field.place()), field.type()));
            }
            diag.error(Code.NO_SUCH_MEMBER, m.name().span(), "'" + t + "' has no method '" + name + "'");
            for (var a : e.args()) c.exprs.expr(a.value(), null);
            return Exprs.error();
        }
        var instance = cands.stream().filter(k -> !k.fn().isStatic()).toList();
        if (instance.isEmpty()) {
            diag.error(Code.WRONG_KIND, m.name().span(), "'" + name + "' is a static function; call it as `Type::" + name + "(...)`");
            for (var a : e.args()) c.exprs.expr(a.value(), null);
            return Exprs.error();
        }
        if (instance.stream().allMatch(k -> k.fn().isGetter())) {
            diag.error(Code.WRONG_KIND, m.name().span(), "'" + name + "' is a getter; use it without parentheses");
            for (var a : e.args()) c.exprs.expr(a.value(), null);
            return Exprs.error();
        }
        var bound = resolve(instance, r.value().type(), e.args(), m.typeArgs(), e.span(), name);
        if (bound == null) return Exprs.error();
        checkMethodAccess(bound.cand().fn(), m.name().span());
        Ir.Place recvPlace = null;
        if (bound.cand().fn().receiver == FnInfo.Receiver.MUT_SELF) {
            var place = r.place();
            if (place != null && !place.isMutable()) {
                diag.error(Code.IMMUTABLE_RECEIVER, m.receiver().span(), "'" + name + "' takes `mut self`, but "
                        + lower(place.immutableWhy()));
            }
            if (place != null) recvPlace = place.place();
        }
        return build(bound, recvPlace == null ? r.value().ir() : null, recvPlace, e.span());
    }

    private static String lower(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private Typed superCall(Expr.Call e, Expr.Member m) {
        var ctx = c.ctx;
        var owner = ctx.fn == null ? null : ctx.fn.ownerType();
        if (owner == null || !owner.isClass() || owner.base == null || ctx.self == null) {
            diag.error(Code.BAD_SUPER, m.receiver().span(), "`super` is only valid in an instance method of a subclass");
            for (var a : e.args()) c.exprs.expr(a.value(), null);
            return Exprs.error();
        }
        var cands = new ArrayList<Candidate>();
        collectClassMethods(owner.base, m.name().text(), cands, true);
        if (cands.isEmpty()) {
            diag.error(Code.NO_SUCH_MEMBER, m.name().span(), "base class '" + owner.base + "' has no method '" + m.name().text() + "'");
            for (var a : e.args()) c.exprs.expr(a.value(), null);
            return Exprs.error();
        }
        var bound = resolve(cands, owner.base, e.args(), m.typeArgs(), e.span(), m.name().text());
        if (bound == null) return Exprs.error();
        if (bound.cand().fn().dispatch() == Dispatch.ABSTRACT) {
            diag.error(Code.BAD_SUPER, m.name().span(), "'" + bound.cand().fn().qualifiedName() + "' is abstract");
        }
        var self = new Ir.Read(new Ir.LocalPlace(ctx.self));
        var direct = new Candidate(bound.cand().fn(), Ir.CallKind.DIRECT, bound.cand().env(), Set.of(), null, false);
        return build(new Bound(direct, bound.args(), bound.order(), bound.typeArgs(), bound.ownerArgs(), bound.ret()),
                bound.cand().fn().receiver == FnInfo.Receiver.MUT_SELF ? null : self,
                bound.cand().fn().receiver == FnInfo.Receiver.MUT_SELF ? new Ir.LocalPlace(ctx.self) : null, e.span());
    }

    private Typed build(Bound b, Ir.Expr recv, Ir.Place recvPlace, Span span) {
        var f = b.cand().fn();
        if (f.isUnsafe() && !c.inUnsafe()) {
            diag.error(Code.UNSAFE_REQUIRED, span, "calling unsafe function '" + f.qualifiedName() + "' requires an unsafe context");
        }
        Ir.Expr receiver = recv;
        List<Ir.Expr> args = b.args();
        int[] order = b.order();
        if (b.cand().ufcs()) {
            receiver = args.getFirst();
            args = args.subList(1, args.size());
            var o = new ArrayList<Integer>();
            for (int i : order) if (i > 0) o.add(i - 1);
            order = o.stream().mapToInt(Integer::intValue).toArray();
        }
        var call = new Ir.Call(f, b.cand().kind(), receiver, recvPlace, args, order, b.typeArgs(), b.ownerArgs(),
                b.cand().staticSelf(), span);
        return new Typed(call, b.ret());
    }

    // ---- Lookup (§8.5) ----

    /** The methods named {@code name} callable on a value of type {@code t}: inherent first, then traits. */
    List<Candidate> methodCandidates(Type t, String name) {
        t = TypeSystem.expand(t);
        var out = new ArrayList<Candidate>();
        switch (t) {
            case Prim p -> {
                inherent(ts.prims.get(p), Map.of(), name, out);
                if (out.isEmpty()) traitMethods(ts.prims.get(p), t, name, out);
            }
            case Type.Lit l -> {
                return methodCandidates(l.base(), name);
            }
            case Type.Nominal n when n.decl().isTrait() -> {
                for (var tr : TypeSystem.selfAndSupers(n)) traitMembers(tr, t, name, out);
            }
            case Type.Nominal n when n.decl().isClass() -> {
                collectClassMethods(n, name, out, false);
                if (out.isEmpty()) {
                    for (Type.Nominal cur = n; cur != null; cur = baseOf(cur)) traitMethods(cur.decl(), t, name, out);
                }
            }
            case Type.Nominal n -> {
                inherent(n.decl(), TypeSystem.bind(n.decl().typeParams, n.args()), name, out);
                if (out.isEmpty()) traitMethods(n.decl(), t, name, out);
            }
            case Type.Param p -> {
                for (var b : p.param().bounds) for (var tr : TypeSystem.selfAndSupers(b)) traitMembers(tr, t, name, out);
            }
            case Type.Union u -> {
                // Members of the union-safe traits every member implements (§3.4).
                var traits = new LinkedHashSet<TypeInfo>(ts.implementedTraits());
                for (Type m : u.members()) {
                    if (TypeSystem.expand(m) instanceof Type.Nominal n && n.decl().isTrait()) {
                        for (var s : TypeSystem.selfAndSupers(n)) traits.add(s.decl());
                    } else if (m instanceof Type.Param p) {
                        for (var b : p.param().bounds) for (var s : TypeSystem.selfAndSupers(b)) traits.add(s.decl());
                    }
                }
                for (TypeInfo tr : traits) {
                    if (tr.methods(name).isEmpty() || !ts.unionSafe(tr)) continue;
                    var inst = ts.implementsAs(u, tr);
                    if (inst != null) traitMembers(inst, t, name, out);
                }
            }
            case Type.Array a -> {
                if (ARRAY_METHODS.contains(name)) {
                    inherent(ts.list, Map.of(ts.list.typeParams.getFirst(), a.element()), name, out);
                }
            }
            default -> {}
        }
        // The same function reached twice (e.g. a trait through two paths) is one candidate.
        var seen = new HashSet<FnInfo>();
        out.removeIf(k -> !seen.add(k.fn()));
        return out;
    }

    /** Fixed-size arrays share the list intrinsics that do not change the length. */
    private static final Set<String> ARRAY_METHODS = Set.of("len", "truthy", "iter", "index", "set", "contains",
            "first", "last", "enumerate", "with_ptr");

    private static Type.Nominal baseOf(Type.Nominal n) {
        var b = n.decl().base;
        return b == null ? null : TypeSystem.substNominal(b, TypeSystem.bind(n.decl().typeParams, n.args()));
    }

    private void inherent(TypeInfo decl, Map<TypeParamInfo, Type> env, String name, List<Candidate> out) {
        for (FnInfo f : decl.methods(name)) out.add(new Candidate(f, Ir.CallKind.DIRECT, env, Set.of(), null, false));
    }

    /** Instance methods of a class and its bases; overridden versions are dropped. */
    private void collectClassMethods(Type.Nominal n, String name, List<Candidate> out, boolean direct) {
        var roots = new HashSet<FnInfo>();
        for (Type.Nominal cur = n; cur != null; cur = baseOf(cur)) {
            var env = TypeSystem.bind(cur.decl().typeParams, cur.args());
            for (FnInfo f : cur.decl().methods(name)) {
                if (f.isStatic()) continue;
                if (!roots.add(f.root())) continue;
                var kind = f.isVirtual() && !direct ? Ir.CallKind.VIRTUAL : Ir.CallKind.DIRECT;
                out.add(new Candidate(f, kind, env, Set.of(), null, false));
            }
        }
    }

    /** Methods of the traits {@code decl} implements (through impls). */
    private void traitMethods(TypeInfo decl, Type self, String name, List<Candidate> out) {
        for (ImplInfo impl : ts.implsOf(decl)) {
            var inst = ts.implementsAs(self, impl.trait.decl());
            if (inst == null) continue;
            traitMembers(inst, self, name, out);
        }
    }

    private void traitMembers(Type.Nominal trait, Type self, String name, List<Candidate> out) {
        var env = TypeSystem.bind(trait.decl().typeParams, trait.args());
        env.put(trait.decl().selfParam, self);
        for (FnInfo f : trait.decl().methods(name)) {
            if (f.isStatic()) continue;
            out.add(new Candidate(f, Ir.CallKind.TRAIT, env, Set.of(), null, false));
        }
    }

    /** Method visibility (§2.3); trait members and their implementations are always public. */
    void checkMethodAccess(FnInfo f, Span span) {
        if (f.visibility() == Visibility.PUB) return;
        if (!(f.owner instanceof TypeInfo owner) || owner.isTrait() || owner.kind() == TypeInfo.Kind.PRIMITIVE) return;
        for (ImplInfo impl : ts.implsOf(owner)) {
            for (var fns : impl.methods.values()) if (fns.contains(f)) return;
        }
        c.exprs.checkAccess(owner, f.visibility(), "method '" + f.name() + "'", span);
    }

    // ---- Functions as values (§7.8) ----

    Typed fnValue(Exprs.FnTarget target, Type expected, Span span) {
        var cands = target.candidates();
        var fnExpected = expected == null ? null : TypeSystem.expand(expected) instanceof Type.Fn f ? f : null;
        Candidate chosen = null;
        if (cands.size() == 1) {
            chosen = cands.getFirst();
        } else if (fnExpected != null) {
            for (var k : cands) {
                if (k.fn().params.size() == fnExpected.params().size()
                        && paramTypes(k.fn(), k.env()).equals(fnExpected.params())) chosen = k;
            }
        }
        if (chosen == null) {
            diag.error(Code.AMBIGUOUS_CALL, span, "'" + target.name() + "' is overloaded; the expected type does not pick one");
            return Exprs.error();
        }
        var f = chosen.fn();
        if (!f.isStatic() || chosen.kind() != Ir.CallKind.DIRECT || f.isGetter()) {
            diag.error(Code.WRONG_KIND, span, "only functions can be used as values; wrap methods in a lambda");
            return Exprs.error();
        }
        var explicit = target.typeArgs().stream().map(c::typeArg).toList();
        var env = new HashMap<>(chosen.env());
        if (!f.typeParams.isEmpty()) {
            if (explicit.size() != f.typeParams.size()) {
                diag.error(Code.CANNOT_INFER, span, "generic function '" + target.name() + "' used as a value needs explicit type arguments");
                return Exprs.error();
            }
            for (int i = 0; i < explicit.size(); i++) env.put(f.typeParams.get(i), explicit.get(i));
        }
        if (!chosen.vars().isEmpty()) {
            diag.error(Code.CANNOT_INFER, span, "name the type arguments of '" + f.qualifiedName() + "' to use it as a value");
            return Exprs.error();
        }
        // An extern is an `extern fn` value; a Threebolt function converts to one where expected (§14.7).
        boolean toExtern = f.isExtern || (fnExpected != null && fnExpected.isExtern());
        if (toExtern && !f.isExtern && !canBeCallback(f, span)) return Exprs.error();
        var type = new Type.Fn(paramTypes(f, env), TypeSystem.subst(f.ret, env), toExtern);
        if (f.params.stream().anyMatch(FnInfo.ParamInfo::isOptional)) {
            diag.error(Code.WRONG_KIND, span, "a function with optional parameters cannot be used as a value");
        }
        return new Typed(new Ir.FnRef(f, f.typeParams.stream().map(p -> env.get(p)).toList(), type), type);
    }

    /** §14.7: a top-level, non-generic function with an ABI signature converts to `extern fn`. */
    private boolean canBeCallback(FnInfo f, Span span) {
        String why = null;
        if (f.owner != null) why = "a method or static function";
        else if (!f.typeParams.isEmpty()) why = "a generic function";
        else if (f.params.stream().anyMatch(p -> !TypeSystem.isAbi(p.type(), false))
                || (f.ret != Type.UNIT && !TypeSystem.isAbi(f.ret, false))) why = "a function whose signature has non-ABI types";
        if (why == null) return true;
        diag.error(Code.BAD_CALLBACK, span, "'" + f.qualifiedName() + "' is " + why
                + "; only top-level, non-generic functions with ABI signatures convert to `extern fn`");
        return false;
    }

    private static List<Type> paramTypes(FnInfo f, Map<TypeParamInfo, Type> env) {
        return f.params.stream().map(p -> TypeSystem.subst(p.type(), env)).toList();
    }

    // ---- Overload resolution (§4.4) ----

    /**
     * Picks the candidate for these arguments and binds them; null after an error. For UFCS
     * candidates, the first positional argument is the receiver.
     */
    Bound resolve(List<Candidate> cands, Type recvType, List<Expr.Arg> args, List<TypeExpr> typeArgExprs,
                  Span span, String name) {
        var positional = new ArrayList<Expr.Arg>();
        var named = new ArrayList<Expr.Arg>();
        for (var a : args) {
            if (a.name() == null) {
                if (!named.isEmpty()) {
                    diag.error(Code.BAD_ARGUMENTS, a.span(), "positional arguments must come before named arguments");
                }
                positional.add(a);
            } else {
                named.add(a);
            }
        }
        var explicit = typeArgExprs.stream().map(c::typeArg).toList();
        var viable = new ArrayList<Candidate>();
        for (var k : cands) if (applicable(k, positional, named, explicit)) viable.add(k);
        if (viable.isEmpty()) {
            if (cands.size() == 1) explainInapplicable(cands.getFirst(), positional, named, explicit, span, name);
            else diag.error(Code.NO_MATCHING_OVERLOAD, span, "no overload of '" + name + "' takes these arguments");
            for (var a : args) c.exprs.expr(a.value(), null);
            return null;
        }
        Candidate chosen = viable.getFirst();
        if (viable.size() > 1) {
            chosen = pick(viable, positional, named, explicit, span, name);
            if (chosen == null) {
                for (var a : args) c.exprs.expr(a.value(), null);
                return null;
            }
        }
        return bind(chosen, positional, named, explicit, span, name);
    }

    private boolean applicable(Candidate k, List<Expr.Arg> positional, List<Expr.Arg> named, List<Type> explicit) {
        var f = k.fn();
        int required = f.requiredCount() + (k.ufcs() ? 1 : 0);
        if (f.isVariadic ? positional.size() < required : positional.size() != required) return false;
        if (!explicit.isEmpty() && explicit.size() != f.typeParams.size()) return false;
        var seen = new HashSet<String>();
        for (var a : named) {
            var p = f.params.stream().filter(x -> x.name().equals(a.name().text())).findFirst();
            if (p.isEmpty() || !p.get().isOptional() || !seen.add(a.name().text())) return false;
        }
        return true;
    }

    private void explainInapplicable(Candidate k, List<Expr.Arg> positional, List<Expr.Arg> named, List<Type> explicit,
                                     Span span, String name) {
        var f = k.fn();
        int required = f.requiredCount() + (k.ufcs() ? 1 : 0);
        for (var a : named) {
            var p = f.params.stream().filter(x -> x.name().equals(a.name().text())).findFirst();
            if (p.isPresent() && !p.get().isOptional()) {
                diag.error(Code.BAD_ARGUMENTS, a.name().span(), "required parameter '" + a.name().text()
                        + "' is positional-only: pass it without its name");
                return;
            }
        }
        if (f.isVariadic && positional.size() < required) {
            diag.error(Code.BAD_ARGUMENTS, span, "'" + name + "' takes at least " + required + " argument"
                    + (required == 1 ? "" : "s") + ", found " + positional.size());
            return;
        }
        if (positional.size() != required && !f.isVariadic) {
            diag.error(Code.BAD_ARGUMENTS, span, "'" + name + "' takes " + required + " positional argument"
                    + (required == 1 ? "" : "s") + ", found " + positional.size()
                    + (f.params.stream().anyMatch(FnInfo.ParamInfo::isOptional) ? " (optional parameters are named-only)" : ""));
            return;
        }
        if (!explicit.isEmpty() && explicit.size() != f.typeParams.size()) {
            diag.error(Code.WRONG_TYPE_ARGUMENTS, span, "'" + name + "' takes " + f.typeParams.size()
                    + " type argument(s), found " + explicit.size());
            return;
        }
        var seen = new HashSet<String>();
        for (var a : named) {
            var p = f.params.stream().filter(x -> x.name().equals(a.name().text())).findFirst();
            if (p.isEmpty()) {
                diag.error(Code.BAD_ARGUMENTS, a.name().span(), "'" + name + "' has no parameter named '" + a.name().text() + "'");
            } else if (!p.get().isOptional()) {
                diag.error(Code.BAD_ARGUMENTS, a.name().span(), "required parameter '" + a.name().text()
                        + "' is positional-only");
            } else if (!seen.add(a.name().text())) {
                diag.error(Code.BAD_ARGUMENTS, a.name().span(), "argument '" + a.name().text() + "' is given twice");
            }
        }
    }

    /** Ranks viable overloads by positional argument types (exact beats conversion), then defaults filled. */
    private Candidate pick(List<Candidate> viable, List<Expr.Arg> positional, List<Expr.Arg> named,
                           List<Type> explicit, Span span, String name) {
        var argTypes = new ArrayList<Type>();
        for (var a : positional) {
            if (a.value() instanceof Expr.Lambda) argTypes.add(null);
            else argTypes.add(diag.muted(() -> c.exprs.expr(a.value(), null)).type());
        }
        var scored = new ArrayList<int[]>();
        var survivors = new ArrayList<Candidate>();
        for (var k : viable) {
            var s = score(k, argTypes, positional, explicit);
            if (s != null) {
                scored.add(s);
                survivors.add(k);
            }
        }
        if (survivors.isEmpty()) {
            diag.error(Code.NO_MATCHING_OVERLOAD, span, "no overload of '" + name + "' accepts arguments of types ("
                    + String.join(", ", argTypes.stream().map(t -> t == null ? "lambda" : t.toString()).toList()) + ")");
            return null;
        }
        var best = new ArrayList<Integer>();
        for (int i = 0; i < survivors.size(); i++) {
            boolean dominated = false;
            for (int j = 0; j < survivors.size(); j++) {
                if (i != j && better(scored.get(j), scored.get(i))) dominated = true;
            }
            if (!dominated) best.add(i);
        }
        if (best.size() > 1) {
            // Prefer the candidate that fills fewer defaulted parameters.
            int min = Integer.MAX_VALUE;
            for (int i : best) min = Math.min(min, defaultsFilled(survivors.get(i), named));
            final int m = min;
            best.removeIf(i -> defaultsFilled(survivors.get(i), named) != m);
        }
        if (best.size() > 1) {
            diag.error(Code.AMBIGUOUS_CALL, span, "call to '" + name + "' is ambiguous between "
                    + String.join(" and ", best.stream().map(i -> "'" + signature(survivors.get(i).fn()) + "'").toList()));
            return null;
        }
        return survivors.get(best.getFirst());
    }

    private static int defaultsFilled(Candidate k, List<Expr.Arg> named) {
        int optional = (int) k.fn().params.stream().filter(FnInfo.ParamInfo::isOptional).count();
        return optional - named.size();
    }

    private static boolean better(int[] a, int[] b) {
        boolean strictly = false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] > b[i]) return false;
            if (a[i] < b[i]) strictly = true;
        }
        return strictly;
    }

    static String signature(FnInfo f) {
        var params = new ArrayList<String>();
        for (var p : f.params) params.add(p.type().toString());
        return f.qualifiedName() + "(" + String.join(", ", params) + ")";
    }

    /** Per-argument cost: 0 exact, 1 conversion; null if some argument does not fit. */
    private int[] score(Candidate k, List<Type> argTypes, List<Expr.Arg> positional, List<Type> explicit) {
        var f = k.fn();
        var env = new HashMap<>(k.env());
        var vars = new HashSet<>(k.vars());
        if (explicit.isEmpty()) vars.addAll(f.typeParams);
        else for (int i = 0; i < explicit.size(); i++) env.put(f.typeParams.get(i), explicit.get(i));
        var params = positionalParams(k);
        for (int i = 0; i < params.size(); i++) {
            if (argTypes.get(i) != null) ts.unify(TypeSystem.subst(params.get(i), k.env()), argTypes.get(i), env, vars);
        }
        var out = new int[params.size()];
        for (int i = 0; i < params.size(); i++) {
            var pt = TypeSystem.subst(params.get(i), env);
            var at = argTypes.get(i);
            if (at == null) {
                if (!(TypeSystem.expand(pt) instanceof Type.Fn fn)
                        || fn.params().size() != ((Expr.Lambda) positional.get(i).value()).params().size()) return null;
                continue;
            }
            if (at == Type.ERROR || TypeSystem.widen(at).equals(pt) || at.equals(pt)) continue;
            if (ts.assignable(at, pt) || literalFits(positional.get(i).value(), pt)) out[i] = 1;
            else return null;
        }
        return out;
    }

    /** The types of the parameters positional arguments go to (the receiver first for UFCS). */
    private List<Type> positionalParams(Candidate k) {
        var out = new ArrayList<Type>();
        if (k.ufcs()) out.add(k.fn().owner instanceof TypeInfo t ? t.selfParam.type() : Type.ERROR);
        for (var p : k.fn().params) if (!p.isOptional()) out.add(p.type());
        return out;
    }

    private boolean literalFits(Expr e, Type pt) {
        if (!Exprs.isNumericLiteral(e)) return false;
        var t = diag.muted(() -> c.exprs.expr(e, pt));
        return ts.assignable(t.type(), pt);
    }

    /** Checks the arguments against {@code k}, inferring type arguments (§10). */
    private Bound bind(Candidate k, List<Expr.Arg> positional, List<Expr.Arg> named, List<Type> explicit,
                       Span span, String name) {
        var f = k.fn();
        var env = new HashMap<>(k.env());
        var vars = new LinkedHashSet<>(k.vars());
        if (explicit.isEmpty()) vars.addAll(f.typeParams);
        else for (int i = 0; i < explicit.size(); i++) env.put(f.typeParams.get(i), explicit.get(i));

        // Map each argument to its parameter (index 0 is the UFCS receiver); C variadic arguments
        // come after the parameters and have no parameter type.
        int fixed = f.params.size() + (k.ufcs() ? 1 : 0);
        int n = fixed + (f.isVariadic ? Math.max(0, positional.size() - f.requiredCount()) : 0);
        var argOf = new Expr.Arg[n];
        var paramType = new Type[n];
        var order = new ArrayList<Integer>();
        int next = 0;
        var required = new ArrayList<Integer>();
        if (k.ufcs()) required.add(0);
        for (int i = 0; i < f.params.size(); i++) if (!f.params.get(i).isOptional()) required.add(i + (k.ufcs() ? 1 : 0));
        for (var a : positional) {
            int idx = next < required.size() ? required.get(next) : fixed + next - required.size();
            next++;
            argOf[idx] = a;
            order.add(idx);
        }
        for (var a : named) {
            for (int i = 0; i < f.params.size(); i++) {
                if (f.params.get(i).name().equals(a.name().text())) {
                    int idx = i + (k.ufcs() ? 1 : 0);
                    argOf[idx] = a;
                    order.add(idx);
                }
            }
        }
        for (int i = 0; i < fixed; i++) {
            Type raw;
            if (k.ufcs() && i == 0) raw = ((TypeInfo) f.owner).selfParam.type();
            else raw = f.params.get(i - (k.ufcs() ? 1 : 0)).type();
            paramType[i] = TypeSystem.subst(raw, k.env());
        }
        if (k.ufcs()) {
            // The receiver's type decides the trait's type arguments and Self.
            var traitDecl = (TypeInfo) f.owner;
            vars.add(traitDecl.selfParam);
            vars.addAll(traitDecl.typeParams);
        }

        var typed = new Typed[n];
        var deferred = new ArrayList<Integer>();
        for (int idx : order) {
            var a = argOf[idx];
            if (idx >= fixed) {
                typed[idx] = c.exprs.expr(a.value(), null);
                variadicArg(typed[idx].type(), a.span());
                continue;
            }
            if (a.value() instanceof Expr.Lambda l && l.params().stream().anyMatch(p -> p.type() == null)
                    && TypeSystem.mentions(paramType[idx], vars)) {
                deferred.add(idx);
                continue;
            }
            var pt = TypeSystem.subst(paramType[idx], env);
            var hint = TypeSystem.mentions(pt, vars) ? null : pt;
            typed[idx] = c.exprs.expr(a.value(), hint);
            if (k.ufcs() && idx == 0) {
                var traitDecl = (TypeInfo) f.owner;
                var inst = ts.implementsAs(typed[idx].type(), traitDecl);
                if (inst != null) {
                    env.put(traitDecl.selfParam, TypeSystem.widen(typed[idx].type()));
                    for (int i = 0; i < traitDecl.typeParams.size(); i++) env.put(traitDecl.typeParams.get(i), inst.args().get(i));
                } else if (typed[idx].type() != Type.ERROR) {
                    diag.error(Code.TYPE_MISMATCH, a.span(), "'" + typed[idx].type() + "' does not implement '" + traitDecl.name() + "'");
                    env.put(traitDecl.selfParam, Type.ERROR);
                }
                continue;
            }
            ts.unify(paramType[idx], typed[idx].type(), env, vars);
        }
        for (int idx : deferred) {
            var pt = TypeSystem.subst(paramType[idx], env);
            if (TypeSystem.expand(pt) instanceof Type.Fn fn && fn.params().stream().anyMatch(p -> TypeSystem.mentions(p, vars))) {
                diag.error(Code.CANNOT_INFER, argOf[idx].span(), "cannot infer the parameter types of this lambda; annotate them");
            }
            typed[idx] = c.exprs.lambda((Expr.Lambda) argOf[idx].value(), pt);
            ts.unify(paramType[idx], typed[idx].type(), env, vars);
        }
        // An argument that failed to type has been reported; inferring from it is no news.
        boolean argError = java.util.Arrays.stream(typed).anyMatch(t -> t != null && t.type() == Type.ERROR);
        for (var v : vars) {
            if (!env.containsKey(v)) {
                if (!argError) {
                    diag.error(Code.CANNOT_INFER, span, "cannot infer type argument '" + v.name() + "' of '" + name
                            + "'; give it explicitly: `" + name + "<...>(...)`");
                }
                env.put(v, Type.ERROR);
            }
        }
        var irArgs = new ArrayList<Ir.Expr>();
        for (int i = 0; i < n; i++) {
            if (argOf[i] == null) {
                irArgs.add(null);
                continue;
            }
            if (i >= fixed) {
                irArgs.add(typed[i].ir());
                continue;
            }
            var target = TypeSystem.subst(paramType[i], env);
            irArgs.add(k.ufcs() && i == 0 ? typed[i].ir() : c.coerce(typed[i], target, argOf[i].value().span()));
        }
        // Bounds of inferred or explicit type arguments.
        var checkParams = new ArrayList<TypeParamInfo>(f.typeParams);
        checkParams.addAll(k.vars());
        c.types.checkBounds(checkParams, checkParams.stream().map(p -> env.getOrDefault(p, Type.ERROR)).toList(), span);
        var typeArgs = f.typeParams.stream().map(p -> env.getOrDefault(p, Type.ERROR)).toList();
        List<Type> ownerArgs = null;
        if (f.isStatic() && f.owner instanceof TypeInfo owner && !owner.typeParams.isEmpty()) {
            ownerArgs = owner.typeParams.stream().map(p -> env.getOrDefault(p, p.type())).toList();
        }
        var ret = TypeSystem.subst(TypeSystem.subst(f.ret, k.env()), env);
        stdCallRules(f, k.env(), ownerArgs, span);
        return new Bound(k, irArgs, order.stream().mapToInt(Integer::intValue).toArray(), typeArgs, ownerArgs, ret);
    }

    /** §14.1: C variadic arguments are scalars of ABI type (promoted at the call). */
    private void variadicArg(Type t, Span span) {
        var w = TypeSystem.widen(t);
        boolean cStruct = TypeSystem.expand(w) instanceof Type.Nominal n && n.decl().isC;
        if (w == Type.ERROR || (TypeSystem.isAbi(w, false) && !cStruct)) return;
        diag.error(Code.NOT_ABI_TYPE, span, "'" + w + "' cannot be passed as a C variadic argument; pass an integer, "
                + "float, bool, pointer or enum" + (w == Prim.STR ? " (`to_cstr()` gives a `Ptr<c::char>`)" : ""));
    }

    /** Type rules of std intrinsics that their signatures cannot express. */
    private void stdCallRules(FnInfo f, Map<TypeParamInfo, Type> ownerEnv, List<Type> ownerArgs, Span span) {
        if (f.intrinsic == null) return;
        switch (f.intrinsic) {
            case "List.with_ptr" -> {
                // §14.5: the elements are passed to C, so they must have ABI types.
                var element = ownerEnv.get(((TypeInfo) f.owner).typeParams.getFirst());
                if (element != null && !TypeSystem.isGeneric(element) && !TypeSystem.isAbi(element, false)) {
                    diag.error(Code.NOT_ABI_TYPE, span, "'with_ptr' needs elements of a C ABI type, not '" + element + "'");
                }
            }
            case "Atomic.new" -> {
                var t = ownerArgs == null ? null : TypeSystem.expand(ownerArgs.getFirst());
                var p = TypeSystem.prim(t == null ? Type.ERROR : t);
                if (t != null && t != Type.ERROR && !TypeSystem.isGeneric(t) && (p == null || !(p.isInteger() || p == Prim.BOOL))) {
                    diag.error(Code.UNSATISFIED_BOUND, span, "`Atomic<T>` holds an integer or `bool`, not '" + t + "'");
                }
            }
            case "Atomic.fetch_add", "Atomic.fetch_sub" -> {
                var t = ownerEnv.get(((TypeInfo) f.owner).typeParams.getFirst());
                if (t != null && TypeSystem.prim(t) == Prim.BOOL) {
                    diag.error(Code.BAD_OPERAND, span, "'" + f.name() + "' needs an integer `Atomic`, not `Atomic<bool>`");
                }
            }
            default -> {}
        }
    }
}
