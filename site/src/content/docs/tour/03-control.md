---
title: Functions and control flow
description: fn, expression bodies, if/else expressions, loops, truthiness, closures and defer.
sidebar:
  order: 3
---

```threebolt
fn square(x: i32) -> i32 {
    return x * x;
}

fn sign(x: i32) -> str => "negative" if x < 0 else "non-negative";

fn main() {
    println(square(7));
    println(sign(-3));

    for i in 0..3 {
        print(f"{i} ");
    }
    println("");

    let mut n = 10;
    while n > 1 {
        n = n / 2 if n % 2 == 0 else 3 * n + 1;
    }

    let words = ["bolt", "", "three"];
    for w in words {
        if w {                            // strings and collections are truthy when non-empty
            println(w);
        }
    }

    let add = (a: i32, b: i32) => a + b;  // a closure
    println(add(2, 3));

    let mut total = 0;
    loop {
        total += 1;
        if total == 4 { break; }
    }
    println(f"total {total}, n {n}");
}
```

```txt
49
negative
0 1 2 
bolt
three
5
total 4, n 1
```

## Functions

`fn name(param: Type) -> Ret { … }`. Leave out `-> Ret` and the function returns `()`. A body
written `=> expr` is shorthand for returning one expression. Functions can be overloaded by
parameter types.

## Blocks are statements

This is a deliberate difference from Rust: **blocks and `if` statements aren't expressions**, and
there's no implicit return from the last line. A conditional *value* is written Python-style:
`a if cond else b`. Assignment is a statement too.

## Loops

- `for x in iterable`, where ranges are `a..b` (exclusive) and `a..=b` (inclusive)
- `while cond`, `while let x = next_or_null()`
- `loop` with `break;` / `continue;`

## Truthiness

Conditions take a `bool`, a nullable value (`null` is false), or a type that implements `Truthy`.
In the standard library, strings and collections are falsy when empty. Numbers are **not** truthy,
so write `if n != 0`.

## Closures

`(a: i32, b: i32) => a + b` is a closure of type `fn(i32, i32) -> i32`. Closures capture variables
by reference, and each loop iteration gets a fresh binding.

## `defer`

`defer expr;` or `defer { … }` runs when the enclosing block exits by any path (normal exit,
`return`, `?`, `break`), with multiple defers running in reverse order. It's the tool for
cleaning up files and locks.
