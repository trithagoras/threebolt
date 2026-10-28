package dev.threebolt.types;

import dev.threebolt.ast.Item;
import dev.threebolt.ast.Name;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Source;
import dev.threebolt.diag.Span;
import dev.threebolt.ir.Local;
import dev.threebolt.resolve.Module;
import dev.threebolt.resolve.ModulePath;
import dev.threebolt.resolve.Program;
import dev.threebolt.resolve.Symbol;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What each name in the program refers to, for editor tooling (hover, go to definition,
 * references): the checker records every identifier it resolves. Targets are {@link Local},
 * {@link FnInfo}, {@link TypeInfo}, {@link FieldInfo}, {@link StaticInfo}, {@link ConstInfo},
 * {@link TypeParamInfo}, {@link EnumMember} or {@link Module}.
 *
 * <p>Speculative checking (overload arguments, deferred lambdas) may type a name more than once:
 * a record made with diagnostics muted only fills a gap, a later unmuted one replaces it.
 */
public final class SymbolIndex {
    /**
     * One occurrence of a name. {@code type} is its type at that point (narrowed, instantiated),
     * or null to use the target's declared type.
     */
    public record Ref(Span span, Object target, Type type, boolean isDecl) {}

    /** An enum member, which has no info object of its own. */
    public record EnumMember(TypeInfo owner, String name) {}

    private final boolean enabled;
    /** Told of every use of a name (also when this index is disabled). */
    Lints lints;
    /** Recording the paths in `use` items, which are not uses. */
    private boolean inUse;
    /** A completion or signature-help request to answer while checking, or null. */
    final Completion completion;
    private Diagnostics diag;
    private final Map<Source, TreeMap<Integer, Ref>> refs = new IdentityHashMap<>();
    private final Map<Ref, Boolean> speculative = new IdentityHashMap<>();
    /** Spans that name two different kinds of thing: shorthand `{ x }` (a field and a local). */
    private final Map<Source, java.util.Set<Integer>> shared = new IdentityHashMap<>();

    public SymbolIndex() {
        this(true, null);
    }

    /** An index that also answers {@code completion} while the program is checked. */
    public SymbolIndex(Completion completion) {
        this(true, completion);
    }

    private SymbolIndex(boolean enabled, Completion completion) {
        this.enabled = enabled;
        this.completion = completion;
    }

    /** An index that records nothing. */
    public static SymbolIndex disabled() {
        return new SymbolIndex(false, null);
    }

    /** Whether the checker is typing speculatively (see the class comment). */
    boolean isMuted() {
        return diag != null && diag.isMuted();
    }

    void attach(Diagnostics diag) {
        this.diag = diag;
    }

    void ref(Span span, Object target, Type type) {
        put(new Ref(span, target, type, false));
    }

    /** A declaration's own name. */
    void decl(Span span, Object target) {
        put(new Ref(span, target, null, true));
    }

    private void put(Ref r) {
        if (lints != null && !r.isDecl() && !inUse && r.target() != null) lints.used(r.target());
        if (!enabled || r.span() == null || r.target() == null) return;
        var bySpan = refs.computeIfAbsent(r.span().source(), k -> new TreeMap<>());
        boolean muted = isMuted();
        var old = bySpan.get(r.span().start());
        boolean oldSpeculative = old != null && speculative.containsKey(old);
        if (old != null && !muted && !oldSpeculative && old.target().getClass() != r.target().getClass()) {
            shared.computeIfAbsent(r.span().source(), k -> new java.util.HashSet<>()).add(r.span().start());
        }
        if (old != null && (old.isDecl() && !r.isDecl() || muted && !oldSpeculative)) return;
        bySpan.put(r.span().start(), r);
        if (old != null) speculative.remove(old);
        if (muted) speculative.put(r, true);
    }

    /** The name at {@code offset} (a code-point offset; the end of a name counts), or null. */
    public Ref at(Source source, int offset) {
        var bySpan = refs.get(source);
        if (bySpan == null) return null;
        var e = bySpan.floorEntry(offset);
        return e != null && offset <= e.getValue().span().end() ? e.getValue() : null;
    }

    /** Every occurrence of {@code target}, its declaration included, in no particular order. */
    public List<Ref> refsTo(Object target) {
        var out = new ArrayList<Ref>();
        for (var bySpan : refs.values()) {
            for (var r : bySpan.values()) if (same(r.target(), target)) out.add(r);
        }
        return out;
    }

    /** Every name that refers to something, each once. */
    public java.util.Collection<Object> targets() {
        var out = java.util.Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (var bySpan : refs.values()) for (var r : bySpan.values()) out.add(r.target());
        return out;
    }

    /** Whether {@code r}'s span also names something else, as `x` in shorthand `{ x }` does. */
    public boolean isShared(Ref r) {
        var starts = shared.get(r.span().source());
        return starts != null && starts.contains(r.span().start());
    }

    /** Every occurrence in one source, in order. */
    public List<Ref> refsIn(Source source) {
        var bySpan = refs.get(source);
        return bySpan == null ? List.of() : List.copyOf(bySpan.values());
    }

    private static boolean same(Object a, Object b) {
        return a == b || (a instanceof EnumMember && a.equals(b));
    }

    // ---- Declarations ----

    /** Records the names of every declaration the checker knows about, and the paths in `use` items. */
    void declarations(Program program, Declarer decls) {
        if (!enabled) return;
        inUse = true;
        for (Module m : program.all()) {
            if (m.ast() == null) continue;
            for (Item item : m.ast().items()) {
                if (item instanceof Item.Use u) use(program, decls.names, m, u);
            }
        }
        inUse = false;
        for (TypeInfo t : decls.allTypes) {
            if (t.item == null) continue;
            decl(itemName(t.item), t);
            t.typeParams.forEach(p -> decl(nameIn(p.span(), p.name()), p));
            t.fields.forEach(f -> decl(nameIn(f.span(), f.name()), f));
            t.statics.values().forEach(s -> decl(s.ast.name().span(), s));
            if (t.item instanceof Item.Enum e) e.members().forEach(m -> decl(m.name().span(), new EnumMember(t, m.name().text())));
        }
        for (FnInfo f : decls.allFns) {
            decl(f.ast.name().span(), f);
            f.typeParams.forEach(p -> decl(nameIn(p.span(), p.name()), p));
        }
        for (StaticInfo s : decls.statics) decl(s.ast.name().span(), s);
        for (ConstInfo c : decls.consts) decl(c.ast.name().span(), c);
    }

    private void use(Program program, Names names, Module m, Item.Use u) {
        var path = u.path();
        int prefix = u.isGroup() || u.glob() ? path.size() : path.size() - 1;
        for (int k = 1; k <= prefix; k++) {
            var mod = program.module(new ModulePath(path.subList(0, k).stream().map(Name::text).toList()));
            if (mod != null) ref(path.get(k - 1).span(), mod, null);
        }
        if (u.glob()) return;
        if (!u.isGroup()) {
            imported(names, m, path.getLast(), u.alias() != null ? u.alias() : path.getLast());
        } else {
            for (var n : u.group()) imported(names, m, n.name(), n.alias() != null ? n.alias() : n.name());
        }
    }

    private void imported(Names names, Module m, Name written, Name bound) {
        var i = m.scope().imports().get(bound.text());
        if (i == null || i.targets().isEmpty()) return;
        Object target = switch (i.targets().getFirst()) {
            case Symbol.ModuleRef(Module mod) -> mod;
            case Symbol.Decl d -> names.info(d.item());
        };
        ref(written.span(), target, null);
    }

    private static Span itemName(Item item) {
        return switch (item) {
            case Item.Struct s -> s.name().span();
            case Item.Class c -> c.name().span();
            case Item.Trait t -> t.name().span();
            case Item.Enum e -> e.name().span();
            case Item.TypeAlias a -> a.name().span();
            case Item.Fn f -> f.name().span();
            case Item.Const c -> c.name().span();
            case Item.Static s -> s.name().span();
            case Item.Use u -> null;
            case Item.Impl i -> null;
            case Item.Extern x -> null;
        };
    }

    /**
     * The first whole-word occurrence of {@code name} in {@code outer}, for declarations that keep
     * only their full span (fields, parameters, type parameters); {@code outer} if none.
     */
    static Span nameIn(Span outer, String name) {
        if (outer == null) return null;
        var text = outer.source().text();
        int from = text.offsetByCodePoints(0, outer.start());
        int to = text.offsetByCodePoints(from, outer.end() - outer.start());
        for (int i = text.indexOf(name, from); i >= 0 && i + name.length() <= to; i = text.indexOf(name, i + 1)) {
            boolean startOk = i == 0 || !isIdent(text.charAt(i - 1));
            boolean endOk = i + name.length() == text.length() || !isIdent(text.charAt(i + name.length()));
            if (startOk && endOk) {
                int start = outer.start() + text.codePointCount(from, i);
                return new Span(outer.source(), start, start + name.codePointCount(0, name.length()));
            }
        }
        return outer;
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Where {@code target} is declared: its name, or null (primitives, intrinsic owners). */
    public static Span definition(Object target) {
        return switch (target) {
            case Local l -> nameIn(l.span(), l.name());
            case FnInfo f -> f.ast.name().span();
            case TypeInfo t -> t.item == null ? null : itemName(t.item);
            case FieldInfo f -> nameIn(f.span(), f.name());
            case StaticInfo s -> s.ast.name().span();
            case ConstInfo c -> c.ast.name().span();
            case TypeParamInfo p -> nameIn(p.span(), p.name());
            case EnumMember m -> m.owner().item instanceof Item.Enum e ? e.members().stream()
                    .filter(x -> x.name().text().equals(m.name())).map(x -> x.name().span()).findFirst().orElse(null) : null;
            case Module m -> m.source().length() == 0 ? null : new Span(m.source(), 0, 0);
            default -> null;
        };
    }

    /**
     * The whole declaration of {@code target} (its doc comment, if any, sits right before it), or
     * null for what has no declaration of its own (locals, primitives, modules).
     */
    public static Span declaration(Object target) {
        return switch (target) {
            case FnInfo f -> f.span();
            case TypeInfo t -> t.item == null ? null : t.item.span();
            case FieldInfo f -> f.span();
            case StaticInfo s -> s.ast.span();
            case ConstInfo c -> c.ast.span();
            case EnumMember m -> m.owner().item instanceof Item.Enum e ? e.members().stream()
                    .filter(x -> x.name().text().equals(m.name())).map(Item.EnumMember::span).findFirst().orElse(null) : null;
            default -> null;
        };
    }

    /**
     * Where an undocumented method's documentation comes from: the method it overrides, or the
     * trait member it implements; null if neither.
     */
    public static FnInfo inheritsDocsFrom(FnInfo f) {
        if (f.overrides != null) return f.overrides;
        if (f.owner instanceof ImplInfo i) {
            var ms = i.trait.decl().methods(f.name());
            return ms.isEmpty() ? null : ms.getFirst();
        }
        return f.owner instanceof TypeInfo t ? Lints.implementedMember(t, f) : null;
    }

    // ---- Descriptions ----

    /** A one-line, source-like description of {@code r}'s target, for hovers. */
    public static String describe(Ref r) {
        return switch (r.target()) {
            case Local l -> "let " + (l.isMutable() ? "mut " : "") + l.name() + ": " + (r.type() != null ? r.type() : l.type);
            case FnInfo f -> signature(f);
            case TypeInfo t -> typeHeader(t);
            case FieldInfo f -> "let " + f.owner().name() + "." + f.name() + ": " + (r.type() != null ? r.type() : f.type);
            case StaticInfo s -> "static let " + (s.isMutable() ? "mut " : "") + qualified(s.owner, s.name()) + ": " + s.type;
            case ConstInfo c -> "const " + c.name() + ": " + c.type;
            case TypeParamInfo p -> p.name() + (p.bounds.isEmpty() ? ""
                    : ": " + String.join(" + ", p.bounds.stream().map(Type::toString).toList()));
            case EnumMember m -> m.owner().name() + "::" + m.name();
            case Module m -> "mod " + m.path();
            default -> r.target().toString();
        };
    }

    private static String qualified(TypeInfo owner, String name) {
        return owner == null ? name : owner.name() + "::" + name;
    }

    /** {@code fn Owner::name<T>(self, a: A, b: B = ...) -> R}. */
    public static String signature(FnInfo f) {
        return signature(f, Map.of(), null, false);
    }

    /**
     * {@link #signature(FnInfo)} with the owner's type parameters substituted from {@code env}.
     * When {@code params} is non-null, adds each parameter's [start, end) in the result to it; the
     * receiver counts as one only when {@code selfIsParam} (a UFCS call, `Trait::m(x)`).
     */
    static String signature(FnInfo f, Map<TypeParamInfo, Type> env, List<int[]> params, boolean selfIsParam) {
        var sb = new StringBuilder();
        if (f.isExtern) sb.append("extern ");
        if (f.isUnsafe() && !f.isExtern) sb.append("unsafe ");
        sb.append(f.isGetter() ? "get fn " : "fn ").append(f.qualifiedName());
        if (!f.typeParams.isEmpty()) sb.append(params(f.typeParams));
        var parts = new ArrayList<String>();
        if (f.receiver == FnInfo.Receiver.SELF) parts.add("self");
        if (f.receiver == FnInfo.Receiver.MUT_SELF) parts.add("mut self");
        boolean receiver = !parts.isEmpty();
        for (var p : f.params) {
            parts.add((p.isMutable() ? "mut " : "") + p.name() + ": " + TypeSystem.subst(p.type(), env)
                    + (p.isOptional() ? " = ..." : ""));
        }
        if (f.isVariadic) parts.add("...");
        sb.append('(');
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(", ");
            boolean isParam = (i > 0 || !receiver || selfIsParam) && !(f.isVariadic && i == parts.size() - 1);
            if (params != null && isParam) params.add(new int[] {sb.length(), sb.length() + parts.get(i).length()});
            sb.append(parts.get(i));
        }
        sb.append(')');
        var ret = TypeSystem.subst(f.ret, env);
        if (ret != Type.UNIT) sb.append(" -> ").append(ret);
        return sb.toString();
    }

    private static String typeHeader(TypeInfo t) {
        String kw = switch (t.kind()) {
            case STRUCT -> "struct";
            case CLASS -> t.isAbstract ? "abstract class" : "class";
            case TRAIT -> "trait";
            case ENUM -> t.isFlags ? "@flags enum" : "enum";
            case ALIAS -> "type";
            case PRIMITIVE -> "primitive";
        };
        var sb = new StringBuilder(kw).append(' ').append(t.name());
        if (!t.typeParams.isEmpty()) sb.append(params(t.typeParams));
        if (t.kind() == TypeInfo.Kind.ALIAS && t.aliasType != null) sb.append(" = ").append(t.aliasType);
        if (t.kind() == TypeInfo.Kind.ENUM) sb.append(" : ").append(t.backing.text());
        var bases = new ArrayList<String>();
        if (t.base != null) bases.add(t.base.toString());
        t.traits.forEach(tr -> bases.add(tr.toString()));
        if (!bases.isEmpty()) sb.append(" : ").append(String.join(", ", bases));
        return sb.toString();
    }

    private static String params(List<TypeParamInfo> ps) {
        var parts = new ArrayList<String>();
        for (var p : ps) {
            parts.add(p.name() + (p.bounds.isEmpty() ? "" : ": " + String.join(" + ", p.bounds.stream().map(Type::toString).toList())));
        }
        return "<" + String.join(", ", parts) + ">";
    }
}
