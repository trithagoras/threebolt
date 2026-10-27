package dev.threebolt.types;

import dev.threebolt.ast.Dispatch;
import dev.threebolt.ast.Expr;
import dev.threebolt.ast.Item;
import dev.threebolt.ast.Visibility;
import dev.threebolt.diag.Span;
import dev.threebolt.ir.Ir;
import dev.threebolt.ir.Local;
import dev.threebolt.resolve.Module;
import java.util.ArrayList;
import java.util.List;

/**
 * A function: module-level, method, getter, static function, trait member, extern, or
 * intrinsic (a bodyless std function implemented by the interpreter).
 */
public final class FnInfo {
    public enum Receiver { NONE, SELF, MUT_SELF }

    public record ParamInfo(String name, Type type, boolean isMutable, Expr defaultValue, Span span) {
        public boolean isOptional() {
            return defaultValue != null;
        }
    }

    private final String name;
    public final Item.Fn ast;
    public final Module module;
    /** The declaring {@link TypeInfo} or {@link ImplInfo}, or null for a module-level function. */
    public final Object owner;
    public List<TypeParamInfo> typeParams = List.of();
    public Receiver receiver = Receiver.NONE;
    public final List<ParamInfo> params = new ArrayList<>();
    public Type ret = Type.UNIT;
    public boolean isVariadic;
    /** The method this `override` overrides. */
    public FnInfo overrides;
    /** For intrinsics: the native's key, e.g. {@code List.push} or {@code int.abs}. */
    public String intrinsic;
    /** For externs: the `@link` library, or null for the process/libc. */
    public String linkLibrary;
    public boolean isExtern;

    // Filled in by the checker.
    public Local selfLocal;
    public final List<Local> paramLocals = new ArrayList<>();
    /** Checked default values, by parameter index (null for required parameters). */
    public final List<Ir.Expr> defaults = new ArrayList<>();
    public Ir.Block body;

    public FnInfo(String name, Item.Fn ast, Module module, Object owner) {
        this.name = name;
        this.ast = ast;
        this.module = module;
        this.owner = owner;
    }

    public String name() {
        return name;
    }

    public Span span() {
        return ast.span();
    }

    public Visibility visibility() {
        return ast.visibility();
    }

    public Dispatch dispatch() {
        return ast.dispatch();
    }

    public boolean isGetter() {
        return ast.isGetter();
    }

    public boolean isUnsafe() {
        return ast.isUnsafe() || isExtern;
    }

    public boolean isStatic() {
        return receiver == Receiver.NONE;
    }

    /** Dispatched virtually through a class reference (§8.2). */
    public boolean isVirtual() {
        return dispatch() != Dispatch.NONE;
    }

    public boolean hasBody() {
        return ast.body() != null;
    }

    public int requiredCount() {
        int n = 0;
        for (ParamInfo p : params) if (!p.isOptional()) n++;
        return n;
    }

    /** The first declaration in this method's override chain. */
    public FnInfo root() {
        FnInfo f = this;
        while (f.overrides != null) f = f.overrides;
        return f;
    }

    public TypeInfo ownerType() {
        return owner instanceof TypeInfo t ? t : null;
    }

    public ImplInfo ownerImpl() {
        return owner instanceof ImplInfo i ? i : null;
    }

    /** {@code Type::name}, {@code name} for module-level functions. */
    public String qualifiedName() {
        return switch (owner) {
            case TypeInfo t -> t.name() + "::" + name;
            case ImplInfo i -> i.targetDecl.name() + "::" + name;
            case null, default -> name;
        };
    }

    @Override
    public String toString() {
        return qualifiedName();
    }
}
