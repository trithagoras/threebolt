package dev.threebolt.types;

import dev.threebolt.ast.Item;
import dev.threebolt.ast.TypeExpr;
import dev.threebolt.ast.Visibility;
import dev.threebolt.diag.Span;
import dev.threebolt.resolve.Module;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A declared type: struct, class, trait, enum, alias, or the built-in member table of a
 * primitive. Filled in by {@link Declarer}; read by the checker and the interpreter.
 */
public final class TypeInfo {
    public enum Kind { STRUCT, CLASS, TRAIT, ENUM, ALIAS, PRIMITIVE }

    private final Kind kind;
    private final String name;
    /** Null for primitives. */
    public final Module module;
    /** Null for primitives. */
    public final Item item;
    public final Prim prim;
    public Visibility visibility = Visibility.PUB;

    public List<TypeParamInfo> typeParams = List.of();
    public boolean isAbstract;
    /** A class's base class, or null. */
    public Type.Nominal base;
    /** Traits named in the base list (structs, classes) or supertraits (traits). */
    public final List<Type.Nominal> traits = new ArrayList<>();
    /** Fields declared here, in order. */
    public final List<FieldInfo> fields = new ArrayList<>();
    /** Inherited fields first, then {@link #fields}; slot = index. */
    public List<FieldInfo> allFields = List.of();
    /** Methods, getters and static functions declared here (or intrinsic), by name. */
    public final Map<String, List<FnInfo>> methods = new LinkedHashMap<>();
    public final Map<String, StaticInfo> statics = new LinkedHashMap<>();

    // Enums (§4.11)
    public Prim backing = Prim.I32;
    public boolean isFlags;
    /** Member name to value (two's-complement bits of the backing type). */
    public final Map<String, Long> enumMembers = new LinkedHashMap<>();

    // Aliases (§4.12)
    public TypeExpr aliasExpr;
    public Type aliasType;
    public boolean resolvingAlias;

    // Traits: `Self` inside the trait, a parameter bounded by the trait itself.
    public TypeParamInfo selfParam;

    /** Whether this is a `@c struct` (§14.3). */
    public boolean isC;

    public TypeInfo(Kind kind, String name, Module module, Item item) {
        this.kind = kind;
        this.name = name;
        this.module = module;
        this.item = item;
        this.prim = null;
    }

    public TypeInfo(Prim prim) {
        this.kind = Kind.PRIMITIVE;
        this.name = prim.text();
        this.module = null;
        this.item = null;
        this.prim = prim;
    }

    public Kind kind() {
        return kind;
    }

    public String name() {
        return name;
    }

    public Span span() {
        return item == null ? null : item.span();
    }

    public boolean isStruct() {
        return kind == Kind.STRUCT;
    }

    public boolean isClass() {
        return kind == Kind.CLASS;
    }

    public boolean isTrait() {
        return kind == Kind.TRAIT;
    }

    public boolean isEnum() {
        return kind == Kind.ENUM;
    }

    /** A std type with this name, declared in std::prelude or std::collections. */
    public boolean isStd(String stdName) {
        return module != null && module.isStd() && name.equals(stdName);
    }

    public boolean isList() {
        return isStd("List");
    }

    /** Value semantics (§3.3). */
    public boolean isValueType() {
        return kind == Kind.STRUCT || kind == Kind.ENUM || (kind == Kind.PRIMITIVE && prim != Prim.STR);
    }

    public List<FnInfo> methods(String name) {
        return methods.getOrDefault(name, List.of());
    }

    public void addMethod(FnInfo fn) {
        methods.computeIfAbsent(fn.name(), k -> new ArrayList<>()).add(fn);
    }

    /** The base class's declaration, or null. */
    public TypeInfo baseDecl() {
        return base == null ? null : base.decl();
    }

    /** This class or one of its base classes is {@code other}. */
    public boolean isSubclassOf(TypeInfo other) {
        for (TypeInfo t = this; t != null; t = t.baseDecl()) if (t == other) return true;
        return false;
    }

    public FieldInfo field(String name) {
        for (FieldInfo f : allFields) if (f.name().equals(name)) return f;
        return null;
    }

    @Override
    public String toString() {
        return name;
    }
}
