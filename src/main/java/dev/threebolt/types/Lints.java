package dev.threebolt.types;

import dev.threebolt.ast.AstWalk;
import dev.threebolt.ast.Dispatch;
import dev.threebolt.ast.Expr;
import dev.threebolt.ast.Item;
import dev.threebolt.ast.Stmt;
import dev.threebolt.ast.Visibility;
import dev.threebolt.diag.Code;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Span;
import dev.threebolt.ir.Ir;
import dev.threebolt.ir.Local;
import dev.threebolt.resolve.Module;
import dev.threebolt.resolve.ModuleScope;
import dev.threebolt.resolve.Program;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Warnings (W0xxx): what the checker sees that is legal but most likely a mistake. Unused locals,
 * `mut`s, imports and private items are collected while checking and reported at the end;
 * unreachable code and statements without effect are reported where they are found. Std modules
 * are never warned about. A name starting with `_` is never unused.
 *
 * <p>Speculative checking (muted diagnostics) declares locals that do not survive, so only
 * locals declared unmuted are candidates; any use, muted or not, counts.
 */
final class Lints {
    private record Declared(Local local, Span name, boolean isParam) {}

    private final Checker c;
    private final Diagnostics diag;
    private final List<Declared> locals = new ArrayList<>();
    private final Set<Object> used = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Local> mutated = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ModuleScope.Import> usedImports = Collections.newSetFromMap(new IdentityHashMap<>());

    Lints(Checker c) {
        this.c = c;
        this.diag = c.diag;
    }

    private boolean active() {
        return c.ctx != null && !c.ctx.module.isStd() && !diag.isMuted();
    }

    // ---- Facts, recorded while checking ----

    /** A local was declared (a `let`, pattern binding or parameter); {@code name} is its name. */
    void declared(Local local, Span name) {
        if (active() && !local.name().startsWith("_")) locals.add(new Declared(local, name, false));
    }

    /**
     * The local just declared is a function's parameter; when the signature is {@code imposed}
     * (see {@link #signatureImposed}), it need not be used.
     */
    void param(Local local, boolean imposed) {
        if (locals.isEmpty() || locals.getLast().local() != local) return;
        var d = locals.removeLast();
        if (!imposed) locals.add(new Declared(local, d.name(), true));
    }

    /** {@code target} (a local or a declaration) was named somewhere other than its declaration. */
    void used(Object target) {
        used.add(target);
    }

    /** {@code place} was written, or a `mut self` method was called on it. */
    void mutated(Ir.Place place) {
        while (true) {
            switch (place) {
                case Ir.LocalPlace(Local l) -> {
                    mutated.add(l);
                    return;
                }
                case Ir.FieldPlace f when f.basePlace() != null -> place = f.basePlace();
                case Ir.IndexPlace i when i.basePlace() != null -> place = i.basePlace();
                default -> {
                    return;
                }
            }
        }
    }

    /** A name resolved through {@code i}, an import of the module being checked. */
    void usedImport(ModuleScope.Import i) {
        usedImports.add(i);
    }

    // ---- Warnings found in place ----

    /** {@code s} follows a statement that never completes, in a block whose statements end at {@code end}. */
    void unreachable(Stmt s, Span end) {
        if (active()) diag.warning(Code.UNREACHABLE, s.span().to(end), "unreachable code");
    }

    /** An expression statement: warns when evaluating it can do nothing. */
    void exprStmt(Expr e) {
        if (!active() || !noEffect(e)) return;
        if (e instanceof Expr.Binary b && b.op() == Expr.BinaryOp.EQ) {
            diag.warning(Code.NO_EFFECT, e.span(), "this comparison has no effect; to assign, write `=`");
        } else if (e instanceof Expr.Lambda) {
            diag.warning(Code.NO_EFFECT, e.span(), "this lambda is never called");
        } else {
            diag.warning(Code.NO_EFFECT, e.span(), "this expression has no effect");
        }
    }

    /**
     * Whether evaluating {@code e} can do nothing observable: no calls (a getter, an operator
     * method on a non-primitive, a cast that panics are all calls or close enough to keep quiet),
     * only reads of names and literals combined by operators.
     */
    private static boolean noEffect(Expr e) {
        boolean top = switch (e) {
            case Expr.Literal l -> true;
            case Expr.Unit u -> true;
            case Expr.Path p -> true;
            case Expr.Self s -> true;
            case Expr.Lambda l -> true;
            case Expr.Typeof t -> true;
            case Expr.Nameof n -> true;
            case Expr.Unary u -> true;
            case Expr.Is i -> true;
            case Expr.Binary b -> b.op() != Expr.BinaryOp.AND && b.op() != Expr.BinaryOp.OR
                    && b.op() != Expr.BinaryOp.COALESCE;
            default -> false;
        };
        if (!top || e instanceof Expr.Lambda) return top;
        boolean[] effect = {false};
        AstWalk.walk(e, n -> {
            if (n instanceof Expr.Call || n instanceof Expr.Member || n instanceof Expr.Index || n instanceof Expr.Slice
                    || n instanceof Expr.Propagate || n instanceof Expr.Match || n instanceof Expr.Init
                    || n instanceof Expr.Cast || n instanceof Expr.FString || n instanceof Expr.Lambda) {
                effect[0] = true;
            }
        });
        return !effect[0];
    }

    // ---- Warnings at the end ----

    void finish(Program program, Declarer decls) {
        for (var d : locals) {
            var l = d.local();
            if (!used.contains(l)) {
                diag.warning(Code.UNUSED_VARIABLE, d.name(), (d.isParam() ? "unused parameter '" : "unused variable '")
                        + l.name() + "'; if this is intentional, name it `_" + l.name() + "`");
            } else if (l.isMutable() && !mutated.contains(l)) {
                diag.warning(Code.UNUSED_MUT, d.name(), "'" + l.name() + "' is declared `mut` but never changed");
            }
        }
        for (Module m : program.all()) {
            if (!m.isStd() && m.ast() != null) imports(m);
        }
        unusedItems(program, decls);
    }

    /** An unused parameter is no mistake where the signature is not this function's own choice. */
    static boolean signatureImposed(FnInfo f) {
        if (f.ast.body() == null || f.isExtern || f.dispatch() != Dispatch.NONE) return true;
        if (f.owner instanceof ImplInfo) return true;
        return f.owner instanceof TypeInfo t && (t.isTrait() || implementedMember(t, f) != null);
    }

    /** The trait member that {@code f}, a method of {@code t}, implements, or null. */
    static FnInfo implementedMember(TypeInfo t, FnInfo f) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<TypeInfo, Boolean>());
        for (var tr : t.traits) {
            var m = traitMember(tr.decl(), f.name(), seen);
            if (m != null) return m;
        }
        return null;
    }

    private static FnInfo traitMember(TypeInfo trait, String name, Set<TypeInfo> seen) {
        if (!seen.add(trait)) return null;
        var own = trait.methods(name);
        if (!own.isEmpty()) return own.getFirst();
        for (var sup : trait.traits) {
            var m = traitMember(sup.decl(), name, seen);
            if (m != null) return m;
        }
        return null;
    }

    private void imports(Module m) {
        for (Item item : m.ast().items()) {
            // A re-export is used by other modules.
            if (!(item instanceof Item.Use u) || u.visibility() != dev.threebolt.ast.Visibility.PRIVATE) continue;
            if (u.glob()) {
                var span = Names.span(u.path());
                boolean any = false;
                boolean bound = false;
                for (var i : m.scope().imports().values()) {
                    if (!i.name().span().equals(span)) continue;
                    bound = true;
                    any |= usedImports.contains(i);
                }
                if (bound && !any) diag.warning(Code.UNUSED_IMPORT, u.span(), "unused import: nothing from '"
                        + String.join("::", u.path().stream().map(n -> n.text()).toList()) + "' is used");
            } else if (!u.isGroup()) {
                var name = u.alias() != null ? u.alias() : u.path().getLast();
                if (unused(m, name.span(), name.text())) {
                    diag.warning(Code.UNUSED_IMPORT, u.span(), "unused import '" + name.text() + "'");
                }
            } else {
                var unusedNames = new ArrayList<dev.threebolt.ast.Item.UseName>();
                for (var n : u.group()) {
                    var name = n.alias() != null ? n.alias() : n.name();
                    if (unused(m, name.span(), name.text())) unusedNames.add(n);
                }
                if (!unusedNames.isEmpty() && unusedNames.size() == u.group().size()) {
                    diag.warning(Code.UNUSED_IMPORT, u.span(), "unused imports: none of these names is used");
                } else {
                    for (var n : unusedNames) {
                        var name = n.alias() != null ? n.alias() : n.name();
                        diag.warning(Code.UNUSED_IMPORT, n.span(), "unused import '" + name.text() + "'");
                    }
                }
            }
        }
    }

    /** Whether the import bound by {@code name} at {@code span} is never used (false if it bound nothing). */
    private boolean unused(Module m, Span span, String name) {
        var i = m.scope().imports().get(name);
        return i != null && i.name().span().equals(span) && !usedImports.contains(i);
    }

    private void unusedItems(Program program, Declarer decls) {
        for (FnInfo f : decls.allFns) {
            if (f.module.isStd() || f.visibility() != Visibility.PRIVATE || used.contains(f)) continue;
            if (f.owner == null && f.name().equals("main") && f.module == program.entry()) continue;
            if (f.isExtern || f.owner instanceof ImplInfo || f.dispatch() != Dispatch.NONE) continue;
            if (f.owner instanceof TypeInfo t && (t.isTrait() || implementedMember(t, f) != null)) continue;
            String what = f.owner == null ? "function" : f.isGetter() ? "getter" : "method";
            diag.warning(Code.UNUSED_ITEM, f.ast.name().span(), what + " '" + f.qualifiedName() + "' is never used");
        }
        for (TypeInfo t : decls.allTypes) {
            if (t.item == null || t.module.isStd()) continue;
            if (t.visibility == Visibility.PRIVATE && !used.contains(t)) {
                diag.warning(Code.UNUSED_ITEM, SymbolIndex.definition(t), kind(t) + " '" + t.name() + "' is never used");
            }
            if (!t.isClass()) continue;
            for (FieldInfo f : t.fields) {
                if (f.visibility == Visibility.PRIVATE && !used.contains(f)) {
                    diag.warning(Code.UNUSED_ITEM, SymbolIndex.definition(f), "field '" + f + "' is never used");
                }
            }
        }
        for (StaticInfo s : decls.statics) {
            if (!s.module.isStd() && s.ast.visibility() == Visibility.PRIVATE && !used.contains(s)) {
                diag.warning(Code.UNUSED_ITEM, s.ast.name().span(), "static '" + s + "' is never used");
            }
        }
        for (ConstInfo k : decls.consts) {
            if (!k.module.isStd() && k.ast.visibility() == Visibility.PRIVATE && !used.contains(k)) {
                diag.warning(Code.UNUSED_ITEM, k.ast.name().span(), "constant '" + k.name() + "' is never used");
            }
        }
    }

    private static String kind(TypeInfo t) {
        return switch (t.kind()) {
            case STRUCT -> "struct";
            case CLASS -> "class";
            case TRAIT -> "trait";
            case ENUM -> "enum";
            case ALIAS -> "type alias";
            case PRIMITIVE -> "type";
        };
    }
}
