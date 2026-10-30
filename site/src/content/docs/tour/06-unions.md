---
title: Unions, narrowing and patterns
description: Flattened union types, literal types, is/as narrowing and exhaustive match with patterns.
sidebar:
  order: 6
---

```threebolt
struct Circle { let r: f64; }
struct Rect { let w: f64; let h: f64; }
type Shape = Circle | Rect;               // a union: no wrapper, no tags

type Direction = "left" | "right";        // literal types

fn area(s: Shape) -> f64 => match s {
    Circle { r } => 3.0 * r * r,
    Rect { w, h } => w * h,
};

fn describe(v: i32 | str | null) -> str {
    if v is null {
        return "nothing";
    }
    if v is str {
        return f"text of length {v.len}";  // v is narrowed to str here
    }
    return match v {                       // ...and to i32 here
        0 => "zero",
        n if n < 0 => "negative",
        1..=9 => "a digit",
        _ => "big",
    };
}

fn main() {
    println(area(Circle { r: 1.0 }));
    println(area(Rect { w: 2.0, h: 3.0 }));
    for v in [7, -1, 42] { println(describe(v)); }
    println(describe("hi"));
    println(describe(null));

    let d: Direction = "left";
    println(d.len);

    let xs = [1, 2, 3];
    if xs is [first, ..rest] {
        println(f"{first} then {rest}");
    }
}
```

```txt
3
6
a digit
negative
big
text of length 2
nothing
4
1 then [2, 3]
```

## Unions instead of enums-with-data

Where Rust uses `enum Shape { Circle(..), Rect(..) }`, Threebolt writes `Circle | Rect`. A
union value is just the value. It carries its runtime type, and there's no wrapper to unpack.
Unions are flattened and deduplicated (`A | (B | A)` is `A | B`), and aliases are transparent.
`T | null` is the language's optional type.

**Literal types** like `"left" | "right"` make lightweight enumerations that still behave like
`str`.

## Narrowing

`x is T` tests the runtime type, and the checker then **narrows** `x` wherever the test is known
to hold: in the `if` block, after an early `return`, and on the right of `&&`. `is not` and
`if let x = maybe` (non-null) narrow too. Narrowing applies to locals, parameters and `self`, but
not to fields, so bind a field to a local first.

`x as T` casts and panics on failure; `x as? T` yields `T | null` instead.

## `match`

`match` is an expression. Arms are tried in order, and the compiler checks **exhaustiveness**
over union members, enums, `bool` and literal unions. Patterns include:

| Pattern | Matches |
|---|---|
| `0`, `"x"`, `'c'` | a literal |
| `1..=9`, `> 0` | ranges and relational patterns |
| `a \| b` | either |
| `not p` | anything `p` doesn't match |
| `Rect { w, h }`, `Rect { w: 0.0, .. }` | a struct, with destructuring |
| `(a, _)`, `[first, ..rest]` | tuples and lists |
| `is str`, `s: str` | a type test, optionally binding |
| `n if n < 0` | a guard (doesn't count towards exhaustiveness) |

The same patterns work after `is` in an `if`: `if xs is [first, ..rest] { … }`.
