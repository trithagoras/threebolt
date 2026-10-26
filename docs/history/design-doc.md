# Threebolt Language Design

**Status:** Pre-compiler design  
**Purpose:** Working specification for language semantics and syntax before implementing the ANTLR4 grammar and compiler.

---

# 1. Language Overview

Threebolt is a statically typed, garbage-collected programming language with:

- Rust-inspired syntax
- C#-style classes and object-oriented programming
- value-type structs
- reference-type classes
- traits rather than interfaces
- generic types and generic functions
- TypeScript-style union types
- explicit `null`
- no exceptions
- result/error handling through ordinary union types
- opt-in mutability
- pattern matching
- closures/lambdas
- static and virtual dispatch
- automatic garbage collection

The language deliberately avoids Rust's ownership/borrowing/lifetime model. GC manages object lifetime; mutability and type safety are separate concerns.

A rough description:

> **Threebolt is a GC-managed, statically typed OO language combining Rust-like syntax and traits with C#-like classes and TypeScript-like union types.**

---

# 2. Design Philosophy

Threebolt should favor:

1. **Simple, explicit semantics**
2. **Compile-time checking where practical**
3. **Minimal special cases**
4. **Useful abstractions emerging from ordinary language features**
5. **Rust-like syntax without Rust's ownership complexity**
6. **C#-like object-oriented ergonomics**
7. **Union types as a fundamental type-system feature**

The language should not attempt to reproduce Rust semantics simply because the syntax resembles Rust.

In particular:

- No borrow checker
- No lifetimes
- No ownership system
- No `Box`
- No `Rc`/`Arc`
- No exceptions
- No ADT/tagged-union system
- No requirement for a special `Result<T, E>` language primitive

---

# 3. Mutability

Mutability is opt-in.

A binding declared with `let` is immutable.

A binding declared with `let mut` is mutable.

```rs
let x = 10;
x = 20;          // ERROR

let mut y = 10;
y = 20;          // OK
```

## 3.1 Fields

Fields independently specify whether they are mutable.

```rs
class Entity {
    let id: U64;
    let mut components: Map<Type, Component> = [];
}
```

`id` cannot be changed after initialization.

`components` may be changed when accessed through a mutable receiver.

This distinction is intentional.

## 3.2 Receiver mutability

Methods explicitly declare whether their receiver is mutable.

```rs
fn get_name(self) -> Str
```

The method cannot mutate the receiver.

```rs
fn set_name(mut self, name: Str)
```

The method may mutate the receiver.

This is analogous to a const/read-only method system, but with mutability being explicitly requested rather than read-only being explicitly requested.

## 3.3 Interaction between receiver and fields

Both conditions must be satisfied to mutate a field:

1. the receiver must be mutable;
2. the field must be mutable.

For example:

```rs
class Player {
    let name: Str;
    let mut health: I32;
}
```

Then:

```rs
fn damage(mut self) {
    self.health -= 10;    // OK
    self.name = "Bob";    // ERROR
}
```

A mutable receiver does not make immutable fields mutable.

## 3.4 Structs

Structs are value types and obey the same mutability rules.

```rs
struct Vec2 {
    x: F32,
    y: F32
}
```

```rs
let v = Vec2 { x: 1.0, y: 2.0 };

v.x = 3.0;       // ERROR
```

```rs
let mut v = Vec2 { x: 1.0, y: 2.0 };

v.x = 3.0;       // OK
```

Struct fields are currently considered mutable by default unless explicitly made immutable.

This point should be confirmed when the struct model is finalized; class fields definitely support explicit `let`/`let mut`.

---

# 4. Values and References

Threebolt has two fundamental aggregate categories.

## 4.1 Structs

Structs are value types.

```rs
struct Vec2 {
    x: F32,
    y: F32
}
```

Assignment/copying of a struct copies its value according to the compiler's value semantics.

Structs do not have private fields.

All struct fields are publicly accessible.

Functions/methods on structs may still be private.

## 4.2 Classes

Classes are reference types.

```rs
class Entity {
    let id: U64;
}
```

Instances are GC-managed objects.

Assigning a class value copies the reference rather than copying the object.

```rs
let a = Entity::new();
let b = a;
```

`a` and `b` refer to the same object.

Garbage collection manages the lifetime of the object.

Mutability is separate from lifetime.

An immutable reference/binding and mutable reference/binding may refer to the same object.

---

# 5. Classes

Classes support:

- fields
- constructors
- methods
- static fields
- static methods
- inheritance
- trait implementation
- virtual dispatch

Example:

```rs
class Entity : Updatable {
    static let mut glb_id = 0;

    let id: U64;
    let mut components: Map<Type, Component> = [];

    pub fn new() -> Self {
        Entity::glb_id += 1;
        return { id: Entity::glb_id };
    }
}
```

`Self` refers to the current contextual type.

---

# 6. Constructors

`new` is a conventional constructor function with special object-initialization semantics.

Example:

```rs
class Entity {
    let id: U64;
    let mut components: Map<Type, Component> = [];

    pub fn new() -> Self {
        return {
            id: 123
        };
    }
}
```

Fields with initializers are initialized automatically.

```rs
class EntityBuilder {
    let entity: Entity = Entity::new();
    let built: Bool = false;

    pub fn new() -> EntityBuilder {
        return {};
    }
}
```

`return {}` means that explicitly supplied fields are initialized by the object initializer and fields with default initializers use those initializers.

Initialization order and whether constructors can access partially initialized `self` remain to be specified.

---

# 7. Static Members

Static fields belong to the type rather than an instance.

```rs
class Entity {
    static let mut glb_id = 0;
}
```

Access:

```rs
Entity::glb_id += 1;
```

Static mutability follows the same rules:

```rs
static let x = 1;       // immutable
static let mut y = 2;   // mutable
```

Static methods are called using `Type::method(...)`.

---

# 8. Method Dispatch

Threebolt supports both **static dispatch** and **virtual dispatch**.

## 8.1 Static dispatch

Static dispatch is resolved at compile time.

Examples include:

- static methods
- generic functions
- methods where the concrete receiver type is statically known and the method is not virtual
- trait operations that are statically monomorphized/specialized

Example:

```rs
Entity::new();
```

The exact implementation is known at compile time.

## 8.2 Virtual dispatch

Classes may define virtual methods whose implementation is selected based on the runtime type of the object.

Example:

```rs
class Animal {
    pub virtual fn speak(self) -> Str {
        return "...";
    }
}

class Dog : Animal {
    pub override fn speak(self) -> Str {
        return "woof";
    }
}
```

Then:

```rs
let animal: Animal = Dog::new();
println(animal.speak());
```

uses virtual dispatch and calls `Dog.speak()`.

### Open question

The exact syntax and defaults need to be finalized.

Potential design:

```rs
fn foo(self)              // non-virtual
virtual fn foo(self)      // virtual
override fn foo(self)     // override
```

This is deliberately closer to C# than making every class method virtual by default.

The current preferred direction is:

> **Methods are statically dispatched by default; `virtual` explicitly opts into dynamic dispatch.**

This avoids hidden runtime dispatch and makes the cost/model explicit.

---

# 9. Traits

Traits are Threebolt's interface/behavior abstraction.

A trait defines required behavior but does not represent a class hierarchy.

```rs
trait Updatable {
    fn update(mut self);
}
```

A type can implement a trait directly in its declaration:

```rs
struct Position : Component, Updatable {
    ...
}
```

A trait can also be attached separately:

```rs
struct Position {
    ...
}

impl Updatable for Position {
    fn update(mut self) {
        ...
    }
}
```

These two forms are semantically equivalent.

The first is convenient when the relationship belongs naturally beside the type declaration.

The second is useful for attaching implementations separately.

## 9.1 Multiple traits

Types can implement multiple traits:

```rs
struct Position : Component, Updatable, Debuggable {
    ...
}
```

or:

```rs
impl Component for Position { ... }
impl Updatable for Position { ... }
impl Debuggable for Position { ... }
```

## 9.2 Trait inheritance

Not yet finalized.

Potential syntax:

```rs
trait Animal : Living {
    ...
}
```

This should probably mean that any implementation of `Animal` must also satisfy `Living`.

---

# 10. Abstract Types

The language has an `abstract` modifier.

Example:

```rs
abstract struct Component;
impl abstract Default for Component;
```

The exact semantics of abstract structs versus abstract classes remain to be finalized.

Current intended use is to represent a type that establishes a conceptual/base constraint without necessarily requiring instances of the type itself.

---

# 11. Generics

Threebolt supports generic types, functions, and methods.

Constraints use Rust-style syntax:

```rs
fn foo<T: Component>(value: T) { ... }
```

Multiple constraints are possible:

```rs
fn foo<T: Component + Default>(value: T) { ... }
```

or potentially comma-separated constraints if that proves preferable.

Generic methods:

```rs
pub fn add_component<T: Component>(mut self) -> () | Err
```

Generic types:

```rs
type Result<T> = T | Err;
```

Generic type inference should be supported where the compiler can determine the type safely.

---

# 12. Type Aliases and Union Types

Union types are a fundamental language feature.

```rs
type Direction = "left" | "right" | "up" | "down";
```

Unions can combine arbitrary compatible types.

```rs
type MaybeString = Str | null;
```

Union types do not create a new runtime wrapper type merely by being named.

For example:

```rs
type Result<T> = T | Err;
```

conceptually means:

```rs
Result<Entity>
```

is:

```rs
Entity | Err
```

rather than an object containing a hidden discriminant.

The compiler's type system tracks which union members are possible.

---

# 13. Nullability

`null` is explicit.

A nullable type can be expressed as:

```rs
T | null
```

There does not need to be a special `T?` syntax unless it is later decided to be useful shorthand.

Example:

```rs
fn find_player() -> Player | null
```

This is an ordinary union.

A nullable value can be tested:

```rs
if let player := find_player() {
    player.update();
}
```

The successful branch narrows the type from:

```text
Player | null
```

to:

```text
Player
```

---

# 14. Truthiness and Type Narrowing

Threebolt supports truthy conditional expressions.

Example:

```rs
if let x := self.components[typeof(T)] {
    return x as T;
}
```

The intended behavior is that the conditional checks whether the value represents a successful/non-null result.

The exact truthiness rules need to be formally specified.

Current preferred direction:

- `null` is false
- `Bool` uses its boolean value
- other values are generally true
- collections may or may not have empty-as-false semantics; this should be explicitly decided rather than inherited accidentally

Type narrowing should occur where the compiler can prove the relevant condition.

---

# 15. Casting and Type Tests

Threebolt supports runtime type testing:

```rs
x is Updatable
```

and casting:

```rs
x as T
```

Example:

```rs
.filter(c => c is Updatable)
```

and:

```rs
return x as T;
```

The exact failure semantics of `as` remain to be decided.

Potential choices:

1. `as` is a checked cast that produces `T | null`
2. `as` is a checked cast that can only appear after successful type narrowing
3. `as` is an asserting cast that traps/panics on failure

Preferred direction: **make checked casting explicit and avoid hidden exceptions.**

A possible model is:

```rs
x as T
```

produces:

```rs
T | null
```

while successful `is` checks narrow the value.

This needs to be finalized alongside runtime type semantics and generics.

---

# 16. Errors

Threebolt has no exceptions.

Operations that may fail should return an ordinary union type.

Example:

```rs
fn add_component<T: Component>(mut self, comp: T) -> () | Err
```

A standard library error type can be:

```rs
class Err {
    message: Str;
}
```

or a struct, depending on the final object model.

A convenience function can construct an error:

```rs
error("something went wrong")
```

which returns an `Err`.

Specific errors can potentially derive from or implement a common error abstraction:

```rs
class FileNotFound : Err {
    path: Str;
}
```

Then APIs can express specific failure sets using unions.

For example:

```rs
fn load_file(path: Str) -> File | FileNotFound | PermissionDenied
```

This is preferable to baking a single universal `Result<T, E>` mechanism into the language.

---

# 17. `?` Propagation

The `?` operator exists for short-circuiting operations that may produce a failure.

Example:

```rs
fn with<T: Component>(mut self) -> () | Err {
    entity.add_component<T>()?;
}
```

The exact semantics are **not yet finalized**.

The current design direction is to make `?` protocol-based rather than hard-coded specifically to `Result`.

A trait analogous in spirit to Rust's `Try` may eventually be used.

However, this should be designed separately and carefully.

Do not consider the `Try` contract settled yet.

---

# 18. Methods and Overloading

Method overloading is supported.

Example:

```rs
fn add_component<T: Component>(mut self) -> () | Err
fn add_component<T: Component>(mut self, comp: T) -> () | Err
```

The overload is selected based on the call's argument count and types.

The exact interaction between generic inference and overload resolution must be specified.

---

# 19. Expression Bodies

Functions can use a concise expression-body syntax:

```rs
fn foo() -> T
    => expression;
```

Example:

```rs
pub fn add_component<T: Component>(mut self) -> () | Err
    => self.add_component(T::default());
```

A block body remains available:

```rs
fn foo() -> T {
    ...
}
```

The expression body is equivalent to returning the expression.

---

# 20. Return Values

Functions explicitly declare their return type unless it can be inferred from context or the language's chosen inference rules.

`Self` is available as an alias for the contextual containing type.

```rs
fn new() -> Self
```

A bare `return;` is equivalent to returning `()` in a function whose return type permits it.

Implicit final-expression returns are currently **not** a language-wide rule; the preferred style is explicit `return` or the concise `=>` form.

This should be revisited if expression-oriented blocks become a larger language feature.

---

# 21. `()` Unit Type

`()` is the unit type.

A function that produces no useful value returns:

```rs
fn update(self) -> ()
```

It may be abbreviated if the language permits return-type omission for functions whose result is inferable:

```rs
fn update(self) {
    ...
}
```

The exact inference rules should be formalized.

---

# 22. Collections

Collections use generic types.

Examples:

```rs
Map<Type, Component>
[Entity]
```

Collection literals are supported.

```rs
let entities: [Entity] = [player];
```

Collection initializers can be used in class fields:

```rs
let mut components: Map<Type, Component> = [];
```

The syntax and semantics of collection literals versus array literals should be standardized.

---

# 23. Indexing and Assignment

Indexing can be used as an l-value when the collection supports mutation.

Example:

```rs
self.components[typeof(T)] := comp;
```

`:=` is intended as mutation/assignment rather than `=`.

Potential distinction:

```rs
let x = expression;
x := expression;
```

where:

- `=` is used for initialization
- `:=` is used for reassignment

This distinction should be made universal throughout the language.

Example:

```rs
let mut x = 10;
x := 20;
```

---

# 24. Pattern Matching

Threebolt supports `match`.

Example:

```rs
let name := match args {
    [name, ...] => name,
    [] => "player1"
};
```

Pattern matching does not require ADTs.

Patterns can match:

- literals
- arrays/collections
- variables
- potentially structs
- potentially unions
- rest patterns

Example:

```rs
match direction {
    "left" => ...
    "right" => ...
    "up" => ...
    "down" => ...
}
```

The compiler should perform exhaustiveness checking where possible.

---

# 25. Union Narrowing

Because Threebolt has union types, control flow should narrow unions.

Example:

```rs
type Direction = "left" | "right";

fn move(direction: Direction) {
    match direction {
        "left" => ...
        "right" => ...
    }
}
```

For class/type unions:

```rs
if value is Foo {
    // value is known to be Foo here
}
```

This should be integrated with the compiler's type-flow analysis.

---

# 26. Closures

Closures use concise lambda syntax.

Example:

```rs
c => c.update()
```

Parameters can potentially explicitly request mutability:

```rs
mut c => c.update()
```

A closure parameter follows the same mutability rules as a local binding.

The intended semantics are:

```rs
c => ...
```

means an immutable parameter.

```rs
mut c => ...
```

means a mutable parameter.

Therefore, if:

```rs
fn update(mut self)
```

requires a mutable receiver, then:

```rs
.for_each(mut c => c.update())
```

is valid, while:

```rs
.for_each(c => c.update())
```

is not.

Whether `filter` and `for_each` themselves pass mutable or immutable references/values depends on the collection API.

---

# 27. Example Component System

A representative Threebolt program:

```rs
struct Vec2 {
    x: F32,
    y: F32
}

class Entity : Updatable {
    static let mut glb_id = 0;

    let id: U64;
    let mut components: Map<Type, Component> = [];

    pub fn new() -> Self {
        Entity::glb_id += 1;
        return { id: Entity::glb_id };
    }

    pub fn maybe_get_component<T: Component>(self) -> T | null {
        if let x := self.components[typeof(T)] {
            return x as T;
        }

        return null;
    }

    pub fn add_component<T: Component>(mut self) -> () | Err
        => self.add_component(T::default());

    pub fn add_component<T: Component>(mut self, comp: T) -> () | Err {
        if (self.maybe_get_component<T>()) {
            return error(
                f"Entity {self.id} already contains component of type {nameof(T)}."
            );
        }

        self.components[typeof(T)] := comp;
    }

    pub fn update(mut self) {
        self.components.values
            .filter(c => c is Updatable)
            .for_each(mut c => c.update());
    }
}

abstract struct Component;
impl abstract Default for Component;

trait Updatable {
    fn update(mut self);
}

struct Position : Component, Updatable {
    x: F32,
    y: F32,

    pub fn default() -> Self {
        return { x: 0.0, y: 0.0 };
    }

    pub fn update(mut self) {
        // component/entity relationship still needs final design
    }
}

struct Velocity : Component {
    dx: F32,
    dy: F32,

    pub fn default() -> Self {
        return { dx: 0.0, dy: 0.0 };
    }
}

struct Details : Component {
    name: Str,

    pub fn default() -> Self {
        return { name: "UNNAMED" };
    }
}

class EntityBuilder {
    let entity: Entity = Entity::new();
    let mut built: Bool = false;

    pub fn new() -> Self {
        return {};
    }

    pub fn with<T: Component>(mut self) -> () | Err {
        entity.add_component<T>()?;
    }

    pub fn with<T: Component>(mut self, comp: T) -> () | Err {
        entity.add_component(comp)?;
    }

    pub fn build(mut self) -> Entity | Err {
        if built {
            return error(
                f"Entity {self.entity.id} has already been built. Builder must be discarded."
            );
        }

        built := true;
        return entity;
    }
}

type Direction = "left" | "right" | "up" | "down";

fn main(args: [Str]) {
    let name := match args {
        [name, ...] => name,
        [] => "player1"
    };

    println(f"Welcome, {name}!");

    let mut player := EntityBuilder::new()
        .with<Details>({ name })
        .with<Position>()
        .build()?;

    let mut entities: [Entity] = [player];

    loop {
        for mut e in entities {
            e.update();
        }
    }
}
```

This example is illustrative rather than a fully valid canonical program until the remaining semantic questions are resolved.

---

# 28. Things Deliberately NOT in Threebolt

The following are currently outside the language model:

### No exceptions

There is no:

```rs
try {}
catch {}
throw
```

Error conditions are represented by types.

### No Rust ownership

There is no:

```text
ownership
borrowing
lifetimes
move semantics
borrow checker
```

### No ADTs

There is no Rust-style:

```rs
enum Foo {
    A(...),
    B(...)
}
```

Union types are the primary sum-type mechanism.

### No interfaces

Traits are used instead.

### No implicit null

A non-nullable type does not implicitly accept `null`.

```rs
let x: Entity = null;       // ERROR
let y: Entity | null = null; // OK
```

### No implicit exception-based casts

Runtime failures should not silently become exceptions.

---

# 29. Current Open Design Questions

These should be handled in dedicated design passes rather than mixed together.

## High priority

### 29.1 Struct mutability

Exactly how struct field mutability works needs to be finalized.

Current likely model:

```rs
struct Vec2 {
    x: F32,
    y: F32
}
```

Fields are mutable when the struct itself is mutable.

For immutable fields:

```rs
struct Foo {
    let x: I32,
    y: I32
}
```

would potentially be supported.

### 29.2 Virtual dispatch syntax

Preferred:

```rs
virtual fn foo(self)
override fn foo(self)
```

with methods non-virtual by default.

Need to decide:

- Can structs have virtual methods?
- Can traits require virtual methods?
- Are trait calls dynamically dispatched?
- How is `super` represented?
- Can a virtual method be sealed/final?

### 29.3 Class inheritance

Need to establish:

```rs
class Dog : Animal
```

semantics.

Questions:

- single inheritance only?
- can a class inherit a struct? presumably no.
- can structs inherit? probably no.
- can classes implement multiple traits? yes.
- are fields inherited?
- are constructors inherited?
- is `super` available?

### 29.4 Trait dispatch

Need to distinguish:

```rs
T: Trait
```

static/generic dispatch from:

```rs
dyn Trait
```

or whatever Threebolt's equivalent is for runtime polymorphism.

This is particularly important because traits are not necessarily classes.

### 29.5 `is` / `as`

Need final semantics for runtime type tests and casts.

### 29.6 Initialization

Need to define:

- field initialization order
- constructor execution order
- base-class initialization
- whether constructors can fail
- whether `return` from `new` is special
- whether `{}` always invokes default field initializers

---

# 30. Medium-Priority Questions

### Type system

- structural versus nominal typing
- variance
- generic constraints
- type inference limits
- type aliases versus distinct types
- recursive types
- function types
- tuples
- anonymous structural types

### Operators

Need complete operator table and precedence:

```text
+
-
*
/
%
==
!=
<
>
<=
>=
&&
||
!
??
?
as
is
```

Also determine whether operators are overloadable.

### Visibility

Current direction:

```rs
pub
```

for public declarations.

Unmarked functions/methods are private.

Struct fields are public by default.

Need to decide class-field visibility defaults.

### Modules

Need syntax for:

```text
modules
imports
exports
packages
namespaces
```

### Metadata/reflection

Existing syntax suggests runtime type information:

```rs
typeof(T)
nameof(T)
```

Need to define what `Type` is and how reflection works.

---

# 31. Compiler Architecture

The language should be designed so that the compiler pipeline is approximately:

```text
Source
  ↓
Lexer
  ↓
ANTLR Parser
  ↓
AST
  ↓
Name Resolution
  ↓
Type Checking
  ↓
Type/Control-Flow Narrowing
  ↓
Generic Resolution / Monomorphization
  ↓
Dispatch Resolution
  ↓
Lowering
  ↓
IR
  ↓
Code Generation
```

ANTLR should primarily handle syntax.

Semantic rules such as:

- whether a field is mutable
- whether a method can mutate `self`
- whether a trait is implemented
- whether a union is exhaustive
- whether a `?` operation is legal
- whether an `as` cast is valid

belong in later compiler stages.

---

# 32. Recommended Design Order

Before writing the ANTLR grammar, settle the language in this order:

1. **Value/reference semantics**
2. **Mutability**
3. **Structs**
4. **Classes**
5. **Class inheritance**
6. **Methods and receivers**
7. **Static versus virtual dispatch**
8. **Traits**
9. **Generics**
10. **Union types**
11. **Nullability**
12. **Type narrowing**
13. **Error/result model**
14. **`?` / `Try`**
15. **Expressions and operators**
16. **Pattern matching**
17. **Closures**
18. **Collections**
19. **Modules/imports**
20. **Reflection/runtime type information**
21. **Only then: formal grammar**

---

# 33. Current Core Model

The current strongest version of the Threebolt model is:

```text
                    THREEBOLT
                        │
        ┌───────────────┼────────────────┐
        │               │                │
     Struct           Class            Trait
     value            reference        behavior
        │               │                │
     mutable          GC-managed       static/dynamic
     bindings         object            polymorphism
        │               │
        └─────── mutability ────────────┘
                    │
             let / let mut
                    │
             self / mut self
```

The type system is centered around:

```text
T
T | U
T | null
generic T
type aliases
traits
```

rather than a built-in `Option`/`Result` hierarchy.

The standard library can then build abstractions such as:

```rs
type Option<T> = T | null;
type Result<T> = T | Err;
```

if desired.

The language itself provides the primitives; the library builds the conventions.

---

# 34. Guiding Principle for Future Decisions

When deciding between competing designs, prefer the one that allows an existing Threebolt feature to express the behavior rather than introducing another special-case feature.

For example:

- nullable values → unions rather than a special `Nullable<T>`
- errors → unions rather than a built-in Result mechanism
- behavior → traits rather than interfaces
- mutation → `let mut` / `mut self` rather than a separate const/readonly system
- generic constraints → traits
- type aliases → ordinary aliases
- polymorphism → static/virtual dispatch and traits
- object lifetime → GC rather than ownership

The goal is for Threebolt's semantics to be **composable**.

---

# 35. Immediate Next Design Pass

The next discussion should focus on **classes, inheritance, traits, and dispatch**.

In particular, establish:

```rs
class Animal {
    virtual fn speak(self) -> Str;
}

class Dog : Animal {
    override fn speak(self) -> Str;
}
```

versus:

```rs
trait Speak {
    fn speak(self) -> Str;
}

class Dog : Speak {
    fn speak(self) -> Str;
}
```

and:

```rs
fn make_speaker<T: Speak>(x: T) -> T;
```

versus a runtime trait reference.

The key questions are:

1. What is statically dispatched?
2. What is virtually dispatched?
3. What does a trait value/reference look like?
4. Can a trait be used as a runtime type?
5. How do class inheritance and trait implementation interact?
6. How does `super` work?
7. What are the rules for overriding?
8. How does `mut self` behave through virtual/trait dispatch?

Those decisions will establish most of Threebolt's object model before grammar work begins.