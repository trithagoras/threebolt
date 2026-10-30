---
title: Structs, classes and enums
description: Value-type structs, reference-type classes with single inheritance, initializers, visibility and enums.
sidebar:
  order: 4
---

```threebolt
struct Vec2 {
    let x: f64;
    let y: f64 = 0.0;

    pub fn len(self) -> f64 => (self.x * self.x + self.y * self.y).sqrt();
    pub fn scale(mut self, k: f64) { self.x *= k; self.y *= k; }
}

abstract class Animal {
    pub let name: str;

    prot fn new(name: str) -> Self => { name };
    pub abstract fn speak(self) -> str;
    pub fn describe(self) -> str => f"{self.name} says {self.speak()}";
}

class Dog : Animal {
    pub let breed: str;
    pub fn new(name: str, breed: str = "mutt") -> Self => { ..Animal::new(name), breed };
    pub override fn speak(self) -> str => "woof";
}

enum Color { Red, Green, Blue }

fn main() {
    let a = Vec2 { x: 3.0, y: 4.0 };
    let mut b = a;                 // structs copy
    b.scale(2.0);
    println(f"{a.len()} {b.len()}");

    let rex = Dog::new("Rex", breed: "collie");
    let pet: Animal = rex;         // classes share one object
    println(pet.describe());
    println(Dog::new("Fido").breed);

    let c = Color::Green;
    println(f"{c} = {c as i32}");
}
```

```txt
5 10
Rex says woof
mutt
Green = 1
```

## Structs are values

A `struct` is copied whenever it's assigned, passed or returned, so `b.scale(2.0)` leaves `a`
untouched. A method's receiver is `self` or `mut self`. A `mut self` method on a struct changes the
receiver in place, which is why `b` must be `let mut`. Structs can implement traits but can't
inherit.

## Classes are references

A `class` lives on the GC heap, and assigning one copies the reference. Classes get single
inheritance plus any number of traits (`class Dog : Animal, Display`). Methods are **non-virtual
by default**: opt in with `virtual` or `abstract`, and mark replacements with `override`, as in C#.
`super.method()` calls the base version.

## Construction

There are no constructors. `new` is just a static function by convention. An **initializer**
`{ field: value, … }` builds the value, `{ name }` is shorthand for `{ name: name }`, and
`..expr` spreads fields from another value. That's how `Dog::new` fills in `Animal`'s fields.
Parameters with a default value (`breed: str = "mutt"`) are **named-only** at the call site:
`Dog::new("Rex", breed: "collie")`.

## Visibility

Everything is **private to its module by default**, members included. Use `pub` for everywhere,
`internal` for the package and `prot` for subclasses.

## Getters

`pub get fn name(self) -> T => …` declares a property read without parentheses. That's how
`xs.len` works.

## Enums

Enums are C-style integer-backed values with no payloads. For data-carrying variants, Threebolt
uses unions of structs instead (next chapters). `@flags` enums get bitwise operators and `.has()`.
