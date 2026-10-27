package dev.threebolt.ast;

import dev.threebolt.diag.Span;
import java.util.List;

/** A module-level declaration (§2.1). */
public sealed interface Item extends Node {
    List<Attribute> attributes();

    Visibility visibility();

    /** {@code use a::b::C;}, {@code use a::b as c;}, {@code use a::b::{C, D as E};}, {@code use a::b::*;} (§2.2). */
    record Use(List<Attribute> attributes, Visibility visibility, List<Name> path, Name alias,
               List<UseName> group, boolean glob, Span span) implements Item {
        /** {@code group} is null unless the `{...}` form was used. */
        public boolean isGroup() {
            return group != null;
        }
    }

    record UseName(Name name, Name alias, Span span) implements Node {}

    /**
     * A function: module-level, member, trait requirement or extern. {@code body} is null for
     * `;` (abstract, required trait members, externs); {@code returnType} is null when omitted.
     */
    record Fn(List<Attribute> attributes, Visibility visibility, Dispatch dispatch, boolean isUnsafe,
              boolean isGetter, Name name, List<TypeParam> typeParams, List<Param> params,
              TypeExpr returnType, Body body, Span span) implements Item, Member {}

    /** {@code struct Done;} has no members. */
    record Struct(List<Attribute> attributes, Visibility visibility, Name name, List<TypeParam> typeParams,
                  List<TypeExpr.Named> bases, List<Member> members, Span span) implements Item {}

    record Class(List<Attribute> attributes, Visibility visibility, boolean isAbstract, Name name,
                 List<TypeParam> typeParams, List<TypeExpr.Named> bases, List<Member> members, Span span)
            implements Item {}

    record Trait(List<Attribute> attributes, Visibility visibility, Name name, List<TypeParam> typeParams,
                 List<TypeExpr.Named> supertraits, List<Member> members, Span span) implements Item {}

    record Impl(List<Attribute> attributes, Visibility visibility, List<TypeParam> typeParams,
                TypeExpr.Named trait, TypeExpr target, List<Member> members, Span span) implements Item {}

    /** {@code backing} is null when omitted (§4.11). */
    record Enum(List<Attribute> attributes, Visibility visibility, Name name, TypeExpr backing,
                List<EnumMember> members, Span span) implements Item {}

    /** {@code value} is null when omitted. */
    record EnumMember(Name name, Expr value, Span span) implements Node {}

    record TypeAlias(List<Attribute> attributes, Visibility visibility, Name name, List<TypeParam> typeParams,
                     TypeExpr type, Span span) implements Item {}

    record Const(List<Attribute> attributes, Visibility visibility, Name name, TypeExpr type, Expr value,
                 Span span) implements Item {}

    /** {@code static let [mut]}, at module level or in a type body (§4.2). */
    record Static(List<Attribute> attributes, Visibility visibility, boolean isMutable, Name name,
                  TypeExpr type, Expr value, Span span) implements Item, Member {}

    /** {@code extern "C" { fn ...; }} (§14.1); {@code abi} is the decoded string. */
    record Extern(List<Attribute> attributes, Visibility visibility, String abi, List<Fn> fns, Span span)
            implements Item {}
}
