---
title: Modules and packages
description: Files as modules, use and visibility, packages with package.toml, and bolt new / add.
sidebar:
  order: 8
---

Every `.3b` file is a module, named by its path from the package root:

```threebolt title="geometry/shapes.3b"
/// Visible to any module that imports it.
pub struct Point {
    let x: i32;
    let y: i32;
}

pub fn manhattan(a: Point, b: Point) -> i32 => (a.x - b.x).abs() + (a.y - b.y).abs();

fn sq(n: i32) -> i32 => n * n;   // private to this module

pub fn dist2(a: Point, b: Point) -> i32 => sq(a.x - b.x) + sq(a.y - b.y);
```

```threebolt title="main.3b"
use geometry::shapes::{Point, manhattan};
use std::math;

fn main() {
    let d = manhattan(Point { x: 0, y: 0 }, Point { x: 3, y: -4 });
    println(d);
    println(math::floor(2.7));
    println(std::math::round(2.5));      // a fully-qualified path needs no `use`
}
```

```txt
7
2
3
```

## `use`

`use a::b::Item;`, `use a::b::{X, Y};`, `use a::b;` (the module itself, then `b::X`),
`use … as Alias;` and `use a::b::*;`. A fully-qualified path like `std::math::round` works
without any `use`. Cyclic imports are fine, because resolution is whole-program. The prelude
(`println`, `error`, `Map`, `Err`, …) is always in scope.

## Visibility

Items are private to their module unless marked `pub` (everywhere) or `internal` (this package).
`pub use other::Thing;` re-exports.

## Packages

A package is a directory with a `package.toml`:

```toml
[package]
name = "game"

[dependencies]
shared = { path = "../shared" }
```

The dependency's modules then appear under its key: `use shared::model::User;`. Dependencies are
not transitive.

```sh
bolt new game            # a package with a hello-world bin
bolt new shared --lib    # a library
bolt add ../shared       # add it as a dependency
bolt run                 # check and run the package's bin
```

`impl`s anywhere in the program apply everywhere, which is why the **orphan rule** restricts
where they may be written. Only the application package may implement any trait for any type.
