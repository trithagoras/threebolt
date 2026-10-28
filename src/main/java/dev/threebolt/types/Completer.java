package dev.threebolt.types;

import dev.threebolt.ast.Expr;
import dev.threebolt.ast.Name;
import dev.threebolt.ast.Visibility;
import dev.threebolt.diag.Span;
import dev.threebolt.ir.Local;
import dev.threebolt.resolve.Module;
import dev.threebolt.resolve.ModulePath;
import dev.threebolt.types.Completion.Item;
import dev.threebolt.types.Completion.Kind;
import dev.threebolt.types.Completion.Site;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers a {@link Completion} from inside the checker: the hooks in {@link Exprs}, {@link Calls},
 * {@link Names} and {@link TypeResolver} call it where they meet the placeholder or the call
 * around the cursor, and it lists what is visible there. Members are listed by asking the
 * checker's own lookups about each candidate name, so the list matches what would resolve.
 */
final class Completer {
    private static final List<String> PRIM_CONSTANTS = List.of("MIN", "MAX", "EPSILON", "INFINITY", "NEG_INFINITY", "NAN");

    private final Checker c;
    private final Completion out;
    /** Set while a type expression's path is resolved: module items are then types only. */
    boolean typePosition;

    Completer(Checker c, Completion out) {
        this.c = c;
        this.out = out;
    }

    /** Whether {@code name} is the placeholder. */
    boolean at(Name name) {
        return Completion.prefixOf(name.text()) != null;
    }

    // ---- Sites ----

    /** {@code receiver.|}: fields, getters and instance methods visible here. */
    void members(Type receiver, Name name) {
        var items = new LinkedHashMap<String, Item>();
        var t = TypeSystem.expand(TypeSystem.literalBase(receiver));
        TypeInfo own = ownDecl(t);
        if (t instanceof Type.Tuple tu) {
            for (int i = 0; i < tu.elements().size(); i++) {
                items.put("" + i, new Item("" + i, Kind.FIELD, i + ": " + tu.elements().get(i), 1));
            }
        }
        if (t instanceof Type.Nominal n && (n.decl().isStruct() || n.decl().isClass())) {
            for (FieldInfo f : n.decl().allFields) {
                if (!f.owner().isStruct() && !quietly(() -> c.exprs.checkAccess(f.owner(), f.visibility, "", name.span()))) continue;
                var base = n.decl() == f.owner() ? n : TypeSystem.baseInstance(n, f.owner());
                var type = TypeSystem.subst(f.type, TypeSystem.bind(f.owner().typeParams, base.args()));
                var detail = SymbolIndex.describe(new SymbolIndex.Ref(null, f, type, false));
                items.put(f.name(), new Item(f.name(), Kind.FIELD, detail, f.owner() == n.decl() ? 1 : 2, f));
            }
        }
        for (String m : methodNames(t)) {
            if (items.containsKey(m)) continue;
            var cands = c.calls.methodCandidates(t, m).stream().filter(k -> !k.fn().isStatic())
                    .filter(k -> quietly(() -> c.calls.checkMethodAccess(k.fn(), name.span()))).toList();
            if (cands.isEmpty()) continue;
            var first = cands.getFirst();
            var kind = first.fn().isGetter() ? Kind.PROPERTY : Kind.METHOD;
            int rank = operatorMethod(t, m) ? 3 : first.fn().owner == own ? 1 : 2;
            items.put(m, new Item(m, kind, signature(first.fn(), first.env(), cands.size()), rank, first.fn()));
        }
        record(Site.MEMBER, name, items.values(), null);
    }

    /**
     * Whether {@code name} on {@code t} is what an operator or condition calls (`add` for `+`, `eq`
     * for `==`, `truthy` for `if x`): {@code t} implements the trait it comes from. Rarely written out.
     */
    private boolean operatorMethod(Type t, String name) {
        var traits = new ArrayList<TypeInfo>(c.ts.operatorTraits.values());
        traits.addAll(List.of(c.ts.eq, c.ts.ord, c.ts.hash, c.ts.truthy, c.ts.index, c.ts.indexMut));
        for (var trait : traits) {
            if (trait != null && !trait.methods(name).isEmpty() && c.ts.implementsAs(t, trait) != null) return true;
        }
        return false;
    }

    /** {@code Type::|}: enum members, constants, statics and static functions. */
    void statics(Type owner, TypeInfo decl, boolean argsGiven, Expr.PathSegment seg) {
        var names = new LinkedHashSet<String>();
        if (owner instanceof Type.Param p) {
            for (var b : p.param().bounds) traitNames(b.decl(), names);
        } else if (decl != null) {
            if (decl.isEnum()) names.addAll(c.consts.enumValues(decl).keySet());
            if (decl.kind() == TypeInfo.Kind.PRIMITIVE) names.addAll(PRIM_CONSTANTS);
            for (TypeInfo t = decl; t != null; t = t.baseDecl()) {
                names.addAll(t.statics.keySet());
                implNames(t, names);
            }
            names.addAll(decl.methods.keySet());
        }
        var items = new ArrayList<Item>();
        for (String n : names) {
            var probe = new Expr.PathSegment(new Name(n, seg.name().span()), List.of(), seg.span());
            Exprs.Target[] target = new Exprs.Target[1];
            if (!quietly(() -> target[0] = c.exprs.memberTarget(owner, decl, argsGiven, probe, false))) continue;
            var item = switch (target[0]) {
                case Exprs.ValueTarget v when decl != null && decl.isEnum() ->
                        new Item(n, Kind.ENUM_MEMBER, decl.name() + "::" + n, 1, new SymbolIndex.EnumMember(decl, n));
                case Exprs.ValueTarget v -> new Item(n, Kind.CONSTANT, "const " + owner + "::" + n + ": " + v.value().type(), 1);
                case Exprs.StaticTarget s -> new Item(n, Kind.STATIC, SymbolIndex.describe(new SymbolIndex.Ref(null, s.info(), null, false)),
                        s.info().owner == decl ? 1 : 2, s.info());
                case Exprs.FnTarget f -> {
                    var k = f.candidates().getFirst();
                    yield new Item(n, k.ufcs() ? Kind.METHOD : Kind.FUNCTION, signature(k.fn(), k.env(), f.candidates().size()),
                            k.fn().owner == decl ? 1 : 2, k.fn());
                }
                default -> null;
            };
            if (item != null) items.add(item);
        }
        record(Site.STATIC_MEMBER, seg.name(), items, null);
    }

    /** {@code module::|}: its items ({@code pub} ones from another module) and loaded submodules. */
    void moduleItems(Module m, Module from, Name name) {
        var items = new ArrayList<Item>();
        for (var e : m.scope().items().entrySet()) {
            var decls = m == from ? e.getValue()
                    : e.getValue().stream().filter(d -> d.item().visibility() == Visibility.PUB).toList();
            if (decls.isEmpty()) continue;
            var item = entityItem(e.getKey(), c.names.entity(decls), 2);
            if (!typePosition || isTypeLike(item.kind())) items.add(item);
        }
        items.addAll(submodules(m.path()));
        record(Site.MODULE_ITEM, name, items, m.path());
    }

    /** {@code a::b::|} where {@code a::b} is not a module itself, e.g. {@code std::|}. */
    void modulePrefix(ModulePath prefix, Name name) {
        record(Site.MODULE_ITEM, name, submodules(prefix), prefix);
    }

    /** A bare name in an expression: locals, then the module's names, the prelude and primitives. */
    void names(Name name) {
        var items = new LinkedHashMap<String, Item>();
        var ctx = c.ctx;
        for (var s = ctx.scope; s != null; s = s.parent) {
            for (Local l : s.locals.values()) {
                if (items.containsKey(l.name()) || Completion.prefixOf(l.name()) != null) continue;
                var type = c.typeOf(l);
                if (type == Type.UNBOUND) continue;
                var detail = SymbolIndex.describe(new SymbolIndex.Ref(null, l, type, false));
                items.put(l.name(), new Item(l.name(), Kind.VARIABLE, detail, 0));
            }
        }
        if (ctx.self != null) {
            items.put("self", new Item("self", Kind.VARIABLE, SymbolIndex.describe(new SymbolIndex.Ref(null, ctx.self, null, false)), 0));
        }
        typeScopeNames(ctx.typeScope, items);
        moduleScopeNames(ctx.module, items, false);
        record(Site.NAME, name, items.values(), null);
    }

    /** A bare name in a type: type parameters, `Self`, then the module's types and modules. */
    void types(TypeResolver.Scope scope, Name name) {
        var items = new LinkedHashMap<String, Item>();
        typeScopeNames(scope, items);
        moduleScopeNames(scope.module(), items, true);
        record(Site.TYPE, name, items.values(), null);
    }

    private void typeScopeNames(TypeResolver.Scope scope, Map<String, Item> items) {
        for (var p : scope.params().values()) {
            items.putIfAbsent(p.name(), new Item(p.name(), Kind.TYPE_PARAMETER,
                    SymbolIndex.describe(new SymbolIndex.Ref(null, p, null, false)), 1));
        }
        if (scope.self() != null) items.putIfAbsent("Self", new Item("Self", Kind.TYPE_PARAMETER, "Self = " + scope.self(), 1));
    }

    private void moduleScopeNames(Module m, Map<String, Item> items, boolean typesOnly) {
        var own = new LinkedHashSet<String>(m.scope().items().keySet());
        own.addAll(m.scope().imports().keySet());
        for (String n : own) addEntity(m, n, 1, items, typesOnly);
        for (String n : m.scope().prelude().keySet()) addEntity(m, n, 3, items, typesOnly);
        for (Prim p : Prim.values()) {
            if (Prim.named(p.text()) == p) items.putIfAbsent(p.text(), new Item(p.text(), Kind.PRIMITIVE, "primitive " + p, 3));
        }
    }

    private void addEntity(Module m, String n, int rank, Map<String, Item> items, boolean typesOnly) {
        if (items.containsKey(n)) return;
        var e = c.names.lookup(m, n);
        if (e == null) return;
        var item = entityItem(n, e, rank);
        if (!typesOnly || isTypeLike(item.kind())) items.put(n, item);
    }

    // ---- Signature help ----

    /** Whether the cursor is inside {@code e}'s argument list, in the module being checked. */
    private boolean around(Expr.Call e) {
        var file = c.ctx == null ? null : c.ctx.module.file();
        var s = e.span();
        return file != null && file.equals(out.file) && s.start() < out.offset && out.offset <= s.end()
                && out.offset > e.callee().span().end();
    }

    /** A call of a named function or method: one signature per overload. */
    void call(Expr.Call e, List<Calls.Candidate> cands) {
        if (!around(e) || !replacesCall(e.span())) return;
        var sigs = new ArrayList<Completion.Signature>();
        for (var k : cands) {
            var params = new ArrayList<int[]>();
            var label = SymbolIndex.signature(k.fn(), k.env(), params, k.ufcs());
            var names = new ArrayList<String>();
            if (k.ufcs()) names.add("self");
            k.fn().params.forEach(p -> names.add(p.name()));
            sigs.add(new Completion.Signature(label, params, names, k.fn()));
        }
        setCall(e, sigs);
    }

    /** A call of a function value: its type is the signature. */
    void callValue(Expr.Call e, Type.Fn f) {
        if (!around(e) || !replacesCall(e.span())) return;
        var sb = new StringBuilder(f.isExtern() ? "extern fn(" : "fn(");
        var params = new ArrayList<int[]>();
        var names = new ArrayList<String>();
        for (int i = 0; i < f.params().size(); i++) {
            if (i > 0) sb.append(", ");
            String p = f.params().get(i).toString();
            params.add(new int[] {sb.length(), sb.length() + p.length()});
            names.add("");
            sb.append(p);
        }
        sb.append(')');
        if (f.ret() != Type.UNIT) sb.append(" -> ").append(f.ret());
        setCall(e, List.of(new Completion.Signature(sb.toString(), params, names, null)));
    }

    /** Calls are checked outside in, so a call within the one recorded is the innermost so far. */
    private boolean replacesCall(Span s) {
        var old = out.call;
        if (old == null) return true;
        if (s.start() == old.start() && s.end() == old.end()) return out.callMuted && !c.index.isMuted();
        return old.start() <= s.start() && s.end() <= old.end();
    }

    private void setCall(Expr.Call e, List<Completion.Signature> sigs) {
        out.call = e.span();
        out.argsFrom = e.callee().span().end();
        out.signatures = sigs;
        out.callMuted = c.index.isMuted();
    }

    // ---- Helpers ----

    private void record(Site site, Name name, Collection<Item> items, ModulePath module) {
        boolean muted = c.index.isMuted();
        if (out.site != null && muted && !out.siteMuted) return;
        out.site = site;
        out.prefix = Completion.prefixOf(name.text());
        out.items = List.copyOf(items);
        out.module = module;
        out.siteMuted = muted;
    }

    /** Runs a check with diagnostics discarded; true if it reported nothing. */
    private boolean quietly(Runnable check) {
        int before = c.diag.dropped();
        c.diag.muted(() -> {
            check.run();
            return null;
        });
        return c.diag.dropped() == before;
    }

    private TypeInfo ownDecl(Type t) {
        return switch (t) {
            case Type.Nominal n -> n.decl();
            case Prim p -> c.ts.prims.get(p);
            default -> null;
        };
    }

    /** Every name that might be a method of {@code t}; {@link Calls#methodCandidates} decides. */
    private Set<String> methodNames(Type t) {
        var out = new LinkedHashSet<String>();
        switch (t) {
            case Prim p -> {
                var decl = c.ts.prims.get(p);
                if (decl != null) {
                    out.addAll(decl.methods.keySet());
                    implNames(decl, out);
                }
            }
            case Type.Nominal n -> {
                for (TypeInfo d = n.decl(); d != null; d = d.baseDecl()) {
                    if (d.isTrait()) traitNames(d, out);
                    out.addAll(d.methods.keySet());
                    implNames(d, out);
                }
            }
            case Type.Param p -> {
                for (var b : p.param().bounds) traitNames(b.decl(), out);
            }
            case Type.Union u -> {
                for (TypeInfo tr : c.ts.implementedTraits()) traitNames(tr, out);
                for (Type m : u.members()) out.addAll(methodNames(TypeSystem.expand(m)));
            }
            case Type.Array a -> out.addAll(Calls.ARRAY_METHODS);
            default -> {}
        }
        return out;
    }

    private void implNames(TypeInfo decl, Set<String> out) {
        for (ImplInfo impl : c.ts.implsOf(decl)) traitNames(impl.trait.decl(), out);
    }

    /** The members of {@code trait} and its supertraits. */
    private static void traitNames(TypeInfo trait, Set<String> out) {
        traitNames(trait, out, new HashSet<>());
    }

    private static void traitNames(TypeInfo trait, Set<String> out, Set<TypeInfo> seen) {
        if (!seen.add(trait)) return;
        out.addAll(trait.methods.keySet());
        for (var s : trait.traits) traitNames(s.decl(), out, seen);
    }

    private static String signature(FnInfo f, Map<TypeParamInfo, Type> env, int overloads) {
        var s = SymbolIndex.signature(f, env, null, false);
        return overloads > 1 ? s + "  (+" + (overloads - 1) + " overload" + (overloads > 2 ? "s" : "") + ")" : s;
    }

    private List<Item> submodules(ModulePath parent) {
        var items = new ArrayList<Item>();
        for (var path : c.program.modules().keySet()) {
            if (parent.equals(path.parent())) items.add(new Item(path.last(), Kind.MODULE, "mod " + path, 2));
        }
        return items;
    }

    private static boolean isTypeLike(Kind k) {
        return switch (k) {
            case STRUCT, CLASS, TRAIT, ENUM, TYPE_ALIAS, PRIMITIVE, MODULE, TYPE_PARAMETER -> true;
            default -> false;
        };
    }

    private static Item entityItem(String name, Names.Entity e, int rank) {
        return switch (e) {
            case Names.ModuleEntity(Module m) -> new Item(name, Kind.MODULE, "mod " + m.path(), rank);
            case Names.PrimEntity(Prim p) -> new Item(name, Kind.PRIMITIVE, "primitive " + p, rank);
            case Names.TypeEntity(TypeInfo t) -> new Item(name, switch (t.kind()) {
                case STRUCT -> Kind.STRUCT;
                case CLASS -> Kind.CLASS;
                case TRAIT -> Kind.TRAIT;
                case ENUM -> Kind.ENUM;
                case ALIAS -> Kind.TYPE_ALIAS;
                case PRIMITIVE -> Kind.PRIMITIVE;
            }, SymbolIndex.describe(new SymbolIndex.Ref(null, t, null, false)), rank, t);
            case Names.FnEntity(List<FnInfo> fns) -> new Item(name, Kind.FUNCTION, signature(fns.getFirst(), Map.of(), fns.size()), rank, fns.getFirst());
            case Names.ConstEntity(ConstInfo k) -> new Item(name, Kind.CONSTANT, SymbolIndex.describe(new SymbolIndex.Ref(null, k, null, false)), rank, k);
            case Names.StaticEntity(StaticInfo s) -> new Item(name, Kind.STATIC, SymbolIndex.describe(new SymbolIndex.Ref(null, s, null, false)), rank, s);
        };
    }
}
