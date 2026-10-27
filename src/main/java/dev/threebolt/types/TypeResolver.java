package dev.threebolt.types;

import dev.threebolt.ast.Expr;
import dev.threebolt.ast.Name;
import dev.threebolt.ast.TypeExpr;
import dev.threebolt.diag.Code;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Span;
import dev.threebolt.resolve.Module;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Resolves type expressions (§3.2) to {@link Type}s. */
public final class TypeResolver {
    /** Where a type expression appears: its module, the type parameters in scope, and {@code Self}. */
    public record Scope(Module module, Map<String, TypeParamInfo> params, Type self) {
        public Scope with(List<TypeParamInfo> more) {
            if (more.isEmpty()) return this;
            var m = new HashMap<>(params);
            for (var p : more) m.put(p.name(), p);
            return new Scope(module, m, self);
        }

        public Scope withSelf(Type self) {
            return new Scope(module, params, self);
        }

        public static Scope of(Module module) {
            return new Scope(module, Map.of(), null);
        }
    }

    private final TypeSystem ts;
    private final Names names;
    private final Diagnostics diagnostics;
    /** Evaluates an array size (a `const` expression); set by the checker. */
    BiFunction<Expr, Scope, Long> constSize = (e, s) -> null;
    /** While declarations are being resolved, bound checks wait until impls are known. */
    private List<Runnable> deferredBounds = new ArrayList<>();
    /** Set by the checker when answering a completion request. */
    Completer completer;

    TypeResolver(TypeSystem ts, Names names, Diagnostics diagnostics) {
        this.ts = ts;
        this.names = names;
        this.diagnostics = diagnostics;
    }

    /** Runs the bound checks deferred so far; later checks run immediately. */
    void runDeferredBounds() {
        var pending = deferredBounds;
        deferredBounds = null;
        pending.forEach(Runnable::run);
    }

    public Type resolve(TypeExpr te, Scope scope) {
        return switch (te) {
            case TypeExpr.Named n -> named(n, scope);
            case TypeExpr.Literal l -> literal(l.value());
            case TypeExpr.Unit u -> Type.UNIT;
            case TypeExpr.Never n -> Type.NEVER;
            case TypeExpr.Tuple t -> new Type.Tuple(t.elements().stream().map(e -> resolve(e, scope)).toList());
            case TypeExpr.ListType l -> ts.listOf(resolve(l.element(), scope));
            case TypeExpr.ArrayType a -> {
                var element = resolve(a.element(), scope);
                Long size = constSize.apply(a.size(), scope);
                if (size == null) yield Type.ERROR;
                if (size < 0) {
                    diagnostics.error(Code.NOT_CONSTANT, a.size().span(), "array size must not be negative");
                    yield Type.ERROR;
                }
                yield new Type.Array(element, size.intValue());
            }
            case TypeExpr.FnType f -> {
                var params = f.params().stream().map(p -> resolve(p, scope)).toList();
                var ret = f.returnType() == null ? Type.UNIT : resolve(f.returnType(), scope);
                if (f.isExtern()) {
                    // §14.2: an `extern fn` type has an ABI signature.
                    for (int i = 0; i < params.size(); i++) {
                        if (!TypeSystem.isAbi(params.get(i), false)) notAbi(params.get(i), f.params().get(i).span());
                    }
                    if (ret != Type.UNIT && !TypeSystem.isAbi(ret, false)) notAbi(ret, f.returnType().span());
                }
                yield new Type.Fn(params, ret, f.isExtern());
            }
            case TypeExpr.Union u -> TypeSystem.union(u.members().stream().map(m -> resolve(m, scope)).toList());
        };
    }

    public static Type literal(Expr.Literal value) {
        return switch (value) {
            case Expr.IntLit i -> new Type.Lit(i.suffix() == null ? Prim.I32 : Prim.named(i.suffix()), i.value());
            case Expr.StrLit s -> new Type.Lit(Prim.STR, s.value());
            case Expr.BoolLit b -> new Type.Lit(Prim.BOOL, b.value());
            case Expr.CharLit c -> new Type.Lit(Prim.CHAR, c.codePoint());
            case Expr.NullLit n -> Type.NULL;
            case Expr.FloatLit f -> Prim.F64;
        };
    }

    /** Resolves a nominal type expression; also used for trait names in base lists and bounds. */
    public Type named(TypeExpr.Named n, Scope scope) {
        var path = n.path();
        String first = path.getFirst().text();
        if (completer != null && completer.at(path.getLast())) {
            if (path.size() == 1) {
                completer.types(scope, path.getFirst());
            } else {
                completer.typePosition = true;
                try {
                    names.resolve(scope.module(), path);
                } finally {
                    completer.typePosition = false;
                }
            }
            return Type.ERROR;
        }
        if (path.size() == 1) {
            var param = scope.params().get(first);
            if (param != null) {
                noArgs(n);
                names.symbolIndex.ref(path.getFirst().span(), param, null);
                return param.type();
            }
            if (first.equals("Self")) {
                if (scope.self() == null) {
                    diagnostics.error(Code.UNRESOLVED_NAME, n.span(), "`Self` is only valid inside a type or trait");
                    return Type.ERROR;
                }
                noArgs(n);
                return scope.self();
            }
        }
        var r = names.resolve(scope.module(), path);
        if (r == null) return Type.ERROR;
        if (r.consumed() < path.size()) {
            Name bad = path.get(r.consumed());
            diagnostics.error(Code.UNRESOLVED_NAME, bad.span(), "'" + bad.text() + "' is not a type");
            return Type.ERROR;
        }
        var args = n.typeArgs().stream().map(a -> resolve(a, scope)).toList();
        return switch (r.entity()) {
            case Names.PrimEntity(Prim p) -> {
                noArgs(n);
                yield p;
            }
            case Names.TypeEntity(TypeInfo t) -> instantiate(t, args, n.span());
            default -> {
                diagnostics.error(Code.WRONG_KIND, Names.span(path), "'" + pathText(path) + "' is not a type");
                yield Type.ERROR;
            }
        };
    }

    /** {@code decl<args>}, checking the argument count and (possibly deferred) bounds. */
    public Type instantiate(TypeInfo decl, List<Type> args, Span span) {
        if (args.size() != decl.typeParams.size()) {
            diagnostics.error(Code.WRONG_TYPE_ARGUMENTS, span, "'" + decl.name() + "' takes "
                    + decl.typeParams.size() + " type argument" + (decl.typeParams.size() == 1 ? "" : "s")
                    + ", got " + args.size());
            return Type.ERROR;
        }
        if (decl.kind() == TypeInfo.Kind.ALIAS) return expandAlias(decl, args, span);
        checkBounds(decl.typeParams, args, span);
        return new Type.Nominal(decl, args);
    }

    public void checkBounds(List<TypeParamInfo> params, List<Type> args, Span span) {
        Runnable check = () -> {
            for (int i = 0; i < params.size() && i < args.size(); i++) {
                var arg = args.get(i);
                if (TypeSystem.isGeneric(arg)) {
                    // A type parameter satisfies a bound it declares (or via a supertrait).
                    if (!(arg instanceof Type.Param)) continue;
                }
                for (var b : params.get(i).bounds) {
                    if (ts.implementsAs(arg, b.decl()) == null && arg != Type.ERROR) {
                        diagnostics.error(Code.UNSATISFIED_BOUND, span,
                                "'" + arg + "' does not implement '" + b + "' (required by '" + params.get(i).name() + "')");
                    }
                }
            }
        };
        if (deferredBounds != null) deferredBounds.add(check);
        else check.run();
    }

    private Type expandAlias(TypeInfo alias, List<Type> args, Span span) {
        if (alias.resolvingAlias) return new Type.AliasRef(alias, args);
        if (alias.aliasType == null) {
            alias.resolvingAlias = true;
            var params = new HashMap<String, TypeParamInfo>();
            for (var p : alias.typeParams) params.put(p.name(), p);
            alias.aliasType = resolve(alias.aliasExpr, new Scope(alias.module, params, null));
            alias.resolvingAlias = false;
            checkAliasCycle(alias, alias.aliasType, span);
        }
        return TypeSystem.subst(alias.aliasType, TypeSystem.bind(alias.typeParams, args));
    }

    /** A recursive alias must recurse through a nominal or collection type (§3.4). */
    private void checkAliasCycle(TypeInfo alias, Type t, Span span) {
        if (t instanceof Type.AliasRef r && r.alias() == alias) {
            diagnostics.error(Code.CYCLE, alias.span(), "type alias '" + alias.name() + "' refers to itself directly");
            alias.aliasType = Type.ERROR;
        } else if (t instanceof Type.Union u) {
            for (var m : u.members()) checkAliasCycle(alias, m, span);
        }
    }

    private void notAbi(Type t, Span span) {
        diagnostics.error(Code.NOT_ABI_TYPE, span, "'" + t + "' is not a C ABI type, so it cannot appear in an `extern fn` type");
    }

    private void noArgs(TypeExpr.Named n) {
        if (!n.typeArgs().isEmpty()) {
            diagnostics.error(Code.WRONG_TYPE_ARGUMENTS, n.span(), "'" + n.path().getLast().text() + "' takes no type arguments");
        }
    }

    public static String pathText(List<Name> path) {
        return String.join("::", path.stream().map(Name::text).toList());
    }
}
