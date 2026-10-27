package dev.threebolt.types;

import java.math.BigInteger;
import java.util.Locale;

/** The primitive types (§3.1). Integers keep their bit width and signedness. */
public enum Prim implements Type {
    I8(8, true), I16(16, true), I32(32, true), I64(64, true), ISIZE(64, true),
    U8(8, false), U16(16, false), U32(32, false), U64(64, false), USIZE(64, false),
    F32(32, true), F64(64, true),
    BOOL(0, false), CHAR(0, false), STR(0, false);

    private final int bits;
    private final boolean signed;

    Prim(int bits, boolean signed) {
        this.bits = bits;
        this.signed = signed;
    }

    public String text() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Prim named(String name) {
        for (Prim p : values()) if (p.text().equals(name)) return p;
        return null;
    }

    public int bits() {
        return bits;
    }

    public boolean isSigned() {
        return signed;
    }

    public boolean isInteger() {
        return ordinal() <= USIZE.ordinal();
    }

    public boolean isFloat() {
        return this == F32 || this == F64;
    }

    public boolean isNumeric() {
        return isInteger() || isFloat();
    }

    public BigInteger min() {
        return signed ? BigInteger.ONE.shiftLeft(bits - 1).negate() : BigInteger.ZERO;
    }

    public BigInteger max() {
        return signed ? BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE)
                : BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE);
    }

    public boolean fits(BigInteger v) {
        return v.compareTo(min()) >= 0 && v.compareTo(max()) <= 0;
    }

    /**
     * Lossless implicit widening (§3.6). Nothing widens to {@code isize}/{@code usize}; they widen
     * only to {@code i64}/{@code u64}.
     */
    public boolean widensTo(Prim to) {
        if (this == to) return true;
        if (this == ISIZE) return to == I64;
        if (this == USIZE) return to == U64;
        if (to == ISIZE || to == USIZE) return false;
        if (isInteger() && to.isInteger()) {
            return to.bits > bits && (to.signed || !signed);
        }
        if (isInteger() && to == F64) return bits <= 32;
        if (isInteger() && to == F32) return bits <= 16;
        return this == F32 && to == F64;
    }

    @Override
    public String toString() {
        return text();
    }
}
