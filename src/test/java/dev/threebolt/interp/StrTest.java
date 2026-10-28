package dev.threebolt.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.threebolt.interp.Values.Str;
import org.junit.jupiter.api.Test;

/** {@code str} (§3.1): character counts, indexing and slicing, inside and outside the BMP. */
class StrTest {
    private static final String CRAB = "🦀"; // U+1F980, two UTF-16 units

    @Test
    void countsCharactersNotUnits() {
        assertEquals(0, Str.of("").len());
        assertEquals(5, Str.of("héllo").len());
        assertEquals(3, Str.of("a" + CRAB + "b").len());
        assertEquals(2, Str.of(CRAB + CRAB).len());
    }

    @Test
    void indexesByCharacter() {
        var s = Str.of("a" + CRAB + "b" + CRAB);
        assertEquals('a', s.at(0));
        assertEquals(0x1F980, s.at(1));
        assertEquals('b', s.at(2));
        assertEquals(0x1F980, s.at(3));
        assertEquals('é', Str.of("héllo").at(1));
    }

    @Test
    void slicesByCharacter() {
        var s = Str.of("a" + CRAB + "bc");
        assertEquals(Str.of(CRAB + "b"), s.slice(1, 3));
        assertEquals(2, s.slice(1, 3).len());
        assertEquals(Str.of("bc"), s.slice(2, 4));
        assertEquals(Str.EMPTY, s.slice(2, 2));
        assertSame(s, s.slice(0, 4));
        assertEquals(Str.of("ll"), Str.of("hello").slice(2, 4));
    }

    @Test
    void concatenatesAndKeepsCounts() {
        var bmp = Str.of("ab").concat(Str.of("cd"));
        assertEquals(Str.of("abcd"), bmp);
        assertEquals(4, bmp.len());
        var mixed = Str.of("ab").concat(Str.of(CRAB));
        assertEquals(3, mixed.len());
        assertEquals(0x1F980, mixed.at(2));
        assertEquals(1, mixed.slice(2, 3).len());
    }

    @Test
    void ordersByCodePoint() {
        // U+FF5E is above the surrogates in UTF-16 order but below U+1F980 in code-point order.
        assertTrue(Str.of("～").compareTo(Str.of(CRAB)) < 0);
        assertTrue(Str.of("ab").compareTo(Str.of("abc")) < 0);
        assertTrue(Str.of("b").compareTo(Str.of("abc")) > 0);
        assertEquals(0, Str.of("a" + CRAB).compareTo(Str.of("a" + CRAB)));
    }

    @Test
    void equalityAndHashFollowTheText() {
        assertEquals(Str.of("x" + CRAB), Str.of("x").concat(Str.of(CRAB)));
        assertEquals(Str.of("abc").hashCode(), Str.of("ab").concat(Str.of("c")).hashCode());
        assertNotEquals(Str.of("a"), "a");
    }
}
