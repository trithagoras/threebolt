---
title: Errors without exceptions
description: Errors are union values — the Err trait, error(), ? propagation, must-use, and panic.
sidebar:
  order: 7
---

Threebolt has no exceptions: no `try`, no `catch`, no `throw`. A function that can fail says so in
its return type, and the error is an ordinary value.

```threebolt
struct NotFound : Err {
    let key: str;
    pub fn message(self) -> str => f"no such key: {self.key}";
}

fn lookup(m: Map<str, i32>, key: str) -> i32 | NotFound {
    if let v = m.get(key) {
        return v;
    }
    return NotFound { key };
}

fn parse_positive(s: str) -> i32 | Err {
    let n = i32::parse(s);             // i32 | null
    if n is null {
        return error(f"not a number: {s}");
    }
    if n <= 0 {
        return error(f"{n} is not positive");
    }
    return n;
}

fn total(m: Map<str, i32>) -> i32 | Err {
    return lookup(m, "a")? + lookup(m, "b")?;
}

fn main() {
    let m = ["a": 1, "b": 2];
    println(total(m));
    match lookup(m, "zzz") {
        NotFound { key } => println(f"missing {key}"),
        v => println(v),
    }
    for s in ["12", "-3", "x"] {
        let r = parse_positive(s);
        if r is Err {
            println(f"error: {r.message()}");
        } else {
            println(r + 1);
        }
    }
    _ = parse_positive("7");           // discarding an error-typed value must be explicit
}
```

```txt
3
missing zzz
13
error: -3 is not positive
error: not a number: x
```

## `T | Err`

An error is any struct or class implementing `trait Err { fn message(self) -> str; }`. As a type,
`Err` is a trait reference that can hold *any* error, so `T | Err` is the everyday fallible
result. Most code declares `type Result<T> = T | Err;` and moves on. For a quick one-off error,
`error("message")` builds a `MsgErr`. Precise signatures like `i32 | NotFound` let callers
`match` on the specific error type.

## `?`

`expr?` unwraps: if the value is an error, the enclosing function returns it immediately (running
its `defer`s). Otherwise you get the value with the error types removed. When a type has no error
members, `?` propagates `null` instead. The compiler checks that whatever `?` might return fits the
function's return type.

## Must-use

Silently ignoring a value that might be an error is a compile error. Use `?`, handle it, or
discard it explicitly with `_ = expr;`.

## `main` and `panic`

`main` may return `() | Err`. A returned error prints its message to stderr and exits with code 1.
`panic("msg")` is for bugs, not failures: it aborts the thread, and `defer`s don't run. Out-of-range
indexing and integer division by zero panic too.
