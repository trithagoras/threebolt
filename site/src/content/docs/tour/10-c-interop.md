---
title: Calling C
description: extern "C" blocks, ABI types, Ptr<T>, unsafe, pinning GC memory and callbacks.
sidebar:
  order: 10
---

C interop is built in: declare the functions and call them. There are no bindings generators and
no build scripts.

```threebolt
extern "C" {
    fn abs(x: c::int) -> c::int;
    fn strlen(s: Ptr<c::char>) -> c::size_t;
    fn qsort(base: Ptr<i32>, n: c::size_t, size: c::size_t, cmp: extern fn(Ptr<i32>, Ptr<i32>) -> c::int);
}

/// A plain top-level fn converts to a C function pointer.
fn compare(a: Ptr<i32>, b: Ptr<i32>) -> c::int {
    let (x, y) = unsafe => (a.read(), b.read());
    return x - y;
}

fn main() {
    println(unsafe => abs(-5));

    let s = "threebolt".to_cstr();     // a malloc'd, NUL-terminated copy
    defer unsafe { c::free(s); }
    println(unsafe => strlen(s));

    let xs = [5, 3, 9, 1];
    xs.with_ptr(p => unsafe => qsort(p, xs.len, 4, compare));   // pinned while C uses it
    println(xs);
}
```

```txt
5
9
[1, 3, 5, 9]
```

## `extern "C"`

An `extern "C" { … }` block declares C functions. Put `@link("name")` on the block to load a
native library; without it, names resolve against libc and the process. Signatures may only use
**ABI types**: integers, floats, `bool`, `Ptr<T>`, enums, `extern fn` pointers and `@c struct`s
(C layout). `c::int`, `c::long`, `c::size_t` and friends are platform-correct aliases.

## `unsafe`

Extern functions and raw pointer operations need an unsafe context: an `unsafe { … }` block, the
expression form `unsafe => expr`, or the body of an `unsafe fn`. Inside one, misuse is undefined
behaviour. That's the third bolt.

## Pointers and GC memory

`Ptr<T>` is a raw, nullable address with `read`, `write`, `offset` and `cast`. Strings cross the
boundary by copying: `s.to_cstr()` (free it with `c::free`) and `str::from_cstr(p)`. To hand C a
list or an `@c struct` in place, `x.with_ptr(p => …)` pins it for the duration of the closure.

## Callbacks

A top-level, non-capturing `fn` with ABI-typed parameters converts to an `extern fn` pointer, as
`compare` does for `qsort`. Closures and methods can't be passed this way.
