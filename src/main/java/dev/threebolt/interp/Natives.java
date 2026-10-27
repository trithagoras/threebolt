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
import dev.threebolt.interp.Values.TupleVal;
import dev.threebolt.interp.Values.TypeVal;
import dev.threebolt.interp.Values.Unit;
import dev.threebolt.types.FnInfo;
import dev.threebolt.types.Prim;
import dev.threebolt.types.Type;
import dev.threebolt.types.TypeInfo;
import dev.threebolt.types.TypeParamInfo;
import dev.threebolt.types.TypeSystem;
import java.io.IOException;
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
    private final Interpreter in;

    Natives(Interpreter in) {
        this.in = in;
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
                in.out.flush();
                throw new Interpreter.ExitSignal((int) ((Int) a[0]).v());
            }
            case "c", "Ptr" -> in.ffi.intrinsic(owner, name, recv, a, typeEnv, fn);
            case "cstruct" -> in.ffi.withPtr(recv, (Values.FnVal) a[0]);
            case "thread", "Thread", "Mutex", "Atomic" -> in.threads.call(owner, name, recv, a, typeEnv, fn);
            case "Type" -> type(name, (TypeVal) recv, a);
            case "math" -> math(name, a);
            case "random" -> new Int(Prim.U64, ENTROPY.nextLong());
            // Collections lock themselves per operation (§13.4), but not around a callback.
            case "List" -> name.equals("with_ptr") ? in.ffi.withPtr(recv, (Values.FnVal) a[0])
                    : locked(recv, () -> recv instanceof ArrayVal arr ? array(name, arr, a) : list(name, (ListVal) recv, a));
            case "Map" -> locked(recv, () -> map(name, (MapVal) recv, a));
            case "Set" -> locked(recv, () -> set(name, (SetVal) recv, a));
            case "Range" -> range(name, (Obj) recv, a);
            case "NativeIter" -> ((IterVal) recv).next.get();
            case "int" -> integer(name, recv, a, fn);
            case "float" -> floating(name, recv, a, fn);
            case "str" -> name.equals("from_cstr") || name.equals("to_cstr")
                    ? in.ffi.intrinsic("str", name, recv, a, typeEnv, fn) : str(name, (String) recv, a);
            case "char" -> character(name, (Char) recv, a);
            case "bool" -> switch (name) {
                case "to_str" -> recv.toString();
                case "eq" -> recv.equals(a[0]);
                case "hash" -> new Int(Prim.U64, Ops.mix(((Boolean) recv) ? 1 : 0));
                default -> unknown(key);
            };
            case "enum", "flags" -> enumeration(name, (EnumVal) recv, a);
            default -> unknown(key);
        };
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
        in.checkAbort();
        switch (name) {
            case "print" -> in.out.print(in.display(a[0]));
            case "println" -> in.out.println(in.display(a[0]));
            case "eprintln" -> {
                in.out.flush();
                in.err.println(in.display(a[0]));
            }
            case "readln" -> {
                return readln((String) a[0]);
            }
            case "panic" -> throw new Panic((String) a[0]);
            default -> unknown("prelude." + name);
        }
        return Unit.UNIT;
    }

    /** A line of stdin without its `\n` or `\r\n`, or null at end of input (§16). */
    private Object readln(String prompt) {
        in.out.print(prompt);
        in.out.flush();
        var line = new StringBuilder();
        synchronized (in.stdin) {
            try {
                for (int c; (c = in.stdin.read()) != -1; ) {
                    if (c == '\n') {
                        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') line.setLength(line.length() - 1);
                        return line.toString();
                    }
                    line.append((char) c);
                }
            } catch (IOException e) {
                throw new Panic("readln: " + e.getMessage());
            }
        }
        return line.isEmpty() ? null : line.toString();
    }

    private Object type(String name, TypeVal t, Object[] a) {
        return switch (name) {
            case "name", "to_str" -> TypeSystem.name(t.type());
            case "eq" -> t.equals(a[0]);
            case "hash" -> new Int(Prim.U64, Ops.mix(t.type().hashCode()));
            case "is_subtype_of" -> in.ts.assignable(t.type(), ((TypeVal) a[0]).type());
            default -> unknown("Type." + name);
        };
    }

    // ---- Iterators ----

    /** An iterator over a Java iterator; elements are copied (value semantics). */
    private IterVal iter(Type element, Iterator<Object> it) {
        return new IterVal(element, () -> it.hasNext() ? Values.copy(it.next()) : in.done);
    }

    /** Iterates a live list by index: elements pushed during the loop are visited. */
    private IterVal listIter(ListVal l) {
        int[] i = {0};
        return new IterVal(l.element, () -> {
            synchronized (l) {
                return i[0] < l.items.size() ? Values.copy(l.items.get(i[0]++)) : in.done;
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
            case "truthy" -> !items.isEmpty();
            case "iter" -> listIter(l);
            case "index" -> in.indexGet(l, a[0]);
            case "set" -> {
                in.indexSet(l, a[0], a[1]);
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
            case "remove" -> items.remove(Interpreter.checkIndex(a[0], items.size()));
            case "clear" -> {
                items.clear();
                yield Unit.UNIT;
            }
            case "reverse" -> {
                Collections.reverse(items);
                yield Unit.UNIT;
            }
            case "contains" -> {
                for (Object x : items) if (in.valueEquals(x, a[0])) yield true;
                yield false;
            }
            case "first" -> items.isEmpty() ? null : Values.copy(items.getFirst());
            case "last" -> items.isEmpty() ? null : Values.copy(items.getLast());
            default -> unknown("List." + name);
        };
    }

    private Object array(String name, ArrayVal arr, Object[] a) {
        var items = arr.items;
        return switch (name) {
            case "len" -> usize(items.length);
            case "truthy" -> items.length > 0;
            case "iter" -> iter(arr.element, java.util.Arrays.asList(items).iterator());
            case "index" -> in.indexGet(arr, a[0]);
            case "set" -> {
                in.indexSet(arr, a[0], a[1]);
                yield Unit.UNIT;
            }
            case "contains" -> {
                for (Object x : items) if (in.valueEquals(x, a[0])) yield true;
                yield false;
            }
            case "first" -> items.length == 0 ? null : Values.copy(items[0]);
            case "last" -> items.length == 0 ? null : Values.copy(items[items.length - 1]);
            default -> unknown("array." + name);
        };
    }

    private Object map(String name, MapVal m, Object[] a) {
        var entries = m.entries;
        return switch (name) {
            case "len" -> usize(entries.size());
            case "truthy" -> !entries.isEmpty();
            case "iter" -> {
                var it = new ArrayList<>(entries.entrySet()).iterator();
                yield generate(new Type.Tuple(List.of(m.key, m.value)), () -> {
                    if (!it.hasNext()) return in.done;
                    var e = it.next();
                    return new TupleVal(new Object[] {Values.copy(e.getKey().value), Values.copy(e.getValue())});
                });
            }
            case "index" -> in.indexGet(m, a[0]);
            case "set" -> {
                entries.put(new Key(a[0], in), a[1]);
                yield Unit.UNIT;
            }
            case "get" -> Values.copy(entries.get(new Key(a[0], in)));
            case "contains_key" -> entries.containsKey(new Key(a[0], in));
            case "remove" -> entries.remove(new Key(a[0], in));
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
            case "truthy" -> !entries.isEmpty();
            case "iter" -> iter(s.element, new ArrayList<>(entries.values()).iterator());
            case "insert" -> entries.putIfAbsent(new Key(a[0], in), a[0]) == null;
            case "contains" -> entries.containsKey(new Key(a[0], in));
            case "remove" -> entries.remove(new Key(a[0], in)) != null;
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
                        return cur[0] <= last ? new Char(cur[0]++) : in.done;
                    });
                }
                var s = (Int) start;
                var e = (Int) end;
                var kind = s.kind();
                BigInteger[] cur = {s.big()};
                BigInteger limit = inclusive ? e.big() : e.big().subtract(BigInteger.ONE);
                yield generate(kind, () -> {
                    if (cur[0].compareTo(limit) > 0) return in.done;
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
            case "parse" -> { return Ops.parseInt((String) a[0], ownerPrim(fn)); }
            case "from_i64" -> { return fromI64(ownerPrim(fn), ((Int) a[0]).v()); }
            case "zero" -> { return new Int(ownerPrim(fn), 0); }
            case "one" -> { return new Int(ownerPrim(fn), 1); }
            case "min_value" -> { return new Int(ownerPrim(fn), ownerPrim(fn).min().longValue()); }
            case "max_value" -> { return new Int(ownerPrim(fn), ownerPrim(fn).max().longValue()); }
            case "bits" -> { return new Int(Prim.U32, ownerPrim(fn).bits()); }
            default -> {}
        }
        var x = (Int) self;
        var k = x.kind();
        return switch (name) {
            case "abs" -> k.isSigned() && x.v() < 0 ? Ops.neg(x) : x;
            case "min" -> Ops.compare(x, (Int) a[0]) <= 0 ? x : a[0];
            case "max" -> Ops.compare(x, (Int) a[0]) >= 0 ? x : a[0];
            case "pow" -> Ops.pow(x, ((Int) a[0]).v());
            case "to_str" -> x.toString();
            case "eq" -> x.equals(a[0]);
            case "cmp" -> new Int(Prim.I32, Ops.compare(x, (Int) a[0]));
            case "hash" -> new Int(Prim.U64, Ops.mix(x.v()));
            case "add" -> Ops.add(x, (Int) a[0]);
            case "sub" -> Ops.sub(x, (Int) a[0]);
            case "mul" -> Ops.mul(x, (Int) a[0]);
            case "div" -> Ops.div(x, (Int) a[0]);
            case "rem" -> Ops.rem(x, (Int) a[0]);
            case "neg" -> Ops.neg(x);
            case "bitand" -> Ops.and(x, (Int) a[0]);
            case "bitor" -> Ops.or(x, (Int) a[0]);
            case "bitxor" -> Ops.xor(x, (Int) a[0]);
            case "not" -> Ops.bitNot(x);
            case "shl" -> Ops.shl(x, (Int) a[0]);
            case "shr" -> Ops.shr(x, (Int) a[0]);
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
            case "parse" -> { return Ops.parseFloat((String) a[0], p); }
            case "from_i64" -> { return Ops.flt(p, ((Int) a[0]).v()); }
            case "from_f64" -> { return Ops.flt(p, ((Flt) a[0]).v()); }
            case "zero" -> { return Ops.flt(p, 0); }
            case "one" -> { return Ops.flt(p, 1); }
            case "nan" -> { return Ops.flt(p, Double.NaN); }
            case "infinity" -> { return Ops.flt(p, Double.POSITIVE_INFINITY); }
            case "epsilon" -> { return Ops.flt(p, p == Prim.F32 ? Math.ulp(1.0f) : Math.ulp(1.0)); }
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
            case "to_str" -> Ops.display(x);
            case "eq" -> v == o;
            case "cmp" -> new Int(Prim.I32, Double.compare(v, o));
            case "add" -> Ops.flt(k, v + o);
            case "sub" -> Ops.flt(k, v - o);
            case "mul" -> Ops.flt(k, v * o);
            case "div" -> Ops.flt(k, v / o);
            case "rem" -> Ops.flt(k, v % o);
            case "neg" -> Ops.flt(k, -v);
            default -> unknown("float." + name);
        };
    }

    // ---- std::math, std::random ----

    private static final java.security.SecureRandom ENTROPY = new java.security.SecureRandom();

    private static Object math(String name, Object[] a) {
        if (name.equals("isqrt")) {
            return new Int(Prim.U64, unsigned((Int) a[0]).sqrt().longValue());
        }
        if (name.equals("factorial") || name.equals("comb") || name.equals("perm")) {
            var n = unsigned((Int) a[0]);
            var k = name.equals("factorial") ? n : unsigned((Int) a[1]);
            if (k.compareTo(n) > 0) return new Int(Prim.U64, 0);
            // n! / (n-k)!, then / k! for comb (using the smaller of k and n-k).
            if (name.equals("comb") && k.compareTo(n.subtract(k)) > 0) k = n.subtract(k);
            var r = BigInteger.ONE;
            for (var i = BigInteger.ZERO; i.compareTo(k) < 0; i = i.add(BigInteger.ONE)) {
                r = r.multiply(n.subtract(i));
                if (name.equals("comb")) r = r.divide(i.add(BigInteger.ONE));
                if (r.bitLength() > 64) throw new Panic("math::" + name + ": the result does not fit in a u64");
            }
            return new Int(Prim.U64, r.longValue());
        }
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

    private static BigInteger unsigned(Int x) {
        return new BigInteger(Long.toUnsignedString(x.v()));
    }

    private Object str(String name, String s, Object[] a) {
        return switch (name) {
            case "len" -> usize(Ops.utf8Length(s));
            case "truthy" -> !s.isEmpty();
            case "chars" -> {
                var cps = s.codePoints().iterator();
                yield generate(Prim.CHAR, () -> cps.hasNext() ? new Char(cps.next()) : in.done);
            }
            case "bytes" -> {
                var bytes = s.getBytes(StandardCharsets.UTF_8);
                int[] i = {0};
                yield generate(Prim.U8, () -> i[0] < bytes.length ? new Int(Prim.U8, bytes[i[0]++] & 0xff) : in.done);
            }
            case "contains" -> s.contains((String) a[0]);
            case "starts_with" -> s.startsWith((String) a[0]);
            case "ends_with" -> s.endsWith((String) a[0]);
            case "find" -> {
                int i = s.indexOf((String) a[0]);
                yield i < 0 ? null : usize(Ops.utf8Length(s.substring(0, i)));
            }
            case "slice" -> slice(s, ((Int) a[0]).v(), ((Int) a[1]).v());
            case "upper" -> s.toUpperCase(Locale.ROOT);
            case "lower" -> s.toLowerCase(Locale.ROOT);
            case "trim" -> s.strip();
            case "split" -> {
                var parts = new ArrayList<Object>();
                String sep = (String) a[0];
                if (sep.isEmpty()) {
                    s.codePoints().forEach(cp -> parts.add(Character.toString(cp)));
                } else {
                    int from = 0;
                    while (true) {
                        int i = s.indexOf(sep, from);
                        if (i < 0) break;
                        parts.add(s.substring(from, i));
                        from = i + sep.length();
                    }
                    parts.add(s.substring(from));
                }
                yield new ListVal(Prim.STR, parts);
            }
            case "replace" -> s.replace((String) a[0], (String) a[1]);
            case "repeat" -> s.repeat((int) ((Int) a[0]).v());
            case "to_str" -> s;
            case "eq" -> s.equals(a[0]);
            case "cmp" -> new Int(Prim.I32, Integer.signum(Ops.compareStr(s, (String) a[0])));
            case "hash" -> new Int(Prim.U64, Ops.hashStr(s));
            case "add" -> s + a[0];
            default -> unknown("str." + name);
        };
    }

    /** Byte offsets; panics unless both fall on character boundaries. */
    private static String slice(String s, long start, long end) {
        var bytes = s.getBytes(StandardCharsets.UTF_8);
        if (start < 0 || end > bytes.length || start > end) {
            throw new Panic("slice " + start + ".." + end + " out of range for a string of length " + bytes.length);
        }
        if (!boundary(bytes, (int) start) || !boundary(bytes, (int) end)) {
            throw new Panic("slice " + start + ".." + end + " is not on character boundaries");
        }
        return new String(bytes, (int) start, (int) (end - start), StandardCharsets.UTF_8);
    }

    private static boolean boundary(byte[] b, int i) {
        return i == b.length || (b[i] & 0xC0) != 0x80;
    }

    private Object character(String name, Char c, Object[] a) {
        int cp = c.cp();
        return switch (name) {
            case "is_digit" -> cp >= '0' && cp <= '9';
            case "is_alphabetic" -> Character.isLetter(cp);
            case "is_alphanumeric" -> Character.isLetterOrDigit(cp);
            case "is_whitespace" -> Character.isWhitespace(cp);
            case "is_uppercase" -> Character.isUpperCase(cp);
            case "is_lowercase" -> Character.isLowerCase(cp);
            case "to_upper" -> new Char(Character.toUpperCase(cp));
            case "to_lower" -> new Char(Character.toLowerCase(cp));
            case "to_str" -> Character.toString(cp);
            case "eq" -> c.equals(a[0]);
            case "cmp" -> new Int(Prim.I32, Integer.compare(cp, ((Char) a[0]).cp()));
            case "hash" -> new Int(Prim.U64, Ops.mix(cp));
            default -> unknown("char." + name);
        };
    }

    private Object enumeration(String name, EnumVal e, Object[] a) {
        return switch (name) {
            case "to_str" -> in.enumDisplay(e);
            case "eq" -> e.equals(a[0]);
            case "cmp" -> new Int(Prim.I32, Long.compare(e.v(), ((EnumVal) a[0]).v()));
            case "hash" -> new Int(Prim.U64, Ops.mix(e.v()));
            case "has" -> {
                long flag = ((EnumVal) a[0]).v();
                yield (e.v() & flag) == flag;
            }
            case "bitand" -> new EnumVal(e.decl(), e.v() & ((EnumVal) a[0]).v());
            case "bitor" -> new EnumVal(e.decl(), e.v() | ((EnumVal) a[0]).v());
            case "bitxor" -> new EnumVal(e.decl(), e.v() ^ ((EnumVal) a[0]).v());
            case "not" -> new EnumVal(e.decl(), Ops.wrap(e.decl().backing, ~e.v()).v());
            default -> unknown("enum." + name);
        };
    }
}
