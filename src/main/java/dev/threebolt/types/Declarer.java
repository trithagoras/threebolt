package dev.threebolt.types;

import dev.threebolt.ast.Attribute;
import dev.threebolt.ast.Dispatch;
import dev.threebolt.ast.Item;
import dev.threebolt.ast.Member;
import dev.threebolt.ast.Param;
import dev.threebolt.ast.TypeExpr;
import dev.threebolt.ast.TypeParam;
import dev.threebolt.ast.Visibility;
import dev.threebolt.diag.Code;
import dev.threebolt.diag.Diagnostic;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Source;
import dev.threebolt.diag.Span;
import dev.threebolt.parse.AstParser;
import dev.threebolt.resolve.Module;
import dev.threebolt.resolve.ModulePath;
import dev.threebolt.resolve.Program;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The declaration pass: creates the semantic info for every item and member of the program,
 * resolves signatures, base lists and field layouts, registers trait implementations, and checks
 * the declaration rules (§4: overrides, abstract members, trait conformance, overloads, getters,
 * and the §2.4 `main` signature). Bodies are left to the {@link BodyChecker}.
 */
public final class Declarer {
    private final Program program;
    private final Diagnostics diagnostics;
    final TypeSystem ts = new TypeSystem();
    final Names names;
    final TypeResolver types;

    final List<TypeInfo> allTypes = new ArrayList<>();
    /** Every function with a body or intrinsic, including methods and impl members. */
    final List<FnInfo> allFns = new ArrayList<>();
    final List<StaticInfo> statics = new ArrayList<>();
    final List<ConstInfo> consts = new ArrayList<>();
    final List<ImplInfo> userImpls = new ArrayList<>();
    private final List<Map.Entry<Module, Item.Impl>> implItems = new ArrayList<>();
    private final Module prelude;

    public Declarer(Program program, Diagnostics diagnostics) {
        this(program, diagnostics, SymbolIndex.disabled());
    }

    Declarer(Program program, Diagnostics diagnostics, SymbolIndex index) {
        this.program = program;
        this.diagnostics = diagnostics;
        this.names = new Names(program, diagnostics, index);
        this.types = new TypeResolver(ts, names, diagnostics);
        this.prelude = program.module(ModulePath.of("std", "prelude"));
    }

    public void run() {
        for (Module m : program.all()) if (m.ast() != null) collect(m);
        builtins();
        for (TypeInfo t : allTypes) typeParams(t);
        for (FnInfo f : allFns) fnTypeParams(f);
        for (TypeInfo t : allTypes) bases(t);
        for (TypeInfo t : allTypes) layout(t, new HashSet<>());
        for (TypeInfo t : allTypes) members(t);
        for (FnInfo f : allFns) if (f.owner == null) signature(f, scopeOf(f));
        for (StaticInfo s : statics) if (s.owner == null) s.type = resolve(s.ast.type(), TypeResolver.Scope.of(s.module));
        for (ConstInfo c : consts) c.type = resolve(c.ast.type(), TypeResolver.Scope.of(c.module));
        primitiveMembers();
        for (var e : implItems) implItem(e.getKey(), e.getValue());
        for (TypeInfo t : inheritanceOrder()) baseListImpls(t);
        builtinImpls();
        types.runDeferredBounds();
        for (ImplInfo i : userImpls) checkImpl(i);
        for (TypeInfo t : allTypes) checkType(t);
        checkOverloads();
        checkAbi();
        checkMain();
    }

    // ---- Collection ----

    private void collect(Module m) {
        for (Item item : m.ast().items()) {
            switch (item) {
                case Item.Struct s -> {
                    var t = type(TypeInfo.Kind.STRUCT, s.name().text(), m, s);
                    t.isC = hasAttribute(s.attributes(), "c");
                    members(t, s.members());
                }
                case Item.Class c -> {
                    var t = type(TypeInfo.Kind.CLASS, c.name().text(), m, c);
                    t.isAbstract = c.isAbstract();
                    members(t, c.members());
                }
                case Item.Trait tr -> {
                    var t = type(TypeInfo.Kind.TRAIT, tr.name().text(), m, tr);
                    t.selfParam = new TypeParamInfo("Self", tr.name().span());
                    members(t, tr.members());
                }
                case Item.Enum e -> {
                    var t = type(TypeInfo.Kind.ENUM, e.name().text(), m, e);
                    t.isFlags = hasAttribute(e.attributes(), "flags");
                }
                case Item.TypeAlias a -> type(TypeInfo.Kind.ALIAS, a.name().text(), m, a).aliasExpr = a.type();
                case Item.Fn f -> fn(f, m, null);
                case Item.Extern x -> {
                    String lib = null;
                    for (Attribute a : x.attributes()) {
                        if (a.name().text().equals("link") && !a.args().isEmpty()
                                && a.args().getFirst() instanceof dev.threebolt.ast.Expr.StrLit s) lib = s.value();
                    }
                    if (!x.abi().equals("C")) {
                        diagnostics.error(Code.BAD_MODIFIER, x.span(), "only the \"C\" ABI is supported");
                    }
                    for (Item.Fn f : x.fns()) {
                        var info = fn(f, m, null);
                        info.isExtern = true;
                        info.intrinsic = null;
                        info.linkLibrary = lib;
                    }
                }
                case Item.Const c -> {
                    var info = new ConstInfo(c, m);
                    consts.add(info);
                    names.infos.put(c, info);
                }
                case Item.Static s -> {
                    var info = new StaticInfo(s, m, null);
                    statics.add(info);
                    names.infos.put(s, info);
                }
                case Item.Impl i -> implItems.add(Map.entry(m, i));
                case Item.Use u -> {}
            }
            checkAttributePlacement(item);
        }
    }

    private void checkAttributePlacement(Item item) {
        for (Attribute a : item.attributes()) {
            boolean ok = switch (a.name().text()) {
                case "c" -> item instanceof Item.Struct;
                case "flags" -> item instanceof Item.Enum;
                case "link" -> item instanceof Item.Extern;
                default -> true; // unknown attributes are reported by the loader
            };
            if (!ok) diagnostics.error(Code.BAD_MODIFIER, a.span(), "'@" + a.name().text() + "' is not allowed here");
        }
    }

    private static boolean hasAttribute(List<Attribute> attributes, String name) {
        return attributes.stream().anyMatch(a -> a.name().text().equals(name));
    }

    private TypeInfo type(TypeInfo.Kind kind, String name, Module m, Item item) {
        var t = new TypeInfo(kind, name, m, item);
        t.visibility = item.visibility();
        allTypes.add(t);
        names.infos.put(item, t);
        return t;
    }

    /** Keywords std members may be named with a trailing `_` (§1: keywords are names after `::`). */
    private static final Set<String> KEYWORD_MEMBERS = Set.of("null", "type");

    private FnInfo fn(Item.Fn f, Module m, Object owner) {
        String name = f.name().text();
        if (m.isStd() && name.endsWith("_") && KEYWORD_MEMBERS.contains(name.substring(0, name.length() - 1))) {
            name = name.substring(0, name.length() - 1);
        }
        var info = new FnInfo(name, f, m, owner);
        boolean inTrait = owner instanceof TypeInfo t && t.isTrait();
        if (m.isStd() && f.body() == null && !inTrait) {
            String ownerName = owner instanceof TypeInfo t ? t.name() : m.path().last();
            info.intrinsic = ownerName + "." + info.name();
        }
        allFns.add(info);
        if (owner == null) names.infos.put(f, info);
        return info;
    }

    private void members(TypeInfo t, List<Member> members) {
        for (Member member : members) {
            switch (member) {
                case Member.Field f -> {
                    if (t.isTrait()) {
                        diagnostics.error(Code.BAD_MODIFIER, f.span(), "traits cannot declare fields");
                        continue;
                    }
                    t.fields.add(new FieldInfo(f.name().text(), t, f.visibility(), f.value(), f.span()));
                }
                case Item.Fn f -> t.addMethod(fn(f, t.module, t));
                case Item.Static s -> {
                    if (t.isTrait()) {
                        diagnostics.error(Code.BAD_MODIFIER, s.span(), "traits cannot declare statics");
                        continue;
                    }
                    var info = new StaticInfo(s, t.module, t);
                    statics.add(info);
                    t.statics.put(info.name(), info);
                }
            }
        }
    }

    // ---- Built-in types ----

    private void builtins() {
        for (Prim p : Prim.values()) ts.prims.put(p, new TypeInfo(p));
        var p = prelude.scope();
        ts.list = stdType(p.items("List"));
        ts.range = stdType(p.items("Range"));
        ts.done = stdType(p.items("Done"));
        ts.typeDecl = stdType(p.items("Type"));
        ts.ptr = stdType(p.items("Ptr"));
        ts.nativeIter = stdType(p.items("NativeIter"));
        var collections = program.module(ModulePath.of("std", "collections")).scope();
        ts.map = stdType(collections.items("Map"));
        ts.set = stdType(collections.items("Set"));
        ts.display = stdType(p.items("Display"));
        ts.eq = stdType(p.items("Eq"));
        ts.ord = stdType(p.items("Ord"));
        ts.hash = stdType(p.items("Hash"));
        ts.truthy = stdType(p.items("Truthy"));
        ts.len = stdType(p.items("Len"));
        ts.error = stdType(p.items("Err"));
        ts.defaultTrait = stdType(p.items("Default"));
        ts.iterator = stdType(p.items("Iterator"));
        ts.iterable = stdType(p.items("Iterable"));
        ts.contains = stdType(p.items("Contains"));
        ts.index = stdType(p.items("Index"));
        ts.indexMut = stdType(p.items("IndexMut"));
        ts.num = stdType(p.items("Num"));
        ts.signed = stdType(p.items("Signed"));
        ts.integer = stdType(p.items("Integer"));
        ts.floatTrait = stdType(p.items("Float"));
        for (String op : List.of("Add", "Sub", "Mul", "Div", "Rem", "Neg", "BitAnd", "BitOr", "BitXor", "Not", "Shl", "Shr")) {
            ts.operatorTraits.put(op, stdType(p.items(op)));
        }
    }

    private TypeInfo stdType(List<dev.threebolt.resolve.Symbol.Decl> decls) {
        return (TypeInfo) names.infos.get(decls.getFirst().item());
    }

    /** Attaches the intrinsic members of std/builtin/*.3b to the primitives. */
    private void primitiveMembers() {
        var lists = new HashMap<String, List<Member>>();
        for (String cat : List.of("int", "float", "str", "char", "bool", "enum", "flags", "cstruct")) {
            String name = "std/builtin/" + cat + ".3b";
            var parsed = AstParser.memberList(new Source(name, resource(name)), diagnostics);
            lists.put(cat, parsed == null ? List.of() : parsed);
        }
        for (Prim p : Prim.values()) {
            String cat = p.isInteger() ? "int" : p.isFloat() ? "float" : p.text();
            builtinMembers(ts.prims.get(p), cat, lists.get(cat));
        }
        for (TypeInfo t : allTypes) {
            if (t.isC && t.isStruct()) builtinMembers(t, "cstruct", lists.get("cstruct"));
            if (!t.isEnum()) continue;
            builtinMembers(t, "enum", lists.get("enum"));
            if (t.isFlags) builtinMembers(t, "flags", lists.get("flags"));
        }
    }

    private void builtinMembers(TypeInfo owner, String category, List<Member> members) {
        var scope = new TypeResolver.Scope(prelude, Map.of(), ts.selfType(owner));
        for (Member m : members) {
            if (!(m instanceof Item.Fn f)) continue;
            var info = new FnInfo(f.name().text(), f, prelude, owner);
            info.intrinsic = category + "." + info.name();
            fnTypeParams(info);
            signature(info, scope.with(info.typeParams));
            owner.addMethod(info);
        }
    }

    private static String resource(String name) {
        try (InputStream in = Declarer.class.getResourceAsStream("/" + name)) {
            if (in == null) throw new IllegalStateException("missing resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- Signatures ----

    private Type resolve(TypeExpr te, TypeResolver.Scope scope) {
        return types.resolve(te, scope);
    }

    /** The scope of a type's body: its module, its type parameters and `Self`. */
    TypeResolver.Scope scopeOf(TypeInfo t) {
        return TypeResolver.Scope.of(t.module).with(t.typeParams).withSelf(ts.selfType(t));
    }

    TypeResolver.Scope scopeOf(ImplInfo i) {
        return TypeResolver.Scope.of(i.module).with(i.typeParams).withSelf(i.target);
    }

    /** The scope of a function's signature and body. */
    TypeResolver.Scope scopeOf(FnInfo f) {
        TypeResolver.Scope base = switch (f.owner) {
            case TypeInfo t when t.kind() == TypeInfo.Kind.PRIMITIVE || (t.isEnum() && f.intrinsic != null) ->
                    new TypeResolver.Scope(prelude, Map.of(), ts.selfType(t));
            case TypeInfo t -> scopeOf(t);
            case ImplInfo i -> scopeOf(i);
            case null, default -> TypeResolver.Scope.of(f.module);
        };
        return base.with(f.typeParams);
    }

    private void typeParams(TypeInfo t) {
        List<TypeParam> params = switch (t.item) {
            case Item.Struct s -> s.typeParams();
            case Item.Class c -> c.typeParams();
            case Item.Trait tr -> tr.typeParams();
            case Item.TypeAlias a -> a.typeParams();
            default -> List.of();
        };
        t.typeParams = params.stream().map(p -> new TypeParamInfo(p.name().text(), p.span())).toList();
        if (t.isTrait()) {
            // Self is bounded by the trait itself.
            t.selfParam.bounds.add(new Type.Nominal(t, t.typeParams.stream().map(TypeParamInfo::type).toList()));
        }
        var scope = t.kind() == TypeInfo.Kind.ALIAS
                ? TypeResolver.Scope.of(t.module).with(t.typeParams)
                : scopeOf(t);
        bounds(t.typeParams, params, scope);
        if (t.isStd("Type") || t.isStd("List")) t.visibility = Visibility.PUB;
    }

    private void fnTypeParams(FnInfo f) {
        f.typeParams = f.ast.typeParams().stream().map(p -> new TypeParamInfo(p.name().text(), p.span())).toList();
    }

    private void bounds(List<TypeParamInfo> infos, List<TypeParam> params, TypeResolver.Scope scope) {
        for (int i = 0; i < infos.size(); i++) {
            for (TypeExpr.Named b : params.get(i).bounds()) {
                var bound = trait(b, scope);
                if (bound != null) infos.get(i).bounds.add(bound);
            }
        }
    }

    /** Resolves {@code te} as a trait; reports and returns null otherwise. */
    private Type.Nominal trait(TypeExpr.Named te, TypeResolver.Scope scope) {
        var t = types.named(te, scope);
        if (t instanceof Type.Nominal n && n.decl().isTrait()) return n;
        if (t != Type.ERROR) diagnostics.error(Code.BAD_BASE, te.span(), "'" + t + "' is not a trait");
        return null;
    }

    private void bases(TypeInfo t) {
        List<TypeExpr.Named> list = switch (t.item) {
            case Item.Struct s -> s.bases();
            case Item.Class c -> c.bases();
            case Item.Trait tr -> tr.supertraits();
            default -> List.of();
        };
        var scope = scopeOf(t);
        for (int i = 0; i < list.size(); i++) {
            var te = list.get(i);
            var resolved = types.named(te, scope);
            if (resolved == Type.ERROR) continue;
            if (resolved instanceof Type.Nominal n && n.decl().isClass() && t.isClass()) {
                if (i != 0) {
                    diagnostics.error(Code.BAD_BASE, te.span(), "the base class must come first in the base list");
                } else {
                    t.base = n;
                }
                continue;
            }
            if (resolved instanceof Type.Nominal n && n.decl().isTrait()) {
                if (t.traits.stream().anyMatch(x -> x.decl() == n.decl())) {
                    diagnostics.error(Code.BAD_BASE, te.span(), "'" + n + "' is listed twice");
                } else {
                    t.traits.add(n);
                }
                continue;
            }
            String what = t.isStruct() ? "a struct can only implement traits"
                    : t.isTrait() ? "a trait can only extend traits" : "'" + resolved + "' is not a class or trait";
            diagnostics.error(Code.BAD_BASE, te.span(), what);
        }
        if (t.isTrait()) checkSupertraitCycle(t);
    }

    private void checkSupertraitCycle(TypeInfo t) {
        var seen = new HashSet<TypeInfo>();
        var stack = new ArrayList<>(t.traits);
        while (!stack.isEmpty()) {
            var s = stack.removeLast().decl();
            if (s == t) {
                diagnostics.error(Code.CYCLE, ((Item.Trait) t.item).name().span(), "trait '" + t.name() + "' is its own supertrait");
                t.traits.clear();
                return;
            }
            if (seen.add(s)) stack.addAll(s.traits);
        }
    }

    private void layout(TypeInfo t, Set<TypeInfo> visiting) {
        if (!t.allFields.isEmpty() || (t.base == null && t.fields.isEmpty())) {
            if (t.allFields.isEmpty()) t.allFields = List.of();
            return;
        }
        if (!visiting.add(t)) {
            diagnostics.error(Code.CYCLE, ((Item.Class) t.item).name().span(), "class '" + t.name() + "' inherits from itself");
            t.base = null;
            return;
        }
        var all = new ArrayList<FieldInfo>();
        if (t.base != null) {
            layout(t.base.decl(), visiting);
            if (t.base != null) all.addAll(t.base.decl().allFields);
        }
        all.addAll(t.fields);
        for (int i = 0; i < all.size(); i++) if (all.get(i).owner() == t) all.get(i).slot = i;
        t.allFields = List.copyOf(all);
        visiting.remove(t);
    }

    private void members(TypeInfo t) {
        if (t.kind() == TypeInfo.Kind.ALIAS) {
            // Resolve now, so that errors are reported once even if unused.
            if (t.aliasType == null) types.instantiate(t, t.typeParams.stream().map(TypeParamInfo::type).toList(), t.span());
            return;
        }
        var scope = scopeOf(t);
        for (FieldInfo f : t.fields) {
            var ast = fieldAst(t, f);
            f.type = resolve(ast.type(), scope);
        }
        for (var fns : t.methods.values()) for (FnInfo f : fns) signature(f, scopeOf(f));
        for (StaticInfo s : t.statics.values()) {
            if (!t.typeParams.isEmpty()) {
                diagnostics.error(Code.BAD_MODIFIER, s.ast.span(), "static fields are not permitted in generic types");
            }
            s.type = resolve(s.ast.type(), scope);
        }
        if (t.isEnum()) {
            var e = (Item.Enum) t.item;
            if (e.backing() != null) {
                var b = resolve(e.backing(), scope);
                var p = TypeSystem.prim(b);
                if (p != null && p.isInteger()) t.backing = p;
                else if (b != Type.ERROR) diagnostics.error(Code.BAD_ENUM, e.backing().span(), "an enum must be backed by an integer type");
            }
        }
    }

    private static Member.Field fieldAst(TypeInfo t, FieldInfo f) {
        List<Member> members = switch (t.item) {
            case Item.Struct s -> s.members();
            case Item.Class c -> c.members();
            default -> List.of();
        };
        for (Member m : members) if (m instanceof Member.Field mf && mf.span() == f.span()) return mf;
        throw new IllegalStateException("field " + f);
    }

    /** Resolves receiver, parameters and return type. */
    private void signature(FnInfo f, TypeResolver.Scope scope) {
        bounds(f.typeParams, f.ast.typeParams(), scope);
        boolean seenOptional = false;
        for (int i = 0; i < f.ast.params().size(); i++) {
            Param p = f.ast.params().get(i);
            switch (p) {
                case Param.Receiver r -> {
                    if (i != 0 || f.owner == null) {
                        diagnostics.error(Code.BAD_MODIFIER, r.span(), "`self` is only allowed as the first parameter of a method");
                        continue;
                    }
                    f.receiver = r.isMutable() ? FnInfo.Receiver.MUT_SELF : FnInfo.Receiver.SELF;
                }
                case Param.Named n -> {
                    var type = resolve(n.type(), scope);
                    if (n.defaultValue() != null) seenOptional = true;
                    else if (seenOptional) {
                        diagnostics.error(Code.BAD_ARGUMENTS, n.span(),
                                "required parameter '" + n.name().text() + "' must come before parameters with defaults");
                    }
                    f.params.add(new FnInfo.ParamInfo(n.name().text(), type, n.isMutable(), n.defaultValue(), n.span()));
                }
                case Param.Variadic v -> {
                    if (!f.isExtern) diagnostics.error(Code.BAD_MODIFIER, v.span(), "`...` is only allowed in extern functions");
                    f.isVariadic = true;
                }
            }
        }
        f.ret = f.ast.returnType() == null ? Type.UNIT : resolve(f.ast.returnType(), scope);
    }

    // ---- Implementations (§4.10) ----

    private void implItem(Module m, Item.Impl item) {
        var params = item.typeParams().stream().map(p -> new TypeParamInfo(p.name().text(), p.span())).toList();
        var scope = TypeResolver.Scope.of(m).with(params);
        bounds(params, item.typeParams(), scope);
        var trait = trait(item.trait(), scope);
        var target = resolve(item.target(), scope);
        if (trait == null || target == Type.ERROR) return;
        TypeInfo decl = switch (TypeSystem.expand(target)) {
            case Type.Nominal n -> n.decl();
            case Prim p -> ts.prims.get(p);
            default -> null;
        };
        if (decl != null && decl.isTrait()
                && TypeSystem.selfAndSupers((Type.Nominal) TypeSystem.expand(target)).stream().anyMatch(s -> s.decl() == trait.decl())) {
            diagnostics.error(Code.BAD_IMPL, item.target().span(),
                    "'" + target + "' already implements '" + trait.decl().name() + "' as a supertrait");
            return;
        }
        if (decl == null) {
            String why = target instanceof Type.Param ? "blanket impls over a type parameter are not permitted"
                    : "cannot implement a trait for '" + target + "'";
            diagnostics.error(Code.BAD_IMPL, item.target().span(), why);
            return;
        }
        var impl = new ImplInfo(trait, decl, m, item.span());
        impl.target = target;
        impl.typeParams = params;
        for (Member member : item.members()) {
            if (!(member instanceof Item.Fn f)) {
                diagnostics.error(Code.BAD_IMPL, member.span(), "an impl block contains only functions");
                continue;
            }
            var info = fn(f, m, impl);
            fnTypeParams(info);
            signature(info, scopeOf(info));
            impl.addMethod(info);
        }
        register(impl, item.trait().span());
    }

    private void register(ImplInfo impl, Span span) {
        var existing = ts.addImpl(impl);
        if (existing != null) {
            diagnostics.add(new Diagnostic(Code.DUPLICATE_IMPL, span,
                    "'" + impl.trait.decl().name() + "' is already implemented for '" + impl.targetDecl.name() + "'",
                    List.of(new Diagnostic.Note(existing.span, "previous implementation"))));
            return;
        }
        userImpls.add(impl);
    }

    /** Classes after their base classes, so base impls are known when checking subclasses. */
    private List<TypeInfo> inheritanceOrder() {
        var out = new ArrayList<TypeInfo>();
        var seen = new HashSet<TypeInfo>();
        for (TypeInfo t : allTypes) addInOrder(t, out, seen);
        return out;
    }

    private void addInOrder(TypeInfo t, List<TypeInfo> out, Set<TypeInfo> seen) {
        if (!seen.add(t)) return;
        if (t.base != null) addInOrder(t.base.decl(), out, seen);
        out.add(t);
    }

    /** {@code struct S : T} with methods in the body ≡ {@code impl T for S}; also for T's supertraits. */
    private void baseListImpls(TypeInfo t) {
        if (!(t.isStruct() || t.isClass())) return;
        var self = ts.selfType(t);
        for (var listed : t.traits) {
            if (t.base != null && ts.implementsAs(t.base, listed.decl()) != null) {
                diagnostics.error(Code.BAD_BASE, baseSpan(t, listed.decl()),
                        "'" + listed.decl().name() + "' is already implemented by the base class");
                continue;
            }
            for (var tr : TypeSystem.selfAndSupers(listed)) {
                boolean isListed = tr == listed;
                if (ts.impl(tr.decl(), t) != null) {
                    if (isListed) {
                        diagnostics.error(Code.DUPLICATE_IMPL, baseSpan(t, tr.decl()),
                                "'" + tr.decl().name() + "' is already implemented for '" + t.name() + "'");
                    }
                    continue;
                }
                if (!isListed && (t.traits.stream().anyMatch(x -> x.decl() == tr.decl())
                        || (t.base != null && ts.implementsAs(t.base, tr.decl()) != null))) continue;
                var impl = new ImplInfo(tr, t, t.module, baseSpan(t, listed.decl()));
                impl.target = self;
                impl.typeParams = t.typeParams;
                for (String member : tr.decl().methods.keySet()) {
                    for (FnInfo f : t.methods(member)) impl.addMethod(f);
                }
                register(impl, impl.span);
            }
        }
    }

    private Span baseSpan(TypeInfo t, TypeInfo trait) {
        List<TypeExpr.Named> list = switch (t.item) {
            case Item.Struct s -> s.bases();
            case Item.Class c -> c.bases();
            default -> List.of();
        };
        for (var te : list) if (te.path().getLast().text().equals(trait.name())) return te.span();
        return t.span();
    }

    /** The implementations primitives and enums have without an `impl` (§16, §4.11). */
    private void builtinImpls() {
        var common = List.of(ts.display, ts.eq);
        for (Prim p : Prim.values()) {
            var traits = new ArrayList<>(common);
            if (p != Prim.BOOL) traits.add(ts.ord);
            if (!p.isFloat()) traits.add(ts.hash);
            if (p.isNumeric()) {
                for (String op : List.of("Add", "Sub", "Mul", "Div", "Rem")) traits.add(ts.operatorTraits.get(op));
                traits.add(ts.num);
                if (p.isSigned()) {
                    traits.add(ts.operatorTraits.get("Neg"));
                    traits.add(ts.signed);
                }
            }
            if (p.isInteger()) {
                for (String op : List.of("BitAnd", "BitOr", "BitXor", "Not", "Shl", "Shr")) traits.add(ts.operatorTraits.get(op));
                traits.add(ts.integer);
            }
            if (p.isFloat()) traits.add(ts.floatTrait);
            if (p == Prim.STR) {
                traits.add(ts.len);
                traits.add(ts.truthy);
                traits.add(ts.operatorTraits.get("Add"));
            }
            builtinImpls(ts.prims.get(p), traits);
        }
        // `s[i]` (§7.6): the only generic trait a primitive implements without an `impl`.
        builtinImpl(ts.prims.get(Prim.STR), new Type.Nominal(ts.index, List.of(Prim.USIZE, Prim.CHAR)));
        for (TypeInfo t : allTypes) {
            if (!t.isEnum()) continue;
            var traits = new ArrayList<>(List.of(ts.display, ts.eq, ts.ord, ts.hash));
            if (t.isFlags) {
                for (String op : List.of("BitAnd", "BitOr", "BitXor", "Not")) traits.add(ts.operatorTraits.get(op));
            }
            builtinImpls(t, traits);
        }
    }

    private void builtinImpls(TypeInfo owner, List<TypeInfo> traits) {
        for (TypeInfo tr : traits) builtinImpl(owner, new Type.Nominal(tr, List.of()));
    }

    private void builtinImpl(TypeInfo owner, Type.Nominal trait) {
        var impl = new ImplInfo(trait, owner, prelude, null);
        impl.target = ts.selfType(owner);
        for (String member : trait.decl().methods.keySet()) for (FnInfo f : owner.methods(member)) impl.addMethod(f);
        ts.addImpl(impl);
    }

    // ---- Declaration checks ----

    /** An impl provides each required member of its trait with the exact signature (§4.9). */
    private void checkImpl(ImplInfo impl) {
        var trait = impl.trait.decl();
        var env = TypeSystem.bind(trait.typeParams, impl.trait.args());
        env.put(trait.selfParam, impl.target);
        var provided = new LinkedHashMap<String, List<FnInfo>>();
        for (var e : trait.methods.entrySet()) {
            String name = e.getKey();
            for (FnInfo required : e.getValue()) {
                FnInfo match = null;
                for (FnInfo candidate : impl.methods.getOrDefault(name, List.of())) {
                    if (sameSignature(required, env, candidate)) match = candidate;
                }
                if (match != null) {
                    provided.computeIfAbsent(name, k -> new ArrayList<>()).add(match);
                } else if (!impl.methods.getOrDefault(name, List.of()).isEmpty()) {
                    var c = impl.methods.get(name).getFirst();
                    diagnostics.error(Code.MISSING_TRAIT_MEMBER, c.ast.name().span(),
                            "'" + name + "' does not match the signature required by trait '" + trait.name() + "'");
                } else if (!required.hasBody()) {
                    diagnostics.error(Code.MISSING_TRAIT_MEMBER, implSpan(impl),
                            "'" + impl.targetDecl.name() + "' is missing '" + name + "' required by trait '" + trait.name() + "'");
                }
            }
        }
        if (impl.targetDecl.item instanceof Item.Impl || isImplBlock(impl)) {
            for (var e : impl.methods.entrySet()) {
                if (!trait.methods.containsKey(e.getKey())) {
                    diagnostics.error(Code.BAD_IMPL, e.getValue().getFirst().ast.name().span(),
                            "'" + e.getKey() + "' is not a member of trait '" + trait.name() + "'");
                }
            }
        }
        impl.methods.clear();
        impl.methods.putAll(provided);
        for (var s : TypeSystem.superTraits(impl.trait)) {
            if (ts.implementsAs(impl.target, s.decl()) == null) {
                diagnostics.error(Code.MISSING_TRAIT_MEMBER, implSpan(impl),
                        "'" + impl.targetDecl.name() + "' must implement '" + s.decl().name() + "', a supertrait of '" + trait.name() + "'");
            }
        }
    }

    private boolean isImplBlock(ImplInfo impl) {
        for (var fns : impl.methods.values()) for (FnInfo f : fns) if (f.owner == impl) return true;
        return false;
    }

    private static Span implSpan(ImplInfo impl) {
        return impl.span;
    }

    /** Exact signature equality after substituting the trait's parameters and Self (§4.9). */
    private boolean sameSignature(FnInfo required, Map<TypeParamInfo, Type> env, FnInfo candidate) {
        if (required.receiver != candidate.receiver || required.isGetter() != candidate.isGetter()) return false;
        if (required.params.size() != candidate.params.size()) return false;
        if (required.typeParams.size() != candidate.typeParams.size()) return false;
        var e = new HashMap<>(env);
        for (int i = 0; i < required.typeParams.size(); i++) {
            e.put(required.typeParams.get(i), candidate.typeParams.get(i).type());
        }
        for (int i = 0; i < required.params.size(); i++) {
            var rp = required.params.get(i);
            var cp = candidate.params.get(i);
            if (!TypeSystem.subst(rp.type(), e).equals(cp.type())) return false;
            if (rp.isOptional() != cp.isOptional()) return false;
            if (rp.isOptional() && !rp.name().equals(cp.name())) return false;
        }
        return TypeSystem.subst(required.ret, e).equals(candidate.ret);
    }

    private void checkType(TypeInfo t) {
        if (t.item == null) return;
        for (var fns : t.methods.values()) {
            for (FnInfo f : fns) checkModifiers(t, f);
        }
        if (t.isClass()) checkClass(t);
        if (t.isStruct() || t.isClass()) {
            for (FieldInfo f : t.fields) {
                if (t.isStruct() && f.visibility == Visibility.PROT) {
                    diagnostics.error(Code.BAD_MODIFIER, f.span(), "`prot` is only allowed in classes");
                }
                if (!t.methods(f.name()).isEmpty()) {
                    var m = t.methods(f.name()).getFirst();
                    diagnostics.error(Code.BAD_GETTER, m.ast.name().span(),
                            "'" + f.name() + "' is already a field of '" + t.name() + "'");
                }
            }
        }
    }

    private void checkModifiers(TypeInfo t, FnInfo f) {
        var ast = f.ast;
        if (t.isStruct() || t.isTrait() || t.isEnum()) {
            if (ast.dispatch() != Dispatch.NONE) {
                diagnostics.error(Code.BAD_MODIFIER, ast.span(), "`" + ast.dispatch().name().toLowerCase()
                        + "` is only allowed in classes");
            }
            if (ast.visibility() == Visibility.PROT) {
                diagnostics.error(Code.BAD_MODIFIER, ast.span(), "`prot` is only allowed in classes");
            }
        }
        if (t.isClass()) {
            if (ast.dispatch() == Dispatch.ABSTRACT && !t.isAbstract) {
                diagnostics.error(Code.BAD_MODIFIER, ast.name().span(), "abstract method '" + f.name()
                        + "' in non-abstract class '" + t.name() + "'");
            }
            if (ast.dispatch() == Dispatch.ABSTRACT && ast.body() != null) {
                diagnostics.error(Code.BAD_MODIFIER, ast.name().span(), "an abstract method has no body");
            }
            if (ast.dispatch() != Dispatch.NONE && f.isStatic()) {
                diagnostics.error(Code.BAD_MODIFIER, ast.name().span(), "a static function cannot be `"
                        + ast.dispatch().name().toLowerCase() + "`");
            }
        }
        boolean needsBody = !t.isTrait() && ast.dispatch() != Dispatch.ABSTRACT && f.intrinsic == null;
        if (needsBody && ast.body() == null) {
            diagnostics.error(Code.BAD_MODIFIER, ast.name().span(), "'" + f.name() + "' needs a body");
        }
        if (f.isGetter()) {
            if (f.receiver != FnInfo.Receiver.SELF || !f.params.isEmpty()) {
                diagnostics.error(Code.BAD_GETTER, ast.name().span(), "a getter takes only `self`");
            }
            if (f.ret == Type.UNIT) diagnostics.error(Code.BAD_GETTER, ast.name().span(), "a getter must return a value");
        }
    }

    /** Overrides, no hiding, abstract members (§4.6). */
    private void checkClass(TypeInfo t) {
        for (var fns : t.methods.values()) {
            for (FnInfo f : fns) {
                if (f.isStatic()) continue;
                FnInfo inherited = t.base == null ? null : findInherited(t.base, f);
                if (f.dispatch() == Dispatch.OVERRIDE) {
                    if (inherited == null) {
                        diagnostics.error(Code.BAD_OVERRIDE, f.ast.name().span(), "'" + f.name() + "' overrides nothing");
                    } else if (!inherited.isVirtual()) {
                        diagnostics.error(Code.BAD_OVERRIDE, f.ast.name().span(),
                                "cannot override non-virtual '" + inherited.qualifiedName() + "'");
                    } else if (!overrideMatches(t, inherited, f)) {
                        diagnostics.add(new Diagnostic(Code.BAD_OVERRIDE, f.ast.name().span(),
                                "'" + f.name() + "' does not match the signature of the method it overrides",
                                List.of(new Diagnostic.Note(inherited.ast.name().span(), "overridden method"))));
                    } else {
                        f.overrides = inherited;
                    }
                } else if (inherited != null) {
                    String why = inherited.isVirtual() ? "'" + f.name() + "' must be declared `override`"
                            : "'" + f.name() + "' hides an inherited method";
                    diagnostics.add(new Diagnostic(Code.HIDDEN_METHOD, f.ast.name().span(), why,
                            List.of(new Diagnostic.Note(inherited.ast.name().span(), "inherited from here"))));
                }
            }
        }
        if (!t.isAbstract) {
            for (FnInfo a : unimplementedAbstract(t)) {
                // Already reported: an abstract method of `t` itself, or a failed `override` of it.
                if (a.owner == t || failedOverride(t, a)) continue;
                diagnostics.add(new Diagnostic(Code.MISSING_ABSTRACT, ((Item.Class) t.item).name().span(),
                        "'" + t.name() + "' must override abstract '" + a.qualifiedName() + "'",
                        List.of(new Diagnostic.Note(a.ast.name().span(), "declared here"))));
            }
        }
    }

    /** The inherited instance method with the same name and parameter types, if any. */
    private FnInfo findInherited(Type.Nominal base, FnInfo f) {
        for (Type.Nominal cur = base; cur != null; ) {
            var env = TypeSystem.bind(cur.decl().typeParams, cur.args());
            for (FnInfo g : cur.decl().methods(f.name())) {
                if (g.isStatic() || g.params.size() != f.params.size()) continue;
                boolean same = true;
                for (int i = 0; i < f.params.size(); i++) {
                    if (!TypeSystem.subst(g.params.get(i).type(), env).equals(f.params.get(i).type())) same = false;
                }
                if (same) return g;
            }
            var b = cur.decl().base;
            cur = b == null ? null : TypeSystem.substNominal(b, env);
        }
        return null;
    }

    private boolean overrideMatches(TypeInfo t, FnInfo inherited, FnInfo f) {
        var owner = inherited.ownerType();
        var inst = TypeSystem.baseInstance((Type.Nominal) ts.selfType(t), owner);
        var env = inst == null ? new HashMap<TypeParamInfo, Type>() : TypeSystem.bind(owner.typeParams, inst.args());
        if (inherited.receiver != f.receiver || inherited.isGetter() != f.isGetter()) return false;
        if (inherited.typeParams.size() != f.typeParams.size()) return false;
        for (int i = 0; i < inherited.typeParams.size(); i++) env.put(inherited.typeParams.get(i), f.typeParams.get(i).type());
        for (int i = 0; i < f.params.size(); i++) {
            var a = inherited.params.get(i);
            var b = f.params.get(i);
            if (a.isOptional() != b.isOptional() || (a.isOptional() && !a.name().equals(b.name()))) return false;
        }
        var ret = TypeSystem.subst(inherited.ret, env);
        // Self in the base means the base type; the override must return the same type.
        return ret.equals(f.ret);
    }

    private static boolean failedOverride(TypeInfo t, FnInfo abstractFn) {
        for (TypeInfo c = t; c != null && c != abstractFn.owner; c = c.baseDecl()) {
            for (FnInfo g : c.methods(abstractFn.name())) {
                if (g.dispatch() == Dispatch.OVERRIDE && g.overrides == null) return true;
            }
        }
        return false;
    }

    private List<FnInfo> unimplementedAbstract(TypeInfo t) {
        var chain = new ArrayList<TypeInfo>();
        for (TypeInfo c = t; c != null; c = c.baseDecl()) chain.add(c);
        var out = new ArrayList<FnInfo>();
        for (TypeInfo c : chain) {
            for (var fns : c.methods.values()) {
                for (FnInfo f : fns) {
                    if (f.dispatch() != Dispatch.ABSTRACT) continue;
                    boolean overridden = false;
                    for (TypeInfo d : chain) {
                        if (d == c) break;
                        for (FnInfo g : d.methods(f.name())) if (g.overrides != null && g.root() == f.root()) overridden = true;
                    }
                    if (!overridden) out.add(f);
                }
            }
        }
        return out;
    }

    /** Overloads must differ in their required parameter types (§4.4). */
    private void checkOverloads() {
        var sets = new ArrayList<List<FnInfo>>();
        for (Module m : program.all()) {
            for (var decls : m.scope().items().values()) {
                var fns = new ArrayList<FnInfo>();
                for (var d : decls) if (names.infos.get(d.item()) instanceof FnInfo f) fns.add(f);
                if (fns.size() > 1) sets.add(fns);
            }
        }
        for (TypeInfo t : allTypes) for (var fns : t.methods.values()) if (fns.size() > 1) sets.add(fns);
        for (var fns : sets) {
            for (int i = 0; i < fns.size(); i++) {
                for (int j = 0; j < i; j++) {
                    var a = fns.get(j);
                    var b = fns.get(i);
                    if (a.isGetter() || b.isGetter()) {
                        diagnostics.error(Code.CONFLICTING_OVERLOADS, b.ast.name().span(),
                                "a getter cannot be overloaded: '" + b.name() + "' is declared twice");
                    } else if (a.isStatic() == b.isStatic() && sameRequired(a, b)) {
                        diagnostics.add(new Diagnostic(Code.CONFLICTING_OVERLOADS, b.ast.name().span(),
                                "overloads of '" + b.name() + "' must differ in their required parameter types",
                                List.of(new Diagnostic.Note(a.ast.name().span(), "conflicts with this declaration"))));
                    }
                }
            }
        }
    }

    private static boolean sameRequired(FnInfo a, FnInfo b) {
        var ra = a.params.stream().filter(p -> !p.isOptional()).toList();
        var rb = b.params.stream().filter(p -> !p.isOptional()).toList();
        if (ra.size() != rb.size() || a.typeParams.size() != b.typeParams.size()) return false;
        var env = new HashMap<TypeParamInfo, Type>();
        for (int i = 0; i < a.typeParams.size(); i++) env.put(b.typeParams.get(i), a.typeParams.get(i).type());
        for (int i = 0; i < ra.size(); i++) {
            if (!ra.get(i).type().equals(TypeSystem.subst(rb.get(i).type(), env))) return false;
        }
        return true;
    }

    /** Extern signatures and `@c struct` fields use ABI types only (§14.1–§14.3). */
    private void checkAbi() {
        for (FnInfo f : allFns) {
            if (!f.isExtern) continue;
            for (var p : f.params) {
                if (!TypeSystem.isAbi(p.type(), false)) notAbi(p.type(), p.span(), "an extern parameter");
                if (p.isOptional()) diagnostics.error(Code.BAD_MODIFIER, p.span(), "extern parameters cannot have defaults");
            }
            if (f.ret != Type.UNIT && !TypeSystem.isAbi(f.ret, false)) notAbi(f.ret, f.ast.returnType().span(), "an extern result");
        }
        for (TypeInfo t : allTypes) {
            if (!t.isC) continue;
            if (!t.typeParams.isEmpty()) {
                diagnostics.error(Code.NOT_ABI_TYPE, ((Item.Struct) t.item).name().span(), "an `@c struct` cannot be generic");
                continue;
            }
            for (FieldInfo f : t.fields) {
                if (!TypeSystem.isAbi(f.type, true)) notAbi(f.type, f.span(), "a field of an `@c struct`");
            }
        }
    }

    private void notAbi(Type t, Span span, String what) {
        String hint = t == Prim.STR ? "; pass a `Ptr<c::char>` from `to_cstr()`" : "";
        diagnostics.error(Code.NOT_ABI_TYPE, span, "'" + t + "' cannot be " + what + ": it is not a C ABI type" + hint);
    }

    /** §2.4: {@code fn main()} or {@code fn main(args: [str])}, returning () or () | errors. */
    private void checkMain() {
        var entry = program.entry();
        if (entry.ast() == null) return;
        var decls = entry.scope().items("main");
        var fns = decls.stream().map(d -> names.infos.get(d.item())).filter(FnInfo.class::isInstance).map(FnInfo.class::cast).toList();
        if (fns.isEmpty()) {
            var source = entry.source();
            diagnostics.error(Code.INVALID_MAIN, new Span(source, 0, Math.min(1, source.length())),
                    "the entry module must declare `fn main()`");
            return;
        }
        var main = fns.getFirst();
        Span at = main.ast.name().span();
        if (fns.size() > 1) {
            diagnostics.error(Code.INVALID_MAIN, fns.get(1).ast.name().span(), "`main` cannot be overloaded");
            return;
        }
        boolean paramsOk = main.typeParams.isEmpty() && (main.params.isEmpty()
                || (main.params.size() == 1 && main.params.getFirst().type().equals(ts.listOf(Prim.STR))
                && !main.params.getFirst().isOptional()));
        if (!paramsOk) {
            diagnostics.error(Code.INVALID_MAIN, at, "`main` takes no parameters or `args: [str]`");
        }
        boolean retOk = TypeSystem.members(main.ret).contains(Type.UNIT);
        for (Type m : TypeSystem.members(main.ret)) {
            if (m != Type.UNIT && m != Type.ERROR && ts.implementsAs(m, ts.error) == null) retOk = false;
        }
        if (!retOk) {
            diagnostics.error(Code.INVALID_MAIN, at, "`main` must return `()` or `() | E` where every `E` implements `Err`");
        }
    }
}
