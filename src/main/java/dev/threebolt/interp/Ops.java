package dev.threebolt.interp;

import dev.threebolt.ast.Expr.BinaryOp;
import dev.threebolt.interp.Values.Char;
import dev.threebolt.interp.Values.EnumVal;
import dev.threebolt.interp.Values.Flt;
import dev.threebolt.interp.Values.Int;
import dev.threebolt.interp.Values.Str;
import dev.threebolt.types.Prim;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Built-in operations on primitive values: checked integer arithmetic (§13.2), IEEE float
 * arithmetic, numeric casts (§7.9), comparisons, display (§16) and parsing. Shared by the
 * interpreter and compile-time constant evaluation.
 */
public final class Ops {
    private Ops() {}

    // ---- Integers ----

    /** The low {@code bits} of {@code raw}, reinterpreted as {@code kind}. */
    public static Int wrap(Prim kind, long raw) {
        int bits = kind.bits();
        if (bits == 64) return new Int(kind, raw);
        long mask = (1L << bits) - 1;
        long low = raw & mask;
        if (kind.isSigned() && (low & (1L << (bits - 1))) != 0) low |= ~mask;
        return new Int(kind, low);
    }

    /** {@code value} as {@code kind}, or null if out of range. */
    public static Int exact(Prim kind, BigInteger value) {
        if (!kind.fits(value)) return null;
        return new Int(kind, value.longValue());
    }

    private static Int checked(Prim kind, long v) {
        if (kind.bits() < 64) {
            long min = kind.min().longValue();
            long max = kind.max().longValue();
            if (v < min || v > max) throw overflow(kind);
        }
        return new Int(kind, v);
    }

    private static Panic overflow(Prim kind) {
        return new Panic("integer overflow (" + kind + ")");
    }

    private static boolean unsigned64(Prim k) {
        return k == Prim.U64 || k == Prim.USIZE;
    }

    public static Int add(Int a, Int b) {
        var k = a.kind();
        if (unsigned64(k)) {
            long r = a.v() + b.v();
            if (Long.compareUnsigned(r, a.v()) < 0) throw overflow(k);
            return new Int(k, r);
        }
        if (k.bits() == 64) {
            try {
                return new Int(k, Math.addExact(a.v(), b.v()));
            } catch (ArithmeticException e) {
                throw overflow(k);
            }
        }
        return checked(k, a.v() + b.v());
    }

    public static Int sub(Int a, Int b) {
        var k = a.kind();
        if (unsigned64(k)) {
            if (Long.compareUnsigned(a.v(), b.v()) < 0) throw overflow(k);
            return new Int(k, a.v() - b.v());
        }
        if (k.bits() == 64) {
            try {
                return new Int(k, Math.subtractExact(a.v(), b.v()));
            } catch (ArithmeticException e) {
                throw overflow(k);
            }
        }
        return checked(k, a.v() - b.v());
    }

    public static Int mul(Int a, Int b) {
        var k = a.kind();
        if (unsigned64(k)) {
            if (Math.unsignedMultiplyHigh(a.v(), b.v()) != 0) throw overflow(k);
            return new Int(k, a.v() * b.v());
        }
        try {
            long r = Math.multiplyExact(a.v(), b.v());
            return k.bits() == 64 ? new Int(k, r) : checked(k, r);
        } catch (ArithmeticException e) {
            throw overflow(k);
        }
    }

    public static Int div(Int a, Int b) {
        var k = a.kind();
        if (b.v() == 0) throw new Panic("division by zero");
        if (unsigned64(k)) return new Int(k, Long.divideUnsigned(a.v(), b.v()));
        if (k.isSigned() && b.v() == -1 && a.v() == k.min().longValue()) throw overflow(k);
        return new Int(k, a.v() / b.v());
    }

    public static Int rem(Int a, Int b) {
        var k = a.kind();
        if (b.v() == 0) throw new Panic("remainder by zero");
        if (unsigned64(k)) return new Int(k, Long.remainderUnsigned(a.v(), b.v()));
        if (k.isSigned() && b.v() == -1) return new Int(k, 0);
        return new Int(k, a.v() % b.v());
    }

    public static Int neg(Int a) {
        var k = a.kind();
        if (!k.isSigned()) {
            if (a.v() != 0) throw overflow(k);
            return a;
        }
        if (a.v() == k.min().longValue()) throw overflow(k);
        return new Int(k, -a.v());
    }

    public static Int bitNot(Int a) {
        return wrap(a.kind(), ~a.v());
    }

    public static Int and(Int a, Int b) {
        return new Int(a.kind(), a.v() & b.v());
    }

    public static Int or(Int a, Int b) {
        return new Int(a.kind(), a.v() | b.v());
    }

    public static Int xor(Int a, Int b) {
        return new Int(a.kind(), a.v() ^ b.v());
    }

    private static int shiftAmount(Prim kind, Int n) {
        var amount = n.big();
        if (amount.signum() < 0 || amount.compareTo(BigInteger.valueOf(kind.bits())) >= 0) {
            throw new Panic("shift amount " + amount + " out of range for " + kind);
        }
        return amount.intValue();
    }

    public static Int shl(Int a, Int n) {
        return wrap(a.kind(), a.v() << shiftAmount(a.kind(), n));
    }

    public static Int shr(Int a, Int n) {
        int s = shiftAmount(a.kind(), n);
        if (a.kind().isSigned()) return new Int(a.kind(), a.v() >> s);
        return new Int(a.kind(), a.v() >>> s);
    }

    public static int compare(Int a, Int b) {
        return unsigned64(a.kind()) ? Long.compareUnsigned(a.v(), b.v()) : Long.compare(a.v(), b.v());
    }

    /** Exact result of an arithmetic op for the {@code wrapping/checked/saturating} families. */
    public static BigInteger exactOp(String op, Int a, Int b) {
        var x = a.big();
        return switch (op) {
            case "add" -> x.add(b.big());
            case "sub" -> x.subtract(b.big());
            case "mul" -> x.multiply(b.big());
            case "neg" -> x.negate();
            default -> throw new IllegalArgumentException(op);
        };
    }

    public static Int wrapBig(Prim kind, BigInteger v) {
        return wrap(kind, v.longValue());
    }

    public static Int saturate(Prim kind, BigInteger v) {
        if (v.compareTo(kind.max()) > 0) return new Int(kind, kind.max().longValue());
        if (v.compareTo(kind.min()) < 0) return new Int(kind, kind.min().longValue());
        return new Int(kind, v.longValue());
    }

    // ---- Floats ----

    public static Flt flt(Prim kind, double v) {
        return new Flt(kind, kind == Prim.F32 ? (double) (float) v : v);
    }

    // ---- Binary operators on primitives ----

    /** {@code l op r} for two values of primitive type {@code prim}. */
    public static Object binary(BinaryOp op, Prim prim, Object l, Object r) {
        if (prim.isInteger()) {
            var a = (Int) l;
            var b = (Int) r;
            return switch (op) {
                case ADD -> add(a, b);
                case SUB -> sub(a, b);
                case MUL -> mul(a, b);
                case DIV -> div(a, b);
                case REM -> rem(a, b);
                case BIT_AND -> and(a, b);
                case BIT_OR -> or(a, b);
                case BIT_XOR -> xor(a, b);
                case SHL -> shl(a, b);
                case SHR -> shr(a, b);
                case EQ -> compare(a, b) == 0;
                case NE -> compare(a, b) != 0;
                case LT -> compare(a, b) < 0;
                case LE -> compare(a, b) <= 0;
                case GT -> compare(a, b) > 0;
                case GE -> compare(a, b) >= 0;
                default -> throw new IllegalStateException(op.toString());
            };
        }
        if (prim.isFloat()) {
            double a = ((Flt) l).v();
            double b = ((Flt) r).v();
            return switch (op) {
                case ADD -> flt(prim, a + b);
                case SUB -> flt(prim, a - b);
                case MUL -> flt(prim, a * b);
                case DIV -> flt(prim, a / b);
                case REM -> flt(prim, a % b);
                case EQ -> a == b;
                case NE -> a != b;
                case LT -> a < b;
                case LE -> a <= b;
                case GT -> a > b;
                case GE -> a >= b;
                default -> throw new IllegalStateException(op.toString());
            };
        }
        if (op == BinaryOp.ADD && prim == Prim.STR) return ((Str) l).concat((Str) r);
        int c = comparePrim(l, r);
        return switch (op) {
            case EQ -> l.equals(r);
            case NE -> !l.equals(r);
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            case GE -> c >= 0;
            default -> throw new IllegalStateException(op + " on " + prim);
        };
    }

    /** Ordering of two primitive values of the same type (§7.3). */
    public static int comparePrim(Object l, Object r) {
        return switch (l) {
            case Int a -> compare(a, (Int) r);
            case Flt a -> Double.compare(a.v(), ((Flt) r).v());
            case Char a -> Integer.compare(a.cp(), ((Char) r).cp());
            case Str a -> a.compareTo((Str) r);
            case Boolean a -> Boolean.compare(a, (Boolean) r);
            default -> throw new IllegalStateException("not ordered: " + l);
        };
    }

    public static Object negate(Object v) {
        return switch (v) {
            case Int a -> neg(a);
            case Flt f -> flt(f.kind(), -f.v());
            default -> throw new IllegalStateException("negate " + v);
        };
    }

    // ---- Casts (§7.9) ----

    /** A numeric (or char/bool) value converted to {@code to}; null when a char cast is invalid. */
    public static Object cast(Object v, Prim to) {
        switch (v) {
            case Int a -> {
                if (to.isInteger()) return wrap(to, a.v());
                if (to.isFloat()) return flt(to, a.big().doubleValue());
                if (to == Prim.CHAR) return toChar(a.big());
            }
            case Flt f -> {
                if (to.isFloat()) return flt(to, f.v());
                if (to.isInteger()) return floatToInt(f.v(), to);
            }
            case Char c -> {
                if (to.isInteger()) return wrap(to, c.cp());
                if (to == Prim.CHAR) return c;
            }
            case Boolean b -> {
                if (to.isInteger()) return new Int(to, b ? 1 : 0);
                if (to == Prim.BOOL) return b;
            }
            default -> {}
        }
        throw new IllegalStateException("cast " + v + " to " + to);
    }

    private static Object toChar(BigInteger v) {
        if (v.signum() < 0 || v.compareTo(BigInteger.valueOf(0x10FFFF)) > 0) return null;
        int cp = v.intValue();
        if (cp >= 0xD800 && cp <= 0xDFFF) return null;
        return new Char(cp);
    }

    /** Truncates toward zero and saturates; NaN becomes 0. */
    private static Int floatToInt(double d, Prim to) {
        if (Double.isNaN(d)) return new Int(to, 0);
        if (Double.isInfinite(d)) return d > 0 ? new Int(to, to.max().longValue()) : new Int(to, to.min().longValue());
        return saturate(to, new BigDecimal(d).setScale(0, RoundingMode.DOWN).toBigInteger());
    }

    // ---- Display (§16) ----

    /** Member name; flags combinations as {@code A | B}; undeclared bits as the integer (§4.11). */
    static String display(EnumVal e) {
        var members = e.decl().enumMembers;
        for (var m : members.entrySet()) if (m.getValue() == e.v()) return m.getKey();
        if (!e.decl().isFlags) return wrap(e.decl().backing, e.v()).toString();
        var parts = new java.util.ArrayList<String>();
        long rest = e.v();
        for (var m : members.entrySet()) {
            long bits = m.getValue();
            if (bits != 0 && (rest & bits) == bits) {
                parts.add(m.getKey());
                rest &= ~bits;
            }
        }
        if (rest != 0) parts.add(wrap(e.decl().backing, rest).toString());
        return String.join(" | ", parts);
    }

    public static String display(Object v) {
        return switch (v) {
            case Str s -> s.toString();
            case Int i -> i.toString();
            case Flt f -> formatFloat(f.v(), f.kind() == Prim.F32);
            case Char c -> Character.toString(c.cp());
            case Boolean b -> b.toString();
            default -> throw new IllegalStateException("display " + v);
        };
    }

    /** Shortest round-trip digits, no exponent, integral values without a fraction: 1, 0.5, -0, inf, NaN. */
    public static String formatFloat(double d, boolean f32) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "inf" : "-inf";
        String s = f32 ? Float.toString((float) d) : Double.toString(d);
        String plain = new BigDecimal(s).toPlainString();
        if (plain.contains(".")) {
            plain = plain.replaceAll("0+$", "");
            if (plain.endsWith(".")) plain = plain.substring(0, plain.length() - 1);
        }
        if ((d == 0 && 1 / d < 0) && !plain.startsWith("-")) plain = "-" + plain;
        return plain;
    }

    // ---- Parsing ----

    private static final Pattern INT = Pattern.compile("[+-]?[0-9]+");
    private static final Pattern FLOAT = Pattern.compile("[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?");

    public static Int parseInt(String s, Prim kind) {
        if (!INT.matcher(s).matches()) return null;
        return exact(kind, new BigInteger(s));
    }

    public static Flt parseFloat(String s, Prim kind) {
        switch (s) {
            case "inf", "+inf", "infinity" -> {
                return flt(kind, Double.POSITIVE_INFINITY);
            }
            case "-inf", "-infinity" -> {
                return flt(kind, Double.NEGATIVE_INFINITY);
            }
            case "NaN" -> {
                return flt(kind, Double.NaN);
            }
            default -> {}
        }
        if (!FLOAT.matcher(s).matches()) return null;
        return flt(kind, Double.parseDouble(s));
    }

    // ---- Hashing ----

    /** FNV-1a over UTF-8 bytes. */
    public static long hashStr(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xff;
            h *= 0x100000001b3L;
        }
        return h;
    }

    public static long mix(long h) {
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
