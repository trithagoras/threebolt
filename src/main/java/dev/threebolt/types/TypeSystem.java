package dev.threebolt.types;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operations on types shared by the checker and the interpreter: union normalization (§3.4),
 * assignability (§3.5), trait implementation lookup (§4.10, §8.5), substitution and inference (§10).
 */
public final class TypeSystem {
    /** Well-known std declarations, set by the {@link Declarer}. */
    public TypeInfo list, map, set, range, done, typeDecl, ptr, nativeIter;
    public TypeInfo display, eq, ord, hash, truthy, len, error, defaultTrait, iterator, iterable, contains, index, indexMut;
    public TypeInfo num, signed, integer, floatTrait;
    public final Map<String, TypeInfo> operatorTraits = new HashMap<>();
    public final Map<Prim, TypeInfo> prims = new LinkedHashMap<>();

    /** Implementations by trait, then by implementing type constructor (§4.10). */
    private final Map<TypeInfo, Map<TypeInfo, ImplInfo>> impls = new HashMap<>();
    /** The `impl` table for each type constructor, in declaration order. */
    private final Map<TypeInfo, List<ImplInfo>> implsOf = new HashMap<>();

    /** Registers an implementation; returns the existing one for the same pair, if any. */
    public ImplInfo addImpl(ImplInfo impl) {
        var byType = impls.computeIfAbsent(impl.trait.decl(), k -> new HashMap<>());
        var existing = byType.get(impl.targetDecl);
        if (existing != null) return existing;
        byType.put(impl.targetDecl, impl);
        implsOf.computeIfAbsent(impl.targetDecl, k -> new ArrayList<>()).add(impl);
        return null;
    }

    public ImplInfo impl(TypeInfo trait, TypeInfo target) {
        var byType = impls.get(trait);
        return byType == null ? null : byType.get(target);
    }

    /** The impls of {@code trait} whose target is a trait reference (§4.10). */
    public List<ImplInfo> traitRefImpls(TypeInfo trait) {
        var byType = impls.get(trait);
        if (byType == null) return List.of();
        return byType.values().stream().filter(i -> i.targetDecl.isTrait()).toList();
    }

    /** Every implementation of {@code trait}, built-in ones included. */
    public java.util.Collection<ImplInfo> implsOfTrait(TypeInfo trait) {
        var byType = impls.get(trait);
        return byType == null ? List.of() : byType.values();
    }

    /** Every trait with at least one impl. */
    public Set<TypeInfo> implementedTraits() {
        return impls.keySet();
    }

    public List<ImplInfo> implsOf(TypeInfo target) {
        return implsOf.getOrDefault(target, List.of());
    }

    // ---- Construction ----

    public Type.Nominal listOf(Type element) {
        return new Type.Nominal(list, List.of(element));
    }

    public Type.Nominal nominal(TypeInfo decl, Type... args) {
        return new Type.Nominal(decl, List.of(args));
    }

    /** The type {@code Self} denotes inside a declaration (§3.2). */
    public Type selfType(TypeInfo decl) {
        if (decl.kind() == TypeInfo.Kind.PRIMITIVE) return decl.prim;
        if (decl.isTrait()) return decl.selfParam.type();
        return new Type.Nominal(decl, decl.typeParams.stream().map(TypeParamInfo::type).toList());
    }

    public static Type union(Type a, Type b) {
        return union(List.of(a, b));
    }

    /** Flattened, deduplicated, literal-subsumed (§3.4); {@code !} members vanish. */
    public static Type union(Collection<Type> types) {
        var flat = new LinkedHashSet<Type>();
        for (Type t : types) flatten(t, flat);
        if (flat.contains(Type.ERROR)) return Type.ERROR;
        if (flat.contains(Type.UNBOUND)) return Type.UNBOUND;
        flat.remove(Type.NEVER);
        flat.removeIf(t -> t instanceof Type.Lit l && flat.contains(l.base()));
        if (flat.isEmpty()) return Type.NEVER;
        if (flat.size() == 1) return flat.iterator().next();
        return new Type.Union(List.copyOf(flat));
    }

    private static void flatten(Type t, Set<Type> out) {
        t = expand(t);
        if (t instanceof Type.Union u) u.members().forEach(m -> flatten(m, out));
        else out.add(t);
    }

    public static List<Type> members(Type t) {
        t = expand(t);
        return t instanceof Type.Union u ? u.members() : List.of(t);
    }

    /** Expands a recursive alias reference (one level). */
    public static Type expand(Type t) {
        while (t instanceof Type.AliasRef r) {
            var alias = r.alias();
            if (alias.aliasType == null) return Type.ERROR;
            t = subst(alias.aliasType, bind(alias.typeParams, r.args()));
        }
        return t;
    }

    /**
     * A union of literals of one base type (`"left" | "right"`) as that base type, for member
     * access and trait implementations; any other type unchanged.
     */
    public static Type literalBase(Type t) {
        if (!(expand(t) instanceof Type.Union u)) return t;
        Prim base = null;
        for (Type m : u.members()) {
            if (!(m instanceof Type.Lit l) || (base != null && l.base() != base)) return t;
            base = l.base();
        }
        return base;
    }

    public static boolean containsNull(Type t) {
        return members(t).contains(Type.NULL);
    }

    /** The members of {@code t} not assignable to {@code removed}. */
    public Type minus(Type t, Type removed) {
        var kept = new ArrayList<Type>();
        for (Type m : members(t)) if (!assignable(m, removed)) kept.add(m);
        return union(kept);
    }

    /** Literal types widened to their base type, also inside unions and tuples (§3.6). */
    public static Type widen(Type t) {
        return switch (t) {
            case Type.Lit l -> l.base();
            case Type.Union u -> union(u.members().stream().map(TypeSystem::widen).toList());
            case Type.Tuple tu -> new Type.Tuple(tu.elements().stream().map(TypeSystem::widen).toList());
            default -> t;
        };
    }

    /**
     * The type inference gives a binding whose value has type {@code t} (§3.6): a lone literal
     * type widens to its base, but a union with literal members is kept, as it comes from a
     * declared type (`Ordering`, `Direction | null`).
     */
    public static Type widenInferred(Type t) {
        return switch (t) {
            case Type.Lit l -> l.base();
            case Type.Union u when u.members().stream().anyMatch(m -> m instanceof Type.Lit) -> u;
            case Type.Union u -> union(u.members().stream().map(TypeSystem::widenInferred).toList());
            case Type.Tuple tu -> new Type.Tuple(tu.elements().stream().map(TypeSystem::widenInferred).toList());
            default -> t;
        };
    }

    /**
     * A type's name as `nameof` and `Type.name` report it (§15): declared names with type
     * arguments, `List<i32>` rather than `[i32]`.
     */
    public static String name(Type t) {
        return switch (expand(t)) {
            case Type.Nominal n -> n.args().isEmpty() ? n.decl().name()
                    : n.decl().name() + "<" + String.join(", ", n.args().stream().map(TypeSystem::name).toList()) + ">";
            case Type.Tuple tu -> tu.elements().size() == 1 ? "(" + name(tu.elements().getFirst()) + ",)"
                    : "(" + String.join(", ", tu.elements().stream().map(TypeSystem::name).toList()) + ")";
            case Type.Array a -> "[" + name(a.element()) + "; " + a.size() + "]";
            case Type.Union u -> String.join(" | ", u.members().stream().map(TypeSystem::name).toList());
            case Type.Fn f -> (f.isExtern() ? "extern " : "") + "fn(" + String.join(", ", f.params().stream()
                    .map(TypeSystem::name).toList()) + ")" + (f.ret() == Type.UNIT ? "" : " -> " + name(f.ret()));
            case Type x -> x.toString();
        };
    }

    // ---- Substitution ----

    public static Map<TypeParamInfo, Type> bind(List<TypeParamInfo> params, List<Type> args) {
        var m = new HashMap<TypeParamInfo, Type>();
        for (int i = 0; i < params.size() && i < args.size(); i++) m.put(params.get(i), args.get(i));
        return m;
    }

    public static Type subst(Type t, Map<TypeParamInfo, Type> env) {
        if (env.isEmpty()) return t;
        return switch (t) {
            case Type.Param p -> env.getOrDefault(p.param(), t);
            case Type.Nominal n -> n.args().isEmpty() ? n : new Type.Nominal(n.decl(), substAll(n.args(), env));
            case Type.Tuple tu -> new Type.Tuple(substAll(tu.elements(), env));
            case Type.Array a -> new Type.Array(subst(a.element(), env), a.size());
            case Type.Fn f -> new Type.Fn(substAll(f.params(), env), subst(f.ret(), env), f.isExtern());
            case Type.Union u -> union(substAll(u.members(), env));
            case Type.AliasRef r -> new Type.AliasRef(r.alias(), substAll(r.args(), env));
            default -> t;
        };
    }

    public static List<Type> substAll(List<Type> ts, Map<TypeParamInfo, Type> env) {
        return ts.stream().map(t -> subst(t, env)).toList();
    }

    public static Type.Nominal substNominal(Type.Nominal t, Map<TypeParamInfo, Type> env) {
        return (Type.Nominal) subst(t, env);
    }

    /** Whether {@code t} mentions a type parameter. */
    public static boolean isGeneric(Type t) {
        return switch (t) {
            case Type.Param p -> true;
            case Type.Nominal n -> n.args().stream().anyMatch(TypeSystem::isGeneric);
            case Type.Tuple tu -> tu.elements().stream().anyMatch(TypeSystem::isGeneric);
            case Type.Array a -> isGeneric(a.element());
            case Type.Fn f -> isGeneric(f.ret()) || f.params().stream().anyMatch(TypeSystem::isGeneric);
            case Type.Union u -> u.members().stream().anyMatch(TypeSystem::isGeneric);
            case Type.AliasRef r -> r.args().stream().anyMatch(TypeSystem::isGeneric);
            default -> false;
        };
    }

    // ---- Classification ----

    public static boolean isTraitRef(Type t) {
        return expand(t) instanceof Type.Nominal n && n.decl().isTrait();
    }

    public static boolean isClass(Type t) {
        return expand(t) instanceof Type.Nominal n && n.decl().isClass();
    }

    public static boolean isStruct(Type t) {
        return expand(t) instanceof Type.Nominal n && n.decl().isStruct();
    }

    /**
     * A C ABI type (§14.2): integers, floats, `bool`, `Ptr<T>`, `extern fn` types, `@c struct`s
     * and enums; `[T; N]` of ABI type only as an `@c struct` field ({@code inCStruct}).
     */
    public static boolean isAbi(Type t, boolean inCStruct) {
        t = expand(t);
        return switch (t) {
            case Prim p -> p != Prim.STR && p != Prim.CHAR;
            case Type.Nominal n -> n.decl().isEnum() || n.decl().isStd("Ptr") || (n.decl().isStruct() && n.decl().isC);
            case Type.Fn f -> f.isExtern();
            case Type.Array a -> inCStruct && isAbi(a.element(), true);
            default -> t == Type.ERROR;
        };
    }

    public static Prim prim(Type t) {
        t = expand(t);
        if (t instanceof Prim p) return p;
        if (t instanceof Type.Lit l) return l.base();
        return null;
    }

    public static boolean isNumeric(Type t) {
        var p = prim(t);
        return p != null && p.isNumeric();
    }

    public static boolean isInteger(Type t) {
        var p = prim(t);
        return p != null && p.isInteger();
    }

    public static boolean isFlags(Type t) {
        return expand(t) instanceof Type.Nominal n && n.decl().isEnum() && n.decl().isFlags;
    }

    /** Reference semantics (§3.3) — the types `mut` receivers may be reached through. */
    public static boolean isReference(Type t) {
        t = expand(t);
        if (t == Prim.STR) return true;
        if (t instanceof Type.Fn f) return !f.isExtern();
        return t instanceof Type.Nominal n && (n.decl().isClass() || n.decl().isTrait());
    }

    // ---- Assignability (§3.5) ----

    public boolean assignable(Type s, Type t) {
        s = expand(s);
        t = expand(t);
        if (s == Type.ERROR || t == Type.ERROR || s == Type.NEVER) return true;
        if (same(s, t)) return true;
        if (s instanceof Type.Union u) {
            for (Type m : u.members()) if (!assignable(m, t)) return false;
            return true;
        }
        if (t instanceof Type.Union u) {
            for (Type m : u.members()) if (assignable(s, m)) return true;
            return false;
        }
        if (s instanceof Type.Lit l) return !(t instanceof Type.Lit) && assignable(l.base(), t);
        if (s instanceof Prim a && t instanceof Prim b) return a.widensTo(b);
        if (t instanceof Type.Nominal tn) {
            if (tn.decl().isClass() && s instanceof Type.Nominal sn && sn.decl().isClass()) {
                var base = baseInstance(sn, tn.decl());
                return base != null && base.equals(tn);
            }
            if (tn.decl().isTrait()) {
                var inst = implementsAs(s, tn.decl());
                return inst != null && inst.equals(tn);
            }
        }
        if (s instanceof Type.Fn sf && t instanceof Type.Fn tf && tf.isExtern() && !sf.isExtern()) return false;
        return false;
    }

    /**
     * Type equality up to recursive aliases: {@code Map<str, Json>} written out equals the alias's
     * own member {@code Map<str, Json>}, where one {@code Json} is expanded and the other is not.
     */
    public static boolean same(Type a, Type b) {
        return same(a, b, new HashSet<>());
    }

    private static boolean same(Type a, Type b, Set<List<Type>> assumed) {
        if (a.equals(b)) return true;
        if (a instanceof Type.AliasRef || b instanceof Type.AliasRef) {
            if (!assumed.add(List.of(a, b))) return true;
            return same(expand(a), expand(b), assumed);
        }
        return switch (a) {
            case Type.Nominal an when b instanceof Type.Nominal bn ->
                    an.decl() == bn.decl() && sameAll(an.args(), bn.args(), assumed);
            case Type.Tuple at when b instanceof Type.Tuple bt -> sameAll(at.elements(), bt.elements(), assumed);
            case Type.Array aa when b instanceof Type.Array ba -> aa.size() == ba.size() && same(aa.element(), ba.element(), assumed);
            case Type.Fn af when b instanceof Type.Fn bf -> af.isExtern() == bf.isExtern()
                    && same(af.ret(), bf.ret(), assumed) && sameAll(af.params(), bf.params(), assumed);
            case Type.Union au when b instanceof Type.Union bu -> au.members().size() == bu.members().size()
                    && au.members().stream().allMatch(m -> bu.members().stream().anyMatch(n -> same(m, n, assumed)));
            default -> false;
        };
    }

    private static boolean sameAll(List<Type> as, List<Type> bs, Set<List<Type>> assumed) {
        if (as.size() != bs.size()) return false;
        for (int i = 0; i < as.size(); i++) if (!same(as.get(i), bs.get(i), assumed)) return false;
        return true;
    }

    /** {@code t}'s instance of the class {@code base}, walking the base chain; null if none. */
    public static Type.Nominal baseInstance(Type.Nominal t, TypeInfo base) {
        Type.Nominal cur = t;
        while (cur != null) {
            if (cur.decl() == base) return cur;
            var b = cur.decl().base;
            cur = b == null ? null : substNominal(b, bind(cur.decl().typeParams, cur.args()));
        }
        return null;
    }

    // ---- Traits ----

    /** Supertraits of a trait instance, transitively, with type arguments substituted. */
    public static List<Type.Nominal> superTraits(Type.Nominal trait) {
        var out = new ArrayList<Type.Nominal>();
        var seen = new HashSet<TypeInfo>();
        collectSupers(trait, out, seen);
        return out;
    }

    private static void collectSupers(Type.Nominal trait, List<Type.Nominal> out, Set<TypeInfo> seen) {
        var env = bind(trait.decl().typeParams, trait.args());
        for (var s : trait.decl().traits) {
            var inst = substNominal(s, env);
            if (seen.add(inst.decl())) {
                out.add(inst);
                collectSupers(inst, out, seen);
            }
        }
    }

    /** {@code trait} and its supertraits. */
    public static List<Type.Nominal> selfAndSupers(Type.Nominal trait) {
        var out = new ArrayList<Type.Nominal>();
        out.add(trait);
        out.addAll(superTraits(trait));
        return out;
    }

    private final Set<Type> implementsInProgress = new HashSet<>();

    /**
     * The instance of {@code trait} that {@code t} implements (e.g. {@code Iterable<i32>} for
     * {@code [i32]}), or null. Covers type-parameter bounds, trait references and their
     * supertraits, impls (also through base classes, and on trait references), and the automatic
     * impls: struct, tuple and union {@code Eq}/{@code Hash}, class and trait-reference {@code Eq} (§3.4, §4.5).
     */
    public Type.Nominal implementsAs(Type t, TypeInfo trait) {
        t = expand(t);
        switch (t) {
            case Type.Param p -> {
                for (var b : p.param().bounds) {
                    for (var s : selfAndSupers(b)) if (s.decl() == trait) return s;
                }
                return null;
            }
            case Type.Lit l -> {
                return implementsAs(l.base(), trait);
            }
            case Prim p -> {
                return viaImpl(prims.get(p), p, trait);
            }
            case Type.Nominal n -> {
                if (n.decl().isTrait()) {
                    for (var s : selfAndSupers(n)) if (s.decl() == trait) return s;
                    if (trait == eq) return nominal(eq);
                    return viaImpl(n.decl(), n, trait);
                }
                for (Type.Nominal cur = n; cur != null; ) {
                    var found = viaImpl(cur.decl(), cur, trait);
                    if (found != null) return found;
                    var b = cur.decl().base;
                    cur = b == null ? null : substNominal(b, bind(cur.decl().typeParams, cur.args()));
                }
                if (n.decl().isClass() && trait == eq) return nominal(eq);
                if (n.decl().isStruct() && (trait == eq || trait == hash)) {
                    if (!implementsInProgress.add(n)) return nominal(trait);
                    try {
                        var env = bind(n.decl().typeParams, n.args());
                        for (FieldInfo f : n.decl().allFields) {
                            if (implementsAs(subst(f.type, env), trait) == null) return null;
                        }
                        return nominal(trait);
                    } finally {
                        implementsInProgress.remove(n);
                    }
                }
                return null;
            }
            case Type.Tuple tu -> {
                if (trait != eq && trait != hash) return null;
                for (Type e : tu.elements()) if (implementsAs(e, trait) == null) return null;
                return nominal(trait);
            }
            case Type.Union u -> {
                var base = literalBase(u);
                if (base != u) return implementsAs(base, trait);
                if (trait == eq || trait == hash) {
                    for (Type m : u.members()) if (m != Type.NULL && implementsAs(m, trait) == null) return null;
                    return nominal(trait);
                }
                if (!unionSafe(trait)) return null;
                // A recursive alias (`type Json = null | [Json] | ...`) implements it unless shown otherwise.
                var key = List.<Object>of(u, trait);
                if (!unionsInProgress.add(key)) return nominal(trait);
                try {
                    Type.Nominal common = null;
                    for (Type m : u.members()) {
                        var inst = implementsAs(m, trait);
                        if (inst == null || (common != null && !common.equals(inst))) return null;
                        common = inst;
                    }
                    return common;
                } finally {
                    unionsInProgress.remove(key);
                }
            }
            case Type.Array a -> {
                if (trait == len || trait == truthy) return nominal(trait);
                if (trait == iterable) return nominal(trait, a.element());
                if (trait == contains) return implementsAs(a.element(), eq) != null ? nominal(trait, a.element()) : null;
                if (trait == index || trait == indexMut) return nominal(trait, Prim.USIZE, a.element());
                if (trait == eq || trait == hash) return implementsAs(a.element(), trait) != null ? nominal(trait) : null;
                return null;
            }
            case Type.Special s -> {
                if (s == Type.Special.UNIT && (trait == eq || trait == hash)) return nominal(trait);
                if (s == Type.Special.NULL && trait == display) return nominal(trait);
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private final Set<List<Object>> unionsInProgress = new HashSet<>();
    private final Map<TypeInfo, Boolean> unionSafe = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Whether a union may implement {@code trait} (§3.4): no static members and `Self` only as the
     * receiver, in the trait and its supertraits.
     */
    public boolean unionSafe(TypeInfo trait) {
        return unionSafe.computeIfAbsent(trait, t -> {
            var decls = new ArrayList<TypeInfo>(List.of(t));
            for (int i = 0; i < decls.size(); i++) {
                for (var s : decls.get(i).traits) if (!decls.contains(s.decl())) decls.add(s.decl());
            }
            for (var d : decls) {
                var self = Set.of(d.selfParam);
                for (var fns : d.methods.values()) {
                    for (FnInfo f : fns) {
                        if (f.isStatic() || mentions(f.ret, self)) return false;
                        for (var p : f.params) if (mentions(p.type(), self)) return false;
                    }
                }
            }
            return true;
        });
    }

    private Type.Nominal viaImpl(TypeInfo decl, Type target, TypeInfo trait) {
        if (decl == null) return null;
        var impl = impl(trait, decl);
        if (impl == null) return null;
        var env = matchImpl(impl, target);
        if (env == null) return null;
        for (var p : impl.typeParams) {
            var arg = env.get(p);
            if (arg == null) continue;
            for (var b : p.bounds) {
                if (implementsAs(arg, b.decl()) == null) return null;
            }
        }
        return substNominal(impl.trait, env);
    }

    /** Binds the impl's type parameters so that its target equals {@code target}; null if it can't. */
    public Map<TypeParamInfo, Type> matchImpl(ImplInfo impl, Type target) {
        var env = new HashMap<TypeParamInfo, Type>();
        if (!match(impl.target, target, env, new HashSet<>(impl.typeParams))) return null;
        return env;
    }

    private boolean match(Type pattern, Type actual, Map<TypeParamInfo, Type> env, Set<TypeParamInfo> vars) {
        pattern = expand(pattern);
        actual = expand(actual);
        if (pattern instanceof Type.Param p && vars.contains(p.param())) {
            var prev = env.putIfAbsent(p.param(), actual);
            return prev == null || prev.equals(actual);
        }
        if (pattern instanceof Type.Nominal pn && actual instanceof Type.Nominal an) {
            if (pn.decl() != an.decl() || pn.args().size() != an.args().size()) return false;
            for (int i = 0; i < pn.args().size(); i++) {
                if (!match(pn.args().get(i), an.args().get(i), env, vars)) return false;
            }
            return true;
        }
        return pattern.equals(actual);
    }

    // ---- Inference (§10) ----

    /**
     * Unifies a parameter type against an argument type, binding the type parameters in
     * {@code vars}. Mismatches are left for the assignability check.
     */
    public void unify(Type param, Type arg, Map<TypeParamInfo, Type> env, Set<TypeParamInfo> vars) {
        param = expand(param);
        arg = expand(arg);
        if (arg == Type.ERROR || arg == Type.NEVER) return;
        switch (param) {
            case Type.Param p when vars.contains(p.param()) -> {
                var prev = env.get(p.param());
                var widened = widen(arg);
                if (prev == null) env.put(p.param(), widened);
                else if (!assignable(widened, prev) && assignable(prev, widened)) env.put(p.param(), widened);
            }
            case Type.Nominal pn -> {
                if (arg instanceof Type.Nominal an && an.decl() == pn.decl()) {
                    unifyAll(pn.args(), an.args(), env, vars);
                } else if (pn.decl().isTrait()) {
                    var inst = implementsAs(arg, pn.decl());
                    if (inst != null) unifyAll(pn.args(), inst.args(), env, vars);
                } else if (pn.decl().isClass() && arg instanceof Type.Nominal an) {
                    var base = baseInstance(an, pn.decl());
                    if (base != null) unifyAll(pn.args(), base.args(), env, vars);
                }
            }
            case Type.Tuple pt -> {
                if (arg instanceof Type.Tuple at && at.elements().size() == pt.elements().size()) {
                    unifyAll(pt.elements(), at.elements(), env, vars);
                }
            }
            case Type.Array pa -> {
                if (arg instanceof Type.Array aa) unify(pa.element(), aa.element(), env, vars);
            }
            case Type.Fn pf -> {
                if (arg instanceof Type.Fn af && af.params().size() == pf.params().size()) {
                    unifyAll(pf.params(), af.params(), env, vars);
                    unify(pf.ret(), af.ret(), env, vars);
                }
            }
            case Type.Union pu -> {
                // Members without inference variables absorb matching argument members; a single
                // generic member takes the rest: `T | null` against `i32 | null` binds T = i32.
                var generic = new ArrayList<Type>();
                var fixed = new ArrayList<Type>();
                for (Type m : pu.members()) (mentions(m, vars) ? generic : fixed).add(m);
                if (generic.size() != 1) return;
                var rest = new ArrayList<Type>();
                for (Type a : members(arg)) {
                    boolean absorbed = false;
                    for (Type f : fixed) if (assignable(a, f)) absorbed = true;
                    if (!absorbed) rest.add(a);
                }
                if (!rest.isEmpty()) unify(generic.getFirst(), union(rest), env, vars);
            }
            default -> {}
        }
    }

    private void unifyAll(List<Type> ps, List<Type> as, Map<TypeParamInfo, Type> env, Set<TypeParamInfo> vars) {
        for (int i = 0; i < ps.size() && i < as.size(); i++) unify(ps.get(i), as.get(i), env, vars);
    }

    public static boolean mentions(Type t, Set<TypeParamInfo> vars) {
        return switch (expand(t)) {
            case Type.Param p -> vars.contains(p.param());
            case Type.Nominal n -> n.args().stream().anyMatch(a -> mentions(a, vars));
            case Type.Tuple tu -> tu.elements().stream().anyMatch(a -> mentions(a, vars));
            case Type.Array a -> mentions(a.element(), vars);
            case Type.Fn f -> mentions(f.ret(), vars) || f.params().stream().anyMatch(a -> mentions(a, vars));
            case Type.Union u -> u.members().stream().anyMatch(a -> mentions(a, vars));
            default -> false;
        };
    }
}
