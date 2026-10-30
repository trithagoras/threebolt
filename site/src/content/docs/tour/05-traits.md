---
title: Traits and generics
description: Traits as contracts, base lists and impl blocks, trait references, generic functions and comprehensions.
sidebar:
  order: 5
---

```threebolt
trait Shape {
    fn area(self) -> f64;
    fn describe(self) -> str => f"a shape of area {self.area()}";   // default body
}

struct Square : Shape {
    let side: f64;
    pub fn area(self) -> f64 => self.side * self.side;
}

class Circle {
    let r: f64;
    pub fn new(r: f64) -> Self => { r };
}

impl Shape for Circle {
    fn area(self) -> f64 => 3.0 * self.r * self.r;
    fn describe(self) -> str => "a roundish circle";
}

fn largest<T: Shape>(shapes: [T]) -> T | null {
    let mut best: T | null = null;
    for s in shapes {
        if let b = best {
            if s.area() > b.area() { best = s; }
        } else {
            best = s;
        }
    }
    return best;
}

fn main() {
    let shapes: [Shape] = [Square { side: 2.0 }, Circle::new(1.0)];  // trait references
    for s in shapes {
        println(s.describe());
    }
    let squares = [Square { side: 1.0 }, Square { side: 3.0 }];
    if let big = largest(squares) {
        println(big.side);
    }
    let doubled = [x * 2 for x in [1, 2, 3] if x != 2];
    println(doubled);
}
```

```txt
a shape of area 4
a roundish circle
3
[2, 6]
```

## Traits

A trait lists methods (some with default bodies), getters and static functions. It never has
fields. There are two ways to implement one:

- In the type's **base list**: `struct Square : Shape { … }`, with the methods in the body.
- In a separate **`impl Shape for Circle { … }`** block, which also works for types you didn't
  write (within the orphan rule; the application package may implement anything).

Trait methods are callable without importing the trait. Operators come from traits too: `+` is
`Add`, `==` is `Eq`, `<` is `Ord`, `[]` is `Index`, `in` is `Contains`.

## Trait references

A trait used as a type (`[Shape]`) is a **trait reference**: calls through it dispatch
dynamically. Putting a struct into one boxes a copy.

## Generics

`fn largest<T: Shape>(shapes: [T]) -> T | null` gives a type parameter a bound. Type arguments are
usually inferred. Generics are reified, so `T` still exists at runtime. Generic types are
**invariant**: a `[Square]` is not a `[Shape]`.

## Comprehensions

`[e for x in it if cond]` builds a list. The same form builds a `Set` or `Map`
(`[k: v for …]`) when the expected type asks for one.
