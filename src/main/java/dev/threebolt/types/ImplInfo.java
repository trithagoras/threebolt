package dev.threebolt.types;

import dev.threebolt.diag.Span;
import dev.threebolt.resolve.Module;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One implementation of a trait for a type constructor (§4.10): an `impl` block, a trait in a
 * struct/class base list, or a built-in implementation (primitives, enums, std types).
 */
public final class ImplInfo {
    /** The implemented trait, with type arguments over {@link #typeParams}. */
    public Type.Nominal trait;
    /** The implementing type, with type arguments over {@link #typeParams}. */
    public Type target;
    public final TypeInfo targetDecl;
    public List<TypeParamInfo> typeParams = new ArrayList<>();
    /** Implementations of the trait's members, by name. Missing names use the trait's default body. */
    public final Map<String, List<FnInfo>> methods = new LinkedHashMap<>();
    public final Module module;
    public final Span span;

    public ImplInfo(Type.Nominal trait, TypeInfo targetDecl, Module module, Span span) {
        this.trait = trait;
        this.targetDecl = targetDecl;
        this.module = module;
        this.span = span;
    }

    public void addMethod(FnInfo fn) {
        methods.computeIfAbsent(fn.name(), k -> new ArrayList<>()).add(fn);
    }

    @Override
    public String toString() {
        return "impl " + trait + " for " + target;
    }
}
