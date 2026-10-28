package dev.threebolt.interp;

import dev.threebolt.interp.Values.ArrayVal;
import dev.threebolt.interp.Values.Char;
import dev.threebolt.interp.Values.EnumVal;
import dev.threebolt.interp.Values.Flt;
import dev.threebolt.interp.Values.Int;
import dev.threebolt.interp.Values.IterVal;
import dev.threebolt.interp.Values.Key;
import dev.threebolt.interp.Values.ListVal;
import dev.threebolt.interp.Values.MapVal;
import dev.threebolt.interp.Values.Obj;
import dev.threebolt.interp.Values.SetVal;
import dev.threebolt.interp.Values.Str;
import dev.threebolt.interp.Values.TupleVal;
import dev.threebolt.interp.Values.TypeVal;
import dev.threebolt.interp.Values.Unit;
import dev.threebolt.types.FnInfo;
import dev.threebolt.types.Prim;
import dev.threebolt.types.Type;
import dev.threebolt.types.TypeInfo;
import dev.threebolt.types.TypeParamInfo;
import dev.threebolt.types.TypeSystem;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** The intrinsics: bodyless std functions (prelude, collections, primitives). */
final class Natives {
    private final Callbacks rt;
    private final Ffi ffi;
    private final Threads threads;
    private final Io io;

    Natives(Callbacks rt, Ffi ffi, Threads threads, Io io) {
        this.rt = rt;
        this.ffi = ffi;
        this.threads = threads;
        this.io = io;
    }

    Object call(FnInfo fn, Object self, Object[] a, Map<TypeParamInfo, Type> typeEnv) {
        String key = fn.intrinsic;
        int dot = key.indexOf('.');
        String owner = key.substring(0, dot);
        String name = key.substring(dot + 1);
        final Object recv = Interpreter.unbox(self);
        return switch (owner) {
            case "prelude" -> prelude(name, a);
            case "process" -> {
                rt.out().flush();
                throw new Callbacks.ExitSignal((int) ((Int) a[0]).v());
            }
            case "c", "Ptr" -> ffi.intrinsic(owner, name, recv, a, typeEnv, fn);
            case "cstruct" -> ffi.withPtr(recv, (Values.FnVal) a[0]);
            case "thread", "Thread", "Atomic", "RawLock", "sync" -> threads.call(owner, name, recv, a, typeEnv, fn);
            case "Type" -> type(name, (TypeVal) recv, a);
            case "math" -> math(name, a);
            case "io" -> {
                rt.checkAbort();
                yield io.call(name, a);
            }
            case "random" -> new Int(Prim.U64, ENTROPY.nextLong());
            // Collections lock themselves per operation (§13.4), but not around a callback.
            case "List" -> name.equals("with_ptr") ? ffi.withPtr(recv, (Values.FnVal) a[0])
                    : name.equals("join") ? join(recv, a[0].toString())
                    : locked(recv, () -> recv instanceof ArrayVal arr ? array(name, arr, a) : list(name, (ListVal) recv, a));
            case "Map" -> locked(recv, () -> map(name, (MapVal) recv, a));
            case "Set" -> locked(recv, () -> set(name, (SetVal) recv, a));
            case "Range" -> range(name, (Obj) recv, a);
            case "NativeIter" -> ((IterVal) recv).next.get();
            case "int" -> integer(name, recv, a, fn);
            case "float" -> floating(name, recv, a, fn);
            case "str" -> name.equals("from_cstr") || name.equals("to_cstr") ? ffi.intrinsic("str", name, recv, a, typeEnv, fn)
                    : name.equals("from_chars") ? fromChars(a[0], fn)
                    : name.equals("from_utf8") ? fromUtf8((ListVal) a[0]) : str(name, (Str) recv, a);
            case "char" -> character(name, (Char) recv, a);
            case "bool" -> switch (name) {
                case "hash" -> new Int(Prim.U64, Ops.mix(((Boolean) recv) ? 1 : 0));
                default -> unknown(key);
            };
            case "enum", "flags" -> enumeration(name, (EnumVal) recv, a);
            default -> unknown(key);
        };
    }

    /** {@code str::from_chars(cs)}, for either overload: an {@code Iterable<char>} or an {@code Iterator<char>}. */
    private Str fromChars(Object cs, FnInfo fn) {
        boolean iterator = fn.params.getFirst().type() instanceof Type.Nominal n && n.decl() == rt.ts().iterator;
        var it = iterator ? cs : rt.callTrait(cs, rt.ts().iterable, "iter");
        var out = new StringBuilder();
        for (Object c; !isDone(c = rt.next(it)); ) out.appendCodePoint(((Char) c).cp());
        return Str.of(out.toString());
    }

    /** {@code str::from_utf8(bytes)}: invalid sequences become U+FFFD. */
    private static Str fromUtf8(ListVal bytes) {
        byte[] b;
        synchronized (bytes) {
            b = new byte[bytes.items.size()];
            for (int i = 0; i < b.length; i++) b[i] = (byte) ((Int) bytes.items.get(i)).v();
        }
        return Str.of(new String(b, StandardCharsets.UTF_8));
    }

    /** {@code xs.join(sep)}: `to_str` runs user code, so it is called outside the list's lock. */
    private Str join(Object recv, String sep) {
        List<Object> items;
        synchronized (recv) {
            items = recv instanceof ArrayVal arr ? java.util.Arrays.asList(arr.items) : new ArrayList<>(((ListVal) recv).items);
        }
        var out = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) out.append(sep);
            out.append(rt.display(items.get(i)));
        }
        return Str.of(out.toString());
    }

    private boolean isDone(Object v) {
        return Interpreter.unbox(v) instanceof Obj o && o.decl == rt.ts().done;
    }

    // ---- Indexing lists, arrays and maps (§16); shared with the evaluator's `xs[i]` ----

    /** Lists, maps and sets lock themselves for each operation, so racing threads cannot break them. */
    static Object get(ListVal l, Object index) {
        synchronized (l) {
            return Values.copy(l.items.get(checkIndex(index, l.items.size())));
        }
    }

    static Object get(ArrayVal a, Object index) {
        return Values.copy(a.items[checkIndex(index, a.items.length)]);
    }

    static Object get(Callbacks rt, MapVal m, Object index) {
        var key = new Key(index, rt);
        synchronized (m) {
            if (!m.entries.containsKey(key)) throw new Panic("key not found in map: " + rt.debug(index));
            return Values.copy(m.entries.get(key));
        }
    }

    /** {@code s[i]}: the {@code i}th character (§3.1). */
    static Char get(Str s, Object index) {
        long i = ((Int) index).v();
        // usize values above i64::MAX are negative here; they are out of range anyway.
        if (i < 0 || i >= s.len()) throw new Panic("index " + Long.toUnsignedString(i) + " out of range for a string of length " + s.len());
        return new Char(s.at((int) i));
    }

    static void set(ListVal l, Object index, Object value) {
        synchronized (l) {
            l.items.set(checkIndex(index, l.items.size()), value);
        }
    }

    static void set(ArrayVal a, Object index, Object value) {
        a.items[checkIndex(index, a.items.length)] = value;
    }

    static void set(Callbacks rt, MapVal m, Object index, Object value) {
        var key = new Key(index, rt);
        synchronized (m) {
            m.entries.put(key, value);
        }
    }

    static int checkIndex(Object index, int size) {
        var i = (Int) index;
        if (i.v() < 0 || i.v() >= size) throw new Panic("index out of range: " + i + " >= " + size);
        return (int) i.v();
    }

    private static Object locked(Object collection, Supplier<Object> op) {
        synchronized (collection) {
            return op.get();
        }
    }

    private static Object unknown(String key) {
        throw new IllegalStateException("unknown intrinsic " + key);
    }

    private static Int usize(long v) {
        return new Int(Prim.USIZE, v);
    }

    private Object prelude(String name, Object[] a) {
        rt.checkAbort();
        if (name.equals("panic")) throw new Panic(a[0].toString());
        if (name.equals("equal")) return rt.equal(a[0], a[1]);
        return unknown("prelude." + name);
    }

    private Object type(String name, TypeVal t, Object[] a) {
        return switch (name) {
            case "name", "to_str" -> Str.of(TypeSystem.name(t.type()));
            case "eq" -> t.equals(a[0]);
            case "hash" -> new Int(Prim.U64, Ops.mix(t.type().hashCode()));
            case "is_subtype_of" -> rt.ts().assignable(t.type(), ((TypeVal) a[0]).type());
            default -> unknown("Type." + name);
        };
    }

    // ---- Iterators ----

    /** An iterator over a Java iterator; elements are copied (value semantics). */
    private IterVal iter(Type element, Iterator<Object> it) {
        return new IterVal(element, () -> it.hasNext() ? Values.copy(it.next()) : rt.done());
    }

    /** Iterates a live list by index: elements pushed during the loop are visited. */
    private IterVal listIter(ListVal l) {
        int[] i = {0};
        return new IterVal(l.element, () -> {
            synchronized (l) {
                return i[0] < l.items.size() ? Values.copy(l.items.get(i[0]++)) : rt.done();
            }
        });
    }

    private IterVal generate(Type element, Supplier<Object> next) {
        return new IterVal(element, next);
    }

    // ---- Collections (§16) ----

    private Object list(String name, ListVal l, Object[] a) {
        var items = l.items;
        return switch (name) {
            case "len" -> usize(items.size());
            case "iter" -> listIter(l);
            case "index" -> get(l, a[0]);
            case "set" -> {
                set(l, a[0], a[1]);
                yield Unit.UNIT;
            }
            case "push" -> {
                items.add(a[0]);
                yield Unit.UNIT;
            }
            case "pop" -> items.isEmpty() ? null : items.removeLast();
            case "insert" -> {
                long i = ((Int) a[0]).v();
                if (i < 0 || i > items.size()) throw new Panic("insert index out of range: " + i + " > " + items.size());
                items.add((int) i, a[1]);
                yield Unit.UNIT;
            }
            case "remove" -> items.remove(checkIndex(a[0], items.size()));
            case "clear" -> {
                items.clear();
                yield Unit.UNIT;
            }
            default -> unknown("List." + name);
        };
    }

    private Object array(String name, ArrayVal arr, Object[] a) {
        var items = arr.items;
        return switch (name) {
            case "len" -> usize(items.length);
            case "iter" -> iter(arr.element, java.util.Arrays.asList(items).iterator());
            case "index" -> get(arr, a[0]);
            case "set" -> {
                set(arr, a[0], a[1]);
                yield Unit.UNIT;
            }
            default -> unknown("array." + name);
        };
    }

    private Object map(String name, MapVal m, Object[] a) {
        var entries = m.entries;
        return switch (name) {
            case "len" -> usize(entries.size());
            case "iter" -> {
                var it = new ArrayList<>(entries.entrySet()).iterator();
                yield generate(new Type.Tuple(List.of(m.key, m.value)), () -> {
                    if (!it.hasNext()) return rt.done();
                    var e = it.next();
                    return new TupleVal(new Object[] {Values.copy(e.getKey().value), Values.copy(e.getValue())});
                });
            }
            case "index" -> get(rt, m, a[0]);
            case "set" -> {
                entries.put(new Key(a[0], rt), a[1]);
                yield Unit.UNIT;
            }
            case "get" -> Values.copy(entries.get(new Key(a[0], rt)));
            case "contains_key" -> entries.containsKey(new Key(a[0], rt));
            case "remove" -> entries.remove(new Key(a[0], rt));
            case "clear" -> {
                entries.clear();
                yield Unit.UNIT;
            }
            case "keys" -> iter(m.key, new ArrayList<Object>(entries.keySet().stream().map(k -> k.value).toList()).iterator());
            case "values" -> iter(m.value, new ArrayList<>(entries.values()).iterator());
            default -> unknown("Map." + name);
        };
    }

    private Object set(String name, SetVal s, Object[] a) {
        var entries = s.entries;
        return switch (name) {
            case "len" -> usize(entries.size());
            case "iter" -> iter(s.element, new ArrayList<>(entries.values()).iterator());
            case "insert" -> entries.putIfAbsent(new Key(a[0], rt), a[0]) == null;
            case "contains" -> entries.containsKey(new Key(a[0], rt));
            case "remove" -> entries.remove(new Key(a[0], rt)) != null;
            case "clear" -> {
                entries.clear();
                yield Unit.UNIT;
            }
            default -> unknown("Set." + name);
        };
    }

    /** Range fields: start, end, inclusive. */
    private Object range(String name, Obj r, Object[] a) {
        Object start = r.fields[0];
        Object end = r.fields[1];
        boolean inclusive = (Boolean) r.fields[2];
        return switch (name) {
            case "contains" -> {
                int lo = Ops.comparePrim(a[0], start);
                int hi = Ops.comparePrim(a[0], end);
                yield lo >= 0 && (inclusive ? hi <= 0 : hi < 0);
            }
            case "iter" -> {
                if (start instanceof Char c) {
                    int[] cur = {c.cp()};
                    int last = ((Char) end).cp() - (inclusive ? 0 : 1);
                    yield generate(Prim.CHAR, () -> {
                        while (cur[0] >= 0xD800 && cur[0] <= 0xDFFF) cur[0]++;
                        return cur[0] <= last ? new Char(cur[0]++) : rt.done();
                    });
                }
                var s = (Int) start;
                var e = (Int) end;
                var kind = s.kind();
                BigInteger[] cur = {s.big()};
                BigInteger limit = inclusive ? e.big() : e.big().subtract(BigInteger.ONE);
                yield generate(kind, () -> {
                    if (cur[0].compareTo(limit) > 0) return rt.done();
                    var v = Ops.wrapBig(kind, cur[0]);
                    cur[0] = cur[0].add(BigInteger.ONE);
                    return v;
                });
            }
            default -> unknown("Range." + name);
        };
    }

    // ---- Primitives ----

    private static Prim ownerPrim(FnInfo fn) {
        return ((TypeInfo) fn.owner).prim;
    }

    private Object integer(String name, Object self, Object[] a, FnInfo fn) {
        switch (name) {
            case "from_i64" -> { return fromI64(ownerPrim(fn), ((Int) a[0]).v()); }
            case "bits" -> { return new Int(Prim.U32, ownerPrim(fn).bits()); }
            default -> {}
        }
        var x = (Int) self;
        var k = x.kind();
        return switch (name) {
            case "to_str" -> Str.of(x.toString());
            case "hash" -> new Int(Prim.U64, Ops.mix(x.v()));
            default -> family(name, x, a, k);
        };
    }

    /** {@code Num::from_i64}: the value {@code n} as a {@code k}; panics if it does not fit. */
    private static Int fromI64(Prim k, long n) {
        var r = Ops.exact(k, BigInteger.valueOf(n));
        if (r == null) throw new Panic(n + " does not fit in '" + k + "'");
        return r;
    }

    /** {@code wrapping_*}, {@code checked_*}, {@code saturating_*} (§13.2). */
    private static Object family(String name, Int x, Object[] a, Prim k) {
        int us = name.indexOf('_');
        String mode = name.substring(0, us);
        String op = name.substring(us + 1);
        if (op.equals("shl") || op.equals("shr")) {
            long n = ((Int) a[0]).v();
            boolean inRange = n >= 0 && n < k.bits();
            return switch (mode) {
                case "wrapping" -> {
                    var amount = new Int(Prim.U32, n & (k.bits() - 1));
                    yield op.equals("shl") ? Ops.shl(x, amount) : Ops.shr(x, amount);
                }
                case "checked" -> inRange ? (op.equals("shl") ? Ops.shl(x, (Int) a[0]) : Ops.shr(x, (Int) a[0])) : null;
                default -> {
                    if (inRange) yield op.equals("shl") ? Ops.shl(x, (Int) a[0]) : Ops.shr(x, (Int) a[0]);
                    yield op.equals("shl") || x.v() >= 0 ? new Int(k, 0) : new Int(k, -1);
                }
            };
        }
        var exact = Ops.exactOp(op, x, op.equals("neg") ? x : (Int) a[0]);
        return switch (mode) {
            case "wrapping" -> Ops.wrapBig(k, exact);
            case "checked" -> Ops.exact(k, exact);
            case "saturating" -> Ops.saturate(k, exact);
            default -> unknown("int." + name);
        };
    }

    private Object floating(String name, Object self, Object[] a, FnInfo fn) {
        var p = ownerPrim(fn);
        switch (name) {
            case "parse" -> { return Ops.parseFloat(a[0].toString(), p); }
            case "from_i64" -> { return Ops.flt(p, ((Int) a[0]).v()); }
            case "from_f64" -> { return Ops.flt(p, ((Flt) a[0]).v()); }
            default -> {}
        }
        var x = (Flt) self;
        var k = x.kind();
        double v = x.v();
        double o = a.length > 0 && a[0] instanceof Flt f ? f.v() : 0;
        return switch (name) {
            case "abs" -> Ops.flt(k, Math.abs(v));
            case "min" -> Ops.flt(k, Math.min(v, o));
            case "max" -> Ops.flt(k, Math.max(v, o));
            case "pow" -> Ops.flt(k, Math.pow(v, o));
            case "sqrt" -> Ops.flt(k, Math.sqrt(v));
            case "floor" -> Ops.flt(k, Math.floor(v));
            case "ceil" -> Ops.flt(k, Math.ceil(v));
            case "round" -> Ops.flt(k, v < 0 ? -Math.floor(-v + 0.5) : Math.floor(v + 0.5));
            case "trunc" -> Ops.flt(k, v < 0 ? Math.ceil(v) : Math.floor(v));
            case "sin" -> Ops.flt(k, Math.sin(v));
            case "cos" -> Ops.flt(k, Math.cos(v));
            case "tan" -> Ops.flt(k, Math.tan(v));
            case "exp" -> Ops.flt(k, Math.exp(v));
            case "ln" -> Ops.flt(k, Math.log(v));
            case "log10" -> Ops.flt(k, Math.log10(v));
            case "is_nan" -> Double.isNaN(v);
            case "is_infinite" -> Double.isInfinite(v);
            case "to_str" -> Str.of(Ops.display(x));
            case "cmp" -> Ops.ordering(Double.compare(v, o));
            default -> unknown("float." + name);
        };
    }

    // ---- std::math, std::random ----

    private static final java.security.SecureRandom ENTROPY = new java.security.SecureRandom();

    private static Object math(String name, Object[] a) {
        double x = ((Flt) a[0]).v();
        return Ops.flt(Prim.F64, switch (name) {
            case "copysign" -> Math.copySign(x, ((Flt) a[1]).v());
            case "cbrt" -> Math.cbrt(x);
            case "expm1" -> Math.expm1(x);
            case "log2" -> x == Math.scalb(1.0, Math.getExponent(x)) ? Math.getExponent(x) : Math.log(x) / Math.log(2);
            case "log1p" -> Math.log1p(x);
            case "asin" -> Math.asin(x);
            case "acos" -> Math.acos(x);
            case "atan" -> Math.atan(x);
            case "atan2" -> Math.atan2(x, ((Flt) a[1]).v());
            case "hypot" -> Math.hypot(x, ((Flt) a[1]).v());
            case "sinh" -> Math.sinh(x);
            case "cosh" -> Math.cosh(x);
            case "tanh" -> Math.tanh(x);
            case "asinh" -> x == 0 || Double.isInfinite(x) ? x
                    : Math.copySign(Math.log(Math.abs(x) + Math.sqrt(x * x + 1)), x);
            case "acosh" -> Math.log(x + Math.sqrt(x * x - 1));
            case "atanh" -> 0.5 * Math.log1p(2 * x / (1 - x));
            default -> throw new IllegalStateException("unknown intrinsic math." + name);
        });
    }

    private Object str(String name, Str s, Object[] a) {
        return switch (name) {
            case "len" -> usize(s.len());
            case "index" -> get(s, a[0]);
            case "slice" -> {
                long start = ((Int) a[0]).v(), end = ((Int) a[1]).v();
                if (start < 0 || end < 0 || end > s.len() || start > end) {
                    throw new Panic("slice " + Long.toUnsignedString(start) + ".." + Long.toUnsignedString(end)
                            + " out of range for a string of length " + s.len());
                }
                yield s.slice((int) start, (int) end);
            }
            case "chars" -> {
                int[] i = {0};
                yield generate(Prim.CHAR, () -> i[0] < s.len() ? new Char(s.at(i[0]++)) : rt.done());
            }
            case "bytes" -> {
                var bytes = s.toString().getBytes(StandardCharsets.UTF_8);
                int[] i = {0};
                yield generate(Prim.U8, () -> i[0] < bytes.length ? new Int(Prim.U8, bytes[i[0]++] & 0xff) : rt.done());
            }
            case "upper" -> Str.of(s.toString().toUpperCase(Locale.ROOT));
            case "lower" -> Str.of(s.toString().toLowerCase(Locale.ROOT));
            case "hash" -> new Int(Prim.U64, Ops.hashStr(s.toString()));
            default -> unknown("str." + name);
        };
    }

    private Object character(String name, Char c, Object[] a) {
        int cp = c.cp();
        return switch (name) {
            case "is_alphabetic" -> Character.isLetter(cp);
            case "is_alphanumeric" -> Character.isLetterOrDigit(cp);
            case "is_whitespace" -> Character.isWhitespace(cp);
            case "is_uppercase" -> Character.isUpperCase(cp);
            case "is_lowercase" -> Character.isLowerCase(cp);
            case "to_upper" -> new Char(Character.toUpperCase(cp));
            case "to_lower" -> new Char(Character.toLowerCase(cp));
            case "to_str" -> Str.of(Character.toString(cp));
            case "hash" -> new Int(Prim.U64, Ops.mix(cp));
            default -> unknown("char." + name);
        };
    }

    private Object enumeration(String name, EnumVal e, Object[] a) {
        return switch (name) {
            case "to_str" -> Str.of(Ops.display(e));
            case "hash" -> new Int(Prim.U64, Ops.mix(e.v()));
            default -> unknown("enum." + name);
        };
    }
}
