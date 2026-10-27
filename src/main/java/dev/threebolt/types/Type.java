package dev.threebolt.types;

import java.math.BigInteger;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A semantic type (§3). Built by {@link TypeSystem}: unions are always normalized (flattened,
 * deduplicated, literals subsumed, §3.4) and aliases expanded, except for a recursive alias
 * reference, which stays an {@link AliasRef} until inspected.
 */
public sealed interface Type permits Prim, Type.Special, Type.Lit, Type.Tuple, Type.Nominal, Type.Array,
        Type.Fn, Type.Union, Type.Param, Type.AliasRef {

    /** {@code ()}, {@code null}, {@code !}, and the error type that silences follow-on errors. */
    enum Special implements Type {
        UNIT("()"), NULL("null"), NEVER("!"), ERROR("{error}");

        private final String text;

        Special(String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    Type UNIT = Special.UNIT;
    Type NULL = Special.NULL;
    Type NEVER = Special.NEVER;
    Type ERROR = Special.ERROR;

    /**
     * A literal type (§3.2): {@code value} is a {@link BigInteger} (integer), {@link String},
     * {@link Boolean} or {@link Integer} code point (char), of base type {@code base}.
     */
    record Lit(Prim base, Object value) implements Type {
        @Override
        public String toString() {
            return switch (value) {
                case String s -> '"' + s + '"';
                case Integer cp when base == Prim.CHAR -> "'" + Character.toString(cp) + "'";
                default -> value.toString();
            };
        }
    }

    /** Two or more elements, or one for {@code (T,)}. */
    record Tuple(List<Type> elements) implements Type {
        @Override
        public String toString() {
            if (elements.size() == 1) return "(" + elements.getFirst() + ",)";
            return elements.stream().map(Type::toString).collect(Collectors.joining(", ", "(", ")"));
        }
    }

    /** A struct, class, trait, enum or primitive-backed std type, with type arguments. */
    record Nominal(TypeInfo decl, List<Type> args) implements Type {
        public Nominal {
            args = List.copyOf(args);
        }

        @Override
        public String toString() {
            if (decl.isList() && args.size() == 1) return "[" + args.getFirst() + "]";
            if (args.isEmpty()) return decl.name();
            return decl.name() + args.stream().map(Type::toString).collect(Collectors.joining(", ", "<", ">"));
        }
    }

    /** {@code [T; N]}. */
    record Array(Type element, int size) implements Type {
        @Override
        public String toString() {
            return "[" + element + "; " + size + "]";
        }
    }

    /** {@code fn(A) -> R}, or {@code extern fn} (§14). */
    record Fn(List<Type> params, Type ret, boolean isExtern) implements Type {
        public Fn {
            params = List.copyOf(params);
        }

        @Override
        public String toString() {
            String r = ret == UNIT ? "" : " -> " + (ret instanceof Union ? "(" + ret + ")" : ret);
            return (isExtern ? "extern " : "") + params.stream().map(Type::toString)
                    .collect(Collectors.joining(", ", "fn(", ")")) + r;
        }
    }

    /** A normalized union of two or more members; construct with {@link TypeSystem#union}. */
    record Union(List<Type> members) implements Type {
        @Override
        public String toString() {
            return members.stream().map(m -> m instanceof Fn ? "(" + m + ")" : m.toString())
                    .collect(Collectors.joining(" | "));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Union u && u.members.size() == members.size() && u.members.containsAll(members);
        }

        @Override
        public int hashCode() {
            return members.stream().mapToInt(Object::hashCode).sum();
        }
    }

    /** A generic type parameter, or a trait's {@code Self}. */
    record Param(TypeParamInfo param) implements Type {
        @Override
        public String toString() {
            return param.name();
        }
    }

    /** A recursive reference to a type alias (§3.4), expanded on demand. */
    record AliasRef(TypeInfo alias, List<Type> args) implements Type {
        @Override
        public String toString() {
            if (args.isEmpty()) return alias.name();
            return alias.name() + args.stream().map(Type::toString).collect(Collectors.joining(", ", "<", ">"));
        }
    }
}
