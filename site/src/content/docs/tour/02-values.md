---
title: Values and types
description: Primitives, bindings and mut, strings, tuples, lists and nullable values.
sidebar:
  order: 2
---

```threebolt
fn main() {
    let answer = 42;              // i32: integer literals default to i32
    let big: u64 = 10_000_000;
    let ratio = 2.5;              // f64
    let ok = true;
    let letter = 'λ';
    let mut count = 0;            // bindings are immutable unless declared `mut`
    count += 1;

    let wide: i64 = answer;       // lossless widening is implicit
    let narrow = big as u8;       // anything else needs `as`
    println(f"{answer} {wide} {narrow} {ratio} {ok} {letter} {count}");

    let s = "héllo";
    println(f"{s.len} {s[1]} {s[1..4]}");   // lengths and indexes count characters

    let pair = (1, "one");
    println(f"{pair.0} is {pair.1}");

    let mut xs = [3, 1, 2];       // [i32], a growable list
    xs.push(4);
    let mut ys = xs;              // lists are references: ys and xs are one list
    ys.push(5);
    println(f"{xs.len} {xs[4]}");

    let maybe: i32 | null = null; // nullable is just a union with null
    println(f"{maybe ?? -1}");
}
```

```txt
42 42 128 2.5 true λ 1
5 é éll
1 is one
5 5
-1
```

## Primitives

`i8`…`i64`, `u8`…`u64`, `isize`/`usize`, `f32`/`f64`, `bool`, `char` (a Unicode scalar) and `str`.
Without an expected type, an integer literal is an `i32` and a float literal is an `f64`. Suffixes pick
a type explicitly: `10_u64`, `1.5_f32`.

Widening that can't lose information happens implicitly (`i32` → `i64`, `u8` → `u32`, `i32` → `f64`).
Any other conversion is written with `as`.

## Bindings and `mut`

`let` bindings are immutable. `let mut` lets you reassign the binding *and* call `mut self` methods
through it, like `push`. Note that `mut` is **shallow**. It says what you intend to do through
that binding; it doesn't freeze the object. Above, `xs` and `ys` are one list, so pushing through
`ys` changes what `xs` sees.

## Strings

`str` is immutable Unicode text. As in Python, every length, index and slice counts *characters*,
not bytes: `s.len`, `s[i]` (a `char`) and `s[a..b]` (a `str`). Use f-strings to build strings, and
for loops collect the parts and `join` them.

## Value types and reference types

Primitives, tuples, structs and fixed arrays (`[T; N]`) are **values**: assigning or passing them
makes a copy. Classes, `str`, lists (`[T]`), `Map`, `Set` and closures are **references**:
assigning them copies the reference. This is the C# model, not Rust's. There is no move and no
borrow, and the garbage collector frees what's no longer reachable.

## Null is a type

There is no `Option`. A value that may be missing has a union type such as `i32 | null`, and
`a ?? b` supplies a default. Unions get a whole chapter later; for now, read `T | null` as
"maybe a `T`".
