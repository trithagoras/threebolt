package dev.threebolt.interp;

import dev.threebolt.ir.Ir;
import dev.threebolt.ir.Local;
import dev.threebolt.types.FnInfo;
import dev.threebolt.types.Prim;
import dev.threebolt.types.Type;
import dev.threebolt.types.TypeInfo;
import dev.threebolt.types.TypeParamInfo;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime values. Threebolt {@code null} is Java {@code null}, {@code bool} is {@link Boolean},
 * {@code str} is {@link Str}. Every value knows its runtime type (§3.4: union values carry it).
 */
public final class Values {
    private Values() {}

    /**
     * An integer of type {@code kind}. {@code u64}/{@code usize} hold the raw two's-complement
     * bits; every other kind holds the exact value.
     */
    public record Int(Prim kind, long v) {
        public BigInteger big() {
            if ((kind == Prim.U64 || kind == Prim.USIZE) && v < 0) {
                return BigInteger.valueOf(v).add(BigInteger.ONE.shiftLeft(64));
            }
            return BigInteger.valueOf(v);
        }

        public boolean isUnsigned64() {
            return kind == Prim.U64 || kind == Prim.USIZE;
        }

        @Override
        public String toString() {
            return isUnsigned64() ? Long.toUnsignedString(v) : Long.toString(v);
        }
    }

    /**
     * A {@code str} (§3.1): a sequence of characters (Unicode scalar values) with constant-time
     * length, indexing and slicing. Kept as UTF-16 plus its character count; a string with
     * characters outside the BMP also keeps the UTF-16 offset of each character.
     */
    public static final class Str {
        public static final Str EMPTY = new Str("", 0, null);

        private final String s;
        private final int len;
        /** Null when every character is one UTF-16 unit; else {@code len + 1} offsets, the last {@code s.length()}. */
        private final int[] offsets;

        private Str(String s, int len, int[] offsets) {
            this.s = s;
            this.len = len;
            this.offsets = offsets;
        }

        public static Str of(String s) {
            int n = s.length();
            int i = 0;
            while (i < n && !Character.isSurrogate(s.charAt(i))) i++;
            if (i == n) return n == 0 ? EMPTY : new Str(s, n, null);
            int len = s.codePointCount(0, n);
            var offsets = new int[len + 1];
            for (int k = 0, at = 0; k < len; k++) {
                offsets[k] = at;
                at += Character.charCount(s.codePointAt(at));
            }
            offsets[len] = n;
            return new Str(s, len, offsets);
        }

        /** The number of characters. */
        public int len() {
            return len;
        }

        public boolean isEmpty() {
            return len == 0;
        }

        /** The code point of character {@code i}; {@code 0 <= i < len}. */
        public int at(int i) {
            return offsets == null ? s.charAt(i) : s.codePointAt(offsets[i]);
        }

        /** Characters {@code [start, end)}; {@code 0 <= start <= end <= len}. */
        public Str slice(int start, int end) {
            if (start == 0 && end == len) return this;
            if (offsets == null) return start == end ? EMPTY : new Str(s.substring(start, end), end - start, null);
            return of(s.substring(offsets[start], offsets[end]));
        }

        public Str concat(Str o) {
            if (o.len == 0) return this;
            if (len == 0) return o;
            var joined = s.concat(o.s);
            return offsets == null && o.offsets == null ? new Str(joined, len + o.len, null) : of(joined);
        }

        /** Code-point order, which is also the byte order of UTF-8. */
        public int compareTo(Str o) {
            if (offsets == null && o.offsets == null) return s.compareTo(o.s);
            for (int i = 0; i < len && i < o.len; i++) {
                int x = at(i);
                int y = o.at(i);
                if (x != y) return Integer.compare(x, y);
            }
            return Integer.compare(len, o.len);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Str t && s.equals(t.s);
        }

        @Override
        public int hashCode() {
            return s.hashCode();
        }

        /** The UTF-16 text. */
        @Override
        public String toString() {
            return s;
        }
    }

    /** {@code f32} values are kept rounded to float precision. */
    public record Flt(Prim kind, double v) {}

    public record Char(int cp) {}

    public enum Unit { UNIT }

    public record EnumVal(TypeInfo decl, long v) {}

    /** A struct value or a class instance. Struct values are copied on read (§5.3). */
    public static final class Obj {
        public final TypeInfo decl;
        public final List<Type> args;
        public final Object[] fields;
        /** The Java state of a std class implemented by the interpreter (`Thread`, `Mutex`, `Atomic`). */
        public Object nat;

        public Obj(TypeInfo decl, List<Type> args, Object[] fields) {
            this.decl = decl;
            this.args = args;
            this.fields = fields;
        }

        public Obj copy() {
            var f = new Object[fields.length];
            for (int i = 0; i < f.length; i++) f[i] = Values.copy(fields[i]);
            return new Obj(decl, args, f);
        }
    }

    /** A struct boxed for a trait reference (§5.5); has reference identity. */
    public static final class Box {
        public final Obj value;

        public Box(Obj value) {
            this.value = value;
        }
    }

    /** A tuple; a value type, copied like a struct. */
    public static final class TupleVal {
        public final Object[] items;

        public TupleVal(Object[] items) {
            this.items = items;
        }
    }

    public static final class ListVal {
        public final Type element;
        public final ArrayList<Object> items;

        public ListVal(Type element, ArrayList<Object> items) {
            this.element = element;
            this.items = items;
        }
    }

    /** {@code [T; N]}; a value type. */
    public static final class ArrayVal {
        public final Type element;
        public final Object[] items;

        public ArrayVal(Type element, Object[] items) {
            this.element = element;
            this.items = items;
        }
    }

    /** Insertion-ordered; keys wrapped so that equality and hashing follow `Eq`/`Hash`. */
    public static final class MapVal {
        public final Type key;
        public final Type value;
        public final LinkedHashMap<Key, Object> entries = new LinkedHashMap<>();

        public MapVal(Type key, Type value) {
            this.key = key;
            this.value = value;
        }
    }

    public static final class SetVal {
        public final Type element;
        public final LinkedHashMap<Key, Object> entries = new LinkedHashMap<>();

        public SetVal(Type element) {
            this.element = element;
        }
    }

    /** A map/set key: equality and hash through `==` and `Hash` (which may run user code). */
    public static final class Key {
        public final Object value;
        private final Callbacks rt;
        private final int hash;

        Key(Object value, Callbacks rt) {
            this.value = value;
            this.rt = rt;
            this.hash = rt.hash(value);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && rt.equal(value, k.value);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    public record TypeVal(Type type) {}

    /** An iterator implemented by the interpreter; {@code next} returns {@link #DONE} at the end. */
    public static final class IterVal {
        public final Type element;
        public final java.util.function.Supplier<Object> next;

        public IterVal(Type element, java.util.function.Supplier<Object> next) {
            this.element = element;
            this.next = next;
        }
    }

    public record PtrVal(Type target, long addr) {}

    /**
     * A closure, a named function used as a value, or a C function pointer (an `extern fn` value
     * from C, §14.7: neither {@code lambda} nor {@code fn}, just {@code addr}).
     */
    public static final class FnVal {
        public final Type.Fn type;
        /** Set for lambdas. */
        public final Ir.Lambda lambda;
        public final Env env;
        public final Map<TypeParamInfo, Type> typeEnv;
        /** Set for named functions. */
        public final FnInfo fn;
        public final List<Type> typeArgs;
        public final long addr;

        public FnVal(Type.Fn type, Ir.Lambda lambda, Env env, Map<TypeParamInfo, Type> typeEnv, FnInfo fn,
                     List<Type> typeArgs) {
            this(type, lambda, env, typeEnv, fn, typeArgs, 0);
        }

        private FnVal(Type.Fn type, Ir.Lambda lambda, Env env, Map<TypeParamInfo, Type> typeEnv, FnInfo fn,
                      List<Type> typeArgs, long addr) {
            this.type = type;
            this.lambda = lambda;
            this.env = env;
            this.typeEnv = typeEnv;
            this.fn = fn;
            this.typeArgs = typeArgs;
            this.addr = addr;
        }

        public static FnVal pointer(Type.Fn type, long addr) {
            return new FnVal(type, null, null, Map.of(), null, List.of(), addr);
        }
    }

    /** A mutable variable; closures share cells (§7.8). */
    public static final class Cell {
        public Object value;

        public Cell(Object value) {
            this.value = value;
        }
    }

    /** The variables of one function invocation; lambdas chain to their defining env. */
    public static final class Env {
        private final Map<Local, Cell> cells = new java.util.HashMap<>();
        private final Env parent;

        public Env(Env parent) {
            this.parent = parent;
        }

        public void define(Local local, Object value) {
            cells.put(local, new Cell(value));
        }

        public Cell cell(Local local) {
            for (Env e = this; e != null; e = e.parent) {
                var c = e.cells.get(local);
                if (c != null) return c;
            }
            throw new IllegalStateException("unbound local " + local);
        }

        /**
         * The cells visible now, for a lambda's captures (§7.8). The cells are shared (capture is by
         * reference), but a later {@link #define} of the same local, i.e. a new binding such as the
         * next loop iteration's, does not affect the lambda.
         */
        public Env snapshot() {
            var s = new Env(null);
            for (Env e = this; e != null; e = e.parent) e.cells.forEach(s.cells::putIfAbsent);
            return s;
        }
    }

    /** The {@code Done} sentinel is created per program (it is a std struct); see {@link Interpreter#done}. */
    public static Object copy(Object v) {
        return switch (v) {
            case Obj o when o.decl.isStruct() -> o.copy();
            case TupleVal t -> {
                var items = new Object[t.items.length];
                for (int i = 0; i < items.length; i++) items[i] = copy(t.items[i]);
                yield new TupleVal(items);
            }
            case ArrayVal a -> {
                var items = new Object[a.items.length];
                for (int i = 0; i < items.length; i++) items[i] = copy(a.items[i]);
                yield new ArrayVal(a.element, items);
            }
            case null, default -> v;
        };
    }

    public static Int integer(Prim kind, long v) {
        return new Int(kind, v);
    }
}
