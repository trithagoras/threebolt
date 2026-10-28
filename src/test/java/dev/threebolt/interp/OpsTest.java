package dev.threebolt.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.threebolt.interp.Values.Char;
import dev.threebolt.interp.Values.Flt;
import dev.threebolt.interp.Values.Int;
import dev.threebolt.types.Prim;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/** Checked integer arithmetic (§13.2), casts (§7.9), float display and parsing. */
class OpsTest {
    private static Int i(Prim kind, long v) {
        return Ops.exact(kind, BigInteger.valueOf(v));
    }

    private static Int u64(String v) {
        return Ops.exact(Prim.U64, new BigInteger(v));
    }

    private static void panics(String message, Runnable r) {
        assertEquals(message, assertThrows(Panic.class, r::run).getMessage());
    }

    // ---- Integers ----

    @Test
    void arithmeticWithinRange() {
        assertEquals(i(Prim.I32, 7), Ops.add(i(Prim.I32, 3), i(Prim.I32, 4)));
        assertEquals(i(Prim.I8, -128), Ops.sub(i(Prim.I8, -100), i(Prim.I8, 28)));
        assertEquals(i(Prim.U8, 255), Ops.mul(i(Prim.U8, 15), i(Prim.U8, 17)));
        assertEquals(i(Prim.I32, -3), Ops.div(i(Prim.I32, -7), i(Prim.I32, 2)), "truncates toward zero");
        assertEquals(i(Prim.I32, -1), Ops.rem(i(Prim.I32, -7), i(Prim.I32, 2)), "sign of the dividend");
    }

    @Test
    void overflowPanics() {
        panics("integer overflow (i32)", () -> Ops.add(i(Prim.I32, Integer.MAX_VALUE), i(Prim.I32, 1)));
        panics("integer overflow (u8)", () -> Ops.sub(i(Prim.U8, 0), i(Prim.U8, 1)));
        panics("integer overflow (i8)", () -> Ops.mul(i(Prim.I8, 16), i(Prim.I8, 8)));
        panics("integer overflow (i64)", () -> Ops.mul(i(Prim.I64, Long.MAX_VALUE), i(Prim.I64, 2)));
        panics("integer overflow (i64)", () -> Ops.add(i(Prim.I64, Long.MAX_VALUE), i(Prim.I64, 1)));
        panics("integer overflow (i32)", () -> Ops.neg(i(Prim.I32, Integer.MIN_VALUE)));
        panics("integer overflow (i32)", () -> Ops.div(i(Prim.I32, Integer.MIN_VALUE), i(Prim.I32, -1)));
        panics("integer overflow (u32)", () -> Ops.neg(i(Prim.U32, 1)));
    }

    @Test
    void unsigned64UsesTheFullRange() {
        var max = u64("18446744073709551615");
        assertEquals("18446744073709551615", max.toString());
        assertEquals(max, Ops.add(u64("18446744073709551614"), u64("1")));
        panics("integer overflow (u64)", () -> Ops.add(max, u64("1")));
        panics("integer overflow (u64)", () -> Ops.sub(u64("0"), u64("1")));
        panics("integer overflow (u64)", () -> Ops.mul(max, u64("2")));
        assertEquals(u64("9223372036854775807"), Ops.div(max, u64("2")));
        assertEquals(1, Ops.compare(max, u64("1")), "compared unsigned");
        assertNull(Ops.exact(Prim.U64, BigInteger.ONE.shiftLeft(64)));
    }

    @Test
    void divisionByZeroPanics() {
        panics("division by zero", () -> Ops.div(i(Prim.I32, 1), i(Prim.I32, 0)));
        panics("remainder by zero", () -> Ops.rem(i(Prim.U8, 1), i(Prim.U8, 0)));
        assertEquals(i(Prim.I32, 0), Ops.rem(i(Prim.I32, Integer.MIN_VALUE), i(Prim.I32, -1)));
    }

    @Test
    void shifts() {
        assertEquals(i(Prim.U8, 0b1000_0000), Ops.shl(i(Prim.U8, 1), i(Prim.I32, 7)));
        assertEquals(i(Prim.U8, 0), Ops.shl(i(Prim.U8, 2), i(Prim.I32, 7)), "bits shifted out are lost");
        assertEquals(i(Prim.I8, -1), Ops.shr(i(Prim.I8, -128), i(Prim.U8, 7)), "arithmetic for signed");
        assertEquals(u64("1"), Ops.shr(u64("18446744073709551615"), i(Prim.I32, 63)), "logical for unsigned");
        panics("shift amount 32 out of range for i32", () -> Ops.shl(i(Prim.I32, 1), i(Prim.I32, 32)));
        panics("shift amount -1 out of range for u8", () -> Ops.shr(i(Prim.U8, 1), i(Prim.I32, -1)));
    }

    @Test
    void bitwiseAndWrapping() {
        assertEquals(i(Prim.U8, 0xF0), Ops.bitNot(i(Prim.U8, 0x0F)));
        assertEquals(i(Prim.I8, -1), Ops.bitNot(i(Prim.I8, 0)));
        assertEquals(i(Prim.I8, -128), Ops.wrap(Prim.I8, 128));
        assertEquals(i(Prim.U16, 0xFFFF), Ops.wrap(Prim.U16, -1));
        assertEquals(i(Prim.I8, 127), Ops.saturate(Prim.I8, BigInteger.valueOf(1000)));
        assertEquals(i(Prim.U8, 0), Ops.saturate(Prim.U8, BigInteger.valueOf(-5)));
    }

    // ---- Casts (§7.9) ----

    @Test
    void integerCastsWrap() {
        assertEquals(i(Prim.U8, 44), Ops.cast(i(Prim.I32, 300), Prim.U8));
        assertEquals(i(Prim.I8, -1), Ops.cast(i(Prim.U8, 255), Prim.I8));
        assertEquals(u64("18446744073709551615"), Ops.cast(i(Prim.I32, -1), Prim.U64));
        assertEquals(i(Prim.I64, -1), Ops.cast(u64("18446744073709551615"), Prim.I64));
    }

    @Test
    void floatToIntTruncatesAndSaturates() {
        assertEquals(i(Prim.I32, -2), Ops.cast(new Flt(Prim.F64, -2.9), Prim.I32));
        assertEquals(i(Prim.U8, 255), Ops.cast(new Flt(Prim.F64, 1e10), Prim.U8));
        assertEquals(i(Prim.U8, 0), Ops.cast(new Flt(Prim.F64, -3.5), Prim.U8));
        assertEquals(i(Prim.I32, 0), Ops.cast(new Flt(Prim.F64, Double.NaN), Prim.I32));
        assertEquals(i(Prim.I64, Long.MIN_VALUE), Ops.cast(new Flt(Prim.F64, Double.NEGATIVE_INFINITY), Prim.I64));
        assertEquals(u64("18446744073709551615"), Ops.cast(new Flt(Prim.F64, 1e30), Prim.U64));
    }

    @Test
    void floatCastsRound() {
        assertEquals(new Flt(Prim.F32, (double) (float) 0.1), Ops.cast(new Flt(Prim.F64, 0.1), Prim.F32));
        assertEquals(new Flt(Prim.F64, 1.8446744073709552e19), Ops.cast(u64("18446744073709551615"), Prim.F64));
    }

    @Test
    void charAndBoolCasts() {
        assertEquals(i(Prim.I32, 0x1F600), Ops.cast(new Char(0x1F600), Prim.I32));
        assertEquals(i(Prim.U8, 0), Ops.cast(new Char(0x100), Prim.U8), "wraps like an integer");
        assertEquals(new Char('A'), Ops.cast(i(Prim.U8, 65), Prim.CHAR));
        assertNull(Ops.cast(i(Prim.I32, 0xD800), Prim.CHAR), "surrogates are not scalar values");
        assertNull(Ops.cast(i(Prim.I32, 0x110000), Prim.CHAR));
        assertNull(Ops.cast(i(Prim.I32, -1), Prim.CHAR));
        assertEquals(i(Prim.U8, 1), Ops.cast(true, Prim.U8));
    }

    // ---- Floats ----

    @Test
    void floatDisplay() {
        assertEquals("1", Ops.formatFloat(1.0, false));
        assertEquals("0.5", Ops.formatFloat(0.5, false));
        assertEquals("0.30000000000000004", Ops.formatFloat(0.1 + 0.2, false));
        assertEquals("0.1", Ops.formatFloat((float) 0.1, true), "shortest digits for f32");
        assertEquals("1000000000000000000000", Ops.formatFloat(1e21, false), "no exponent");
        assertEquals("0.0000001", Ops.formatFloat(1e-7, false));
        assertEquals("-0", Ops.formatFloat(-0.0, false));
        assertEquals("-2.5", Ops.formatFloat(-2.5, false));
        assertEquals("inf", Ops.formatFloat(Double.POSITIVE_INFINITY, false));
        assertEquals("-inf", Ops.formatFloat(Double.NEGATIVE_INFINITY, true));
        assertEquals("NaN", Ops.formatFloat(Double.NaN, false));
    }

    @Test
    void f32ValuesStayRounded() {
        assertEquals((double) (float) 0.1, Ops.flt(Prim.F32, 0.1).v());
        assertEquals(0.1, Ops.flt(Prim.F64, 0.1).v());
    }

    // ---- Parsing ----

    @Test
    void parsing() {
        assertEquals(new Flt(Prim.F64, 2.5), Ops.parseFloat("2.5", Prim.F64));
        assertEquals(new Flt(Prim.F64, 0.5), Ops.parseFloat(".5", Prim.F64));
        assertEquals(new Flt(Prim.F64, 1e3), Ops.parseFloat("1e3", Prim.F64));
        assertEquals(new Flt(Prim.F64, Double.NEGATIVE_INFINITY), Ops.parseFloat("-inf", Prim.F64));
        assertNull(Ops.parseFloat("1.2.3", Prim.F64));
    }
}
