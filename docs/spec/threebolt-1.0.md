# Threebolt 1.0 — Language Reference

Normative technical reference for implementers (human or agent). Supersedes `docs/history/design-doc.md` and `docs/history/decisions-1.0.md`. Terse by design: every rule is meant to be implementable and testable. "Error" without qualification means compile-time error. "Panic" means the runtime abort defined in §13.

**Character:** statically typed, GC-managed, Rust-flavoured syntax, C#-flavoured semantics. Structs (values), classes (references, single inheritance), traits (behaviour + runtime polymorphism), flattened union types, explicit `null`, no exceptions, easy C interop. Hobbyist "three-bolt" ethos: prefer simple, slightly sharp semantics over safety machinery.

**Not in 1.0:** async; ownership/borrowing/lifetimes; exceptions; ADT enums with payloads; interfaces (traits instead); setters; method hiding; sealing; `Try` trait; blanket impls; `where` clauses; variance; full reflection; finalizers/destructors; labeled `break`; format specifiers; method references as values; static fields in generic types; package manager/dependencies.

---

## 1. Lexical structure

- Source is UTF-8. File extension `.3b`.
- Comments: `// line`, `/* block */` (nestable), `/// doc` (line, attaches to next item; exactly three slashes — `////` is an ordinary comment).
- Identifiers: `[A-Za-z_][A-Za-z0-9_]*`. `_` alone is the discard/wildcard token, not an identifier.
- Semicolons terminate statements. Whitespace is insignificant.

**Keywords:**
`abstract as break class const continue defer else enum extern false fn for if impl in is let loop match mut nameof null override prot pub return Self self static struct super trait true type typeof unsafe use virtual while`

After `.` (member access) or `::` (path segment) any keyword is accepted as a name (e.g. `x.type()`, `Ptr<T>::null()`).

**Contextual keyword:** `get` is special only directly before `fn` in a member declaration (§4.8); elsewhere it is an ordinary identifier (`fn get<T>()`, `map.get(k)`).

**Literals:**

| Kind | Forms |
|---|---|
| Integer | `123`, `1_000`, `0xFF`, `0o17`, `0b1010`, optional suffix `_i8 _i16 _i32 _i64 _isize _u8 _u16 _u32 _u64 _usize` (e.g. `10_u64`) |
| Float | `1.0`, `1e9`, `2.5e-3`, suffix `_f32 _f64` (`1.5_f32`; also on integer digits: `1_f32`). A float literal requires digits on both sides of `.` |
| bool | `true`, `false` |
| char | `'a'`, `'\n'`, `'\u{1F600}'` — a Unicode scalar value |
| str | `"..."` with escapes `\n \r \t \0 \\ \" \' \u{HEX}` (1–6 hex digits). May not contain a raw line break |
| Multi-line str | `"""..."""`: same escapes; may contain raw line breaks (each LF or CRLF is `\n`) and `"`/`""`; ends at the first `"""`. Content is taken verbatim — no indentation stripping, no leading-newline removal |
| F-string | `f"text {expr} more"` or `f"""..."""` (multi-line rules as above); `{{` / `}}` are literal braces, a lone `}` in the text is an error. `expr` is any expression, including string literals and nested f-strings (`f"{greet("bob")}"`); braces inside it must balance. Each interpolated value must implement `Display` (§16) |
| Null / unit | `null`, `()` |

- Numeric literal suffixes always start with `_`: `10u64` is an error. A literal followed directly by identifier characters (`1.0abc`, `0x`, `0b12`) is an error.
- Immediately after `.`, digits never form a float literal, so `t.0.1` is nested tuple access (`(t.0).1`).
- F-strings require lexer modes in ANTLR (push on `f"`, push/pop on `{`/`}`).

**Operators / punctuation:**
`+ - * / % & | ^ ~ << >> ! && || == != < > <= >= = += -= *= /= %= &= |= ^= <<= >>= ?? ? as? .. ..= ... => -> :: : . , ; @ ( ) [ ] { } < >`

`...` appears only in C variadic declarations (§14.1).

`as?` is a single token (no whitespace).

**Attributes:** `@name` or `@name(args)` before a declaration. 1.0 attributes: `@c`, `@flags`, `@link("lib")`. Unknown attributes are errors.

---

## 2. Program structure

### 2.1 Modules
- Every `.3b` file is a module. The **source root** is the directory containing the entry file. Module path = file path relative to root, `/` → `::`, extension dropped (`game/entity.3b` → `game::entity`).
- Standard library modules live under `std::`. The prelude (§16) is implicitly imported everywhere.
- A module contains only items: `use`, `fn`, `struct`, `class`, `trait`, `impl`, `enum`, `type`, `const`, `static let`, `extern` blocks.

### 2.2 `use`
```rs
use std::io::File;              // item
use game::component::{Component, Position};
use game::entity;               // module; then entity::Entity
use std::collections::Map as Dict;
```
- Only `pub` items may be imported from another module. Cyclic module imports are permitted (resolution is whole-program).
- Trait methods are callable on any implementing type without importing the trait (impls are global, §8.5).

### 2.3 Visibility
- Items and members are **private by default** (visible within the declaring module; for members, within the declaring type).
- `pub`: visible everywhere it can be imported/accessed.
- `prot`: class members only; visible to the declaring class and its subclasses.
- Struct fields are always public; writing `pub` on them is permitted and redundant.
- Trait method implementations are always public.

### 2.4 Entry point
- The entry module MUST declare `fn main()` or `fn main(args: [str])`, return type `()` or `() | E` where every non-`()` member implements `Error`.
- `args` excludes the program name.
- Returning an error: its `message()` is written to stderr, exit code 1. Normal return: exit code 0. `std::process::exit(code: i32)` terminates immediately; pending `defer`s do not run.

---

## 3. Types

### 3.1 Primitive types
`i8 i16 i32 i64 isize u8 u16 u32 u64 usize f32 f64 bool char str`

- Primitive names are lowercase and predeclared, not keywords; static members use paths as for any type (`i32::parse(s)`, `u64::MAX`, `str::from_cstr(p)`). Declaring a type with a primitive's name is an error. Nominal and stdlib types are PascalCase (`Type`, `Err`, `List`, `Ptr`, …).
- `str`: immutable UTF-8 text, reference semantics but immutable (identity unobservable). No integer indexing; `.chars()`, `.bytes()`, `.len` (bytes).
- `char`: Unicode scalar value (32-bit).
- `()`: unit, the empty tuple; sole value `()`.
- `null`: the null type; sole value `null`.
- `!`: the never type (type position only). Return type of diverging functions (`panic`, `process::exit`); assignable to every type; has no values.
- `Type`: runtime type descriptor (§15).

### 3.2 Type expressions
| Form | Meaning |
|---|---|
| `Name`, `Name<T, U>` | nominal type (struct, class, trait, enum, alias), possibly generic |
| `Self` | the enclosing struct/class/trait type |
| `T \| U` | union (§3.4) |
| `"left"`, `42`, `true` | literal types |
| `(T, U)` | tuple; `()` is unit. One-element tuple `(T,)` |
| `[T]` | growable list (stdlib `List<T>`, reference type) |
| `[T; N]` | fixed-size array, value type, `N` a `const` expression |
| `Map<K, V>`, `Set<T>` | stdlib collections (reference types) |
| `fn(A, B) -> R` | function/closure type; `-> R` omitted means `-> ()` |
| `extern fn(A) -> R` | C function pointer (§14) |
| `Ptr<T>` | raw pointer (§14) |
| `( T )` | grouping |

### 3.3 Categories
- **Value types:** primitives except `str`, `()`, `null`, tuples, structs, enums, `[T; N]`, `Ptr<T>`, `extern fn`. Assignment/passing copies.
- **Reference types:** classes, `str`, `[T]`, `Map`, `Set`, closures (`fn` types), trait references, boxed structs. Assignment/passing copies the reference.

### 3.4 Unions
- Unions are **flattened, unordered, deduplicated**: `A | (B | A)` ≡ `A | B`. `T | T` ≡ `T`.
- Type aliases are transparent: `type Result<T> = T | Err;` — `Result<Entity>` *is* `Entity | Err`.
- A union value carries its runtime type; there is no user-visible wrapper.
- Literal members are subsumed by their base type: `"a" | str` ≡ `str`.
- Methods, fields and getters cannot be accessed on a union-typed value; narrow first (§9). Unions participate in conditions (§6.6), `==` (§7.3), and `Hash` (when every member implements `Hash`) — nothing else.
- Recursive aliases are permitted when recursion passes through a nominal or collection type: `type Json = null | bool | f64 | str | [Json] | Map<str, Json>;`

### 3.5 Assignability (`S` to `T`)
`S` is assignable to `T` when any of:
1. `S` ≡ `T`.
2. `T` is a union and `S` is assignable to some member of `T`.
3. `S` is a union and every member of `S` is assignable to `T`.
4. `S` is a class and `T` is a base class (transitively).
5. `T` is a trait and `S` implements `T` (directly, via supertrait, or via base class). If `S` is a struct, the value is **boxed** (§5.5).
6. `S` is a literal type whose base type is assignable to `T`, or `T` is a literal type equal to the literal `S`.
7. Lossless numeric widening (§3.6).
8. A top-level non-capturing `fn` with ABI-compatible signature to an `extern fn` type (§14).

Generic types are **invariant**: `[Dog]` is not assignable to `[Animal]`. `fn` types are invariant in parameters and return.

### 3.6 Numeric conversions
- Implicit (lossless) widening: signed→wider signed, unsigned→wider unsigned, unsigned→strictly wider signed, `i8..i32`/`u8..u32`→`f64`, `i8 i16 u8 u16`→`f32`, `f32`→`f64`. `isize`/`usize` widen only to themselves and `i64`/`u64` respectively (platform independence).
- Everything else requires `as` (§7.9).
- Integer literals adopt the expected type if it is an integer or float type and the value fits (error if not). Float literals adopt an expected float type. With no expected type: integer → `i32`, float → `f64`.
- Inference widens literal types: `let d = "left";` has type `str`; `let d: Direction = "left";` has type `Direction`.

---

## 4. Declarations

### 4.1 Bindings
```rs
let x = 1;                 // immutable binding
let mut y: i64 = 2;        // mutable binding
let (a, mut b) = pair;     // irrefutable pattern
```
- Bindings MUST be initialized at declaration.
- Shadowing is permitted in the same and nested scopes.
- See §5 for what `mut` permits.

### 4.2 Constants and statics
```rs
const MAX: usize = 1024;               // compile-time evaluated
static let START: Instant = Instant::now();
static let mut COUNTER: u64 = 0;
```
- `const` initializers are constant expressions: literals, other `const`s, arithmetic/bitwise/comparison on them, enum members, `typeof`/`nameof` not permitted. `const` may be used in literal types, enum values, `[T; N]` sizes, array-repeat counts.
- `static let [mut]` may appear at module level or in a struct/class body. Initializers run **lazily on first access**, exactly once even under threads. Initialization cycles panic.
- Mutable statics are unsynchronized.
- Static fields are not permitted in generic types.

### 4.3 Functions
```rs
pub fn spawn(name: str, hp: i32 = 100, pos: Vec2 = Vec2::zero()) -> Entity { ... }
fn sq(x: f64) -> f64 => x * x;
fn log(msg: str) { println(msg); }   // return type ()
```
- Return type omitted ⇒ `()`. Return types are never inferred for named functions.
- **Expression body** `=> body;` is equivalent to `{ return body; }`. `body` is an expression or an assignment (§7.1); an assignment body is only valid when the return type is `()`.
- **Parameters:** `name: T` (immutable) or `mut name: T` (mutable binding local to the callee; does not affect callers).
- **Required parameters are positional-only. Parameters with a default value are named-only** and MUST follow all required parameters. Call: `spawn("orc", pos: p)`. Named arguments may appear in any order, after all positional arguments.
- Default expressions are evaluated at each call, in the callee's declaration scope, left to right, and may refer to earlier parameters.
- For virtual/trait calls, the default expressions of the **statically resolved** declaration are used (C# rule).
- Functions may be generic: `fn first<T>(xs: [T]) -> T | null`.

### 4.4 Overloading
- Functions/methods with the same name in the same scope form an overload set.
- Resolution:
  1. Viable candidates: positional argument count equals required parameter count; every named argument matches an optional parameter name; generic arguments inferable (§10).
  2. Rank by positional arguments: exact type match beats widening/upcast/boxing.
  3. Among equals, prefer the candidate requiring fewer defaulted parameters to be filled.
  4. Remaining tie ⇒ error. Overloads differing only in return type ⇒ error at declaration.
- Overloads differing only in optional parameters, such that a call with no named arguments is ambiguous, are an error at declaration.

### 4.5 Structs
```rs
pub struct Vec2 {
    let x: f32;
    let y: f32 = 0.0;
    static let ZERO: Vec2 = Vec2 { x: 0.0, y: 0.0 };

    pub fn len(self) -> f32 => (self.x * self.x + self.y * self.y).sqrt();
    pub fn normalize(mut self) { let l = self.len(); self.x /= l; self.y /= l; }
}
pub struct Done;            // unit struct
```
- Value type. **Fields** (structs and classes): `let name: T [= default];`, optionally prefixed by visibility (`pub let id: u64;`). Fields carry no `mut`: every field is writable iff the path to it is mutable (§5.1). There are no init-only/readonly fields in 1.0; for "readable outside, not writable", use a private field plus a `pub get fn` (§4.8).
- No inheritance. May implement traits (`struct P : Component, Updatable`). No `virtual`/`abstract`/`override`/`prot`.
- A method without a `self` parameter is static (`Vec2::zero()`).
- Struct literals `Vec2 { x: 1.0 }` may appear anywhere; fields without defaults must be supplied.
- Structs automatically implement `Eq` (field-wise) if all fields implement `Eq`, and `Hash` if all fields implement `Hash`. An explicit impl overrides the automatic one.

### 4.6 Classes
```rs
pub abstract class Animal : Describe {
    pub let name: str;
    prot let legs: i32 = 4;

    prot fn new(name: str) -> Self => { name };
    pub abstract fn speak(self) -> str;
    pub virtual fn walk(self) -> str => f"{self.name} walks on {self.legs} legs";
    pub fn describe(self) -> str => f"{self.name} says {self.speak()}";
}

pub class Dog : Animal {
    let breed: str;
    pub fn new(name: str, breed: str = "mutt") -> Self => { ..Animal::new(name), breed };
    pub override fn speak(self) -> str => "woof";
    pub override fn walk(self) -> str => super.walk() + " happily";
}
```
- Reference type, GC-managed. Base list: at most one class, which MUST be first, followed by traits.
- Instance fields, instance methods and static fields are inherited. **Static functions are not inherited**; `Dog::new` and `Animal::new` are unrelated.
- Methods are **non-virtual by default**. `virtual` opts in; `abstract fn` (no body, only in `abstract class`) is implicitly virtual; `override` is required to override and MUST match the overridden signature exactly (parameter types, receiver `self`/`mut self`, return type, optional-parameter names and types). `override` may override an `abstract fn` or `virtual fn` (or another `override`).
- Declaring an instance method in a subclass with the same name and parameter types as a non-virtual inherited method ⇒ error (no hiding). Different parameter types ⇒ overload.
- `super.method(args)` calls the base class implementation; only valid inside an instance method of a subclass.
- A concrete (non-abstract) class MUST override every inherited `abstract fn`.
- A class may not list a trait already implemented by a base class.

### 4.7 Object initializers and construction
- `new` is an ordinary static function by convention; no special semantics. There is no `self` in static functions, so partially-initialized objects are unobservable.
- An **initializer** `{ field: expr, field2, ..spread }` creates a struct/class value. Named form `Type { ... }`; target-typed form `{ ... }` only where the expected type is known (e.g. after `return`/`=>` in a function returning `Self`, typed `let`, argument position).
- Field shorthand: `{ name }` ≡ `{ name: name }`.
- **Class initializers** may only appear within the class's own body.
- Every field without a default MUST be supplied, either explicitly or by a spread. Inherited fields not visible to the subclass (private in the base) can only be supplied by spread or their default.
- **Spread** `..expr`: copies every field of `expr`'s static type into the new value. For structs, `expr` is the same struct type (struct update). For classes, `expr`'s static type is the class or one of its base classes; the spread supplies the inherited fields. Explicit fields override spread fields regardless of order. At most one spread.
- Evaluation order: spread expression, then explicit fields in source order, then defaults for remaining fields in declaration order. Default initializers cannot refer to `self` or other fields.
- **Abstract classes** cannot be instantiated except by initializers inside their own body (typically a `prot fn new`), whose result exists to be spread into a subclass initializer. Calling an `abstract fn` on such an instance panics.

### 4.8 Properties
```rs
pub get fn len(self) -> usize => self.count;
```
- `get` is a contextual keyword (§1): `get fn` declares a getter: no parameters besides `self` (never `mut self`), non-`()` return. Accessed without parentheses: `xs.len`.
- A getter may not share a name with a field of the same type (convention: private backing field `_name`, getter `name`).
- Getters may be `virtual`/`override`/`abstract`, and may be declared in traits. No setters in 1.0; a getter is not an assignable place.

### 4.9 Traits
```rs
pub trait Describe {
    fn describe(self) -> str;                          // required
    fn shout(self) -> str => self.describe() + "!";    // default body
}
pub trait Component : Default {}                       // supertrait
pub trait Default { fn default() -> Self; }            // static requirement
```
- Members: instance methods (`self`/`mut self`), getters, static functions. No fields.
- `trait A : B + C` — implementors of `A` must implement `B` and `C`; a reference of type `A` may call `B` and `C` instance members.
- Traits may be generic: `trait Iterator<T>`. No associated types.
- Implementations match trait signatures exactly (receiver mutability included). Default bodies may be replaced by the implementor.

### 4.10 `impl`
```rs
impl Describe for Vec2 { fn describe(self) -> str => f"({self.x}, {self.y})"; }
impl<T: Display> Display for [T] { ... }
```
- `struct S : T` / `class C : T` with methods in the body ≡ a separate `impl T for S { ... }`; both may be mixed for different traits but each (trait, type) pair is implemented exactly once program-wide.
- `impl` may appear in any module, for any trait and any type (no orphan rule). Two impls of the same (trait, type-constructor) pair anywhere in the program ⇒ error.
- Generic impls are allowed for a generic type constructor (`impl<T: Eq> Eq for [T]`). Blanket impls over a bare type parameter (`impl<T: X> Y for T`) are not permitted in 1.0.
- `impl` blocks contain only implementations of that trait's members.

### 4.11 Enums
```rs
pub enum Color { Red, Green, Blue }                          // backing i32: 0,1,2
pub enum Signal : c::int { Int = 2, Kill = 9, Term = 15 }
@flags pub enum Perm : u8 { None = 0, Read, Write, Exec }    // 0,1,2,4
```
- Distinct nominal value type backed by an integer type (default `i32`). No payloads.
- Unassigned member values: plain enums — previous + 1 (first = 0). `@flags` — the next power of two greater than the previous value (first unassigned = 1). Values are `const` expressions and must fit the backing type.
- Members: `Color::Red`. Enums implement `Eq`, `Ord` (by value), `Hash`, `Display` (member name; flags combinations as `Read | Write`; undeclared bits as the integer).
- `e as i32` (any integer type) yields the value. `n as Color` panics if `n` is not a declared value; `n as? Color` yields `Color | null`.
- `@flags` enums: `| & ^ ~` and `|= &= ^=` defined; `.has(flag) -> bool`; every bit pattern is a valid value (so `n as Perm` never panics, and `match` on a flags enum requires `_`).
- Non-flags enum `match` is exhaustive over declared members.

### 4.12 Type aliases
`type Name<T> = TypeExpr;` — transparent (no new type). Aliases may be generic and recursive per §3.4.

---

## 5. Mutability and value semantics

### 5.1 Places
A **place** is a local binding, parameter, `self`, a field access on a place or reference, an index expression, or a static. A place is **mutable** iff:
- binding/parameter/static: declared `mut`;
- `self`: receiver declared `mut self`;
- field `p.f`: `p` is a mutable place (whether `p` has value or reference type). E.g. inside a `mut self` method, `self.hp = 0` and `self.entity.add(c)` (a `mut self` method on a field) are both permitted.
- index `c[i]`: `c` is a place (or reference) whose type implements `IndexMut`; if `c` has value type, `c` must be a mutable place.

### 5.2 What `mut` permits
- **Assignment** (`=`, compound) requires a mutable place.
- **Calling a `mut self` method** requires the receiver expression to be a mutable place **or a temporary** (non-place, e.g. a call result — enables builder chaining). Receivers reached through a reference-typed immutable binding are not mutable: `let e = Entity::new(); e.add(c);` ⇒ error.
- Mutability is **shallow** and expresses intent, not a guarantee: `let a = Entity::new(); let mut b = a; b.add(c);` is legal and mutates the object `a` refers to.
- Inside a `mut self` method, `self` is a mutable place; inside a `self` method it is not (so neither fields of `self` nor `mut self` methods on `self` are usable mutably; fields holding references may still have non-`mut` methods called).

### 5.3 Structs (values)
- Assigning, passing, returning, capturing by closure value, and storing a struct copies it.
- A `mut self` method on a struct operates **in place** on the receiver place.
- **Read-modify-write through indexers:** for `c[i].f = v` or `c[i].m()` (`m` a `mut self` method) where the element type is a value type, the compiler emits `let mut t = c[i]; t.f = v; c[i] = t;` (resp. `t.m()`), requiring `c` to implement `Index` and `IndexMut`. Applies recursively to nested value-type paths.
- Getters return copies; `obj.getter.f = v` ⇒ error.
- `for mut x in xs` over value-type elements binds copies; mutations do not affect `xs`.

### 5.4 Classes (references)
- Assignment copies the reference. Identity is preserved; `==` is reference equality unless `Eq` is implemented.

### 5.5 Boxing
- Converting a struct value to a trait type (§3.5 rule 5) copies it into a GC-allocated box. The trait reference refers to the box.
- `mut self` calls through the trait reference mutate the box in place.
- `ref as S` / `ref as? S` / `p is S` narrowing produce a **copy** of the boxed struct.
- Boxes have reference identity.

---

## 6. Statements and control flow

### 6.1 Statement forms
`let` declaration · assignment · expression statement · block `{ ... }` · `if` · `while` · `loop` · `for` · `match` (as statement) · `break;` · `continue;` · `return [expr];` · `defer` · `unsafe { ... }`

- A `{` at the start of a statement always begins a block. Blocks create scopes. Blocks are not expressions.
- Assignment is a statement, not an expression (except as an `=>` body, §7.1).
- Expression statements: any expression. Discarding a value whose type contains a member implementing `Error` ⇒ error; use `_ = expr;`.

### 6.2 Assignment
`place = expr;` and `place op= expr;` for `+ - * / % & | ^ << >>`. `place op= e` evaluates `place` once. `_ = expr;` evaluates and discards.

### 6.3 `if`
```rs
if cond { ... } else if cond2 { ... } else { ... }
if let x = expr { ... } else { ... }
if let mut x = expr { ... }
```
- `cond` follows §6.6. No parentheses required; a struct/class/collection initializer may not appear directly in a condition, `while` head, `for` iterable, or `match` scrutinee unless parenthesized.
- `if let x = e`: evaluates `e` (type `T | null`); if non-null, binds `x: T` (narrowed) in the then-block. Never consults `Truthy`. `e`'s type must include `null` ⇒ else error.

### 6.4 Loops
```rs
loop { ... }
while cond { ... }
while let x = it_next_or_null() { ... }
for x in iterable { ... }
for mut x in iterable { ... }
for (k, v) in map { ... }
for i in 0..n { ... }
```
- `for p in e`: `e` must implement `Iterable<T>` (calls `e.iter()`) or `Iterator<T>`; loops until `next()` returns `Done`. `p` is an irrefutable pattern.
- `break;` / `continue;` affect the innermost loop. Loops are not expressions.

### 6.5 `defer`
```rs
defer expr;
defer { ... }
```
- Registers code to run when the **enclosing block** exits by any path (fall-through, `return`, `?` propagation, `break`, `continue`). Multiple defers run in reverse registration order. Captures variables by reference (sees final values).
- Does not run on panic or `process::exit`. A deferred block may not contain `return`, `break`, `continue`, or `?` propagating out of it.

### 6.6 Conditions and `Truthy`
- `if`/`while` conditions and operands of `! && ||` must have a type that is `bool`, implements `Truthy`, or is a union of `null` and types that are `bool` or implement `Truthy`.
- Evaluation: `null` → false; `bool` → itself; otherwise `x.truthy()`.
- stdlib implements `Truthy` for `str` and all collections (empty ⇒ false). **Not** for numeric types, chars, or enums.
- Narrowing on truthy test: in the true branch of `if x`, `null` is removed from `x`'s type.

---

## 7. Expressions

### 7.1 `=>` bodies
Function expression bodies, lambda bodies, `match` arms, and `unsafe =>` accept an **expression**, or (lambda, match arm, `()`-returning fn) an **assignment** whose value is `()`, or (lambda, match arm) a **block**. A block yields no value: as a lambda body it requires `return` for non-`()` results; as a match arm in a value-producing `match` it must diverge (`return`/`break`/`continue`/panic).

### 7.2 Precedence (highest first)

| # | Operators | Assoc |
|---|---|---|
| 1 | postfix: `.member` `(args)` `[index]` `<TypeArgs>` `?` | left |
| 2 | prefix: `-` `!` `~` | right |
| 3 | `as T` `as? T` | left |
| 4 | `* / %` | left |
| 5 | `+ -` | left |
| 6 | `<< >>` | left |
| 7 | `&` | left |
| 8 | `^` | left |
| 9 | `\|` | left |
| 10 | `..` `..=` | none |
| 11 | `== != < > <= >=` `is` | none (no chaining) |
| 12 | `&&` | left |
| 13 | `\|\|` | left |
| 14 | `??` | right |
| 15 | `a if c else b` | right |
| 16 | lambda `params => body`, `unsafe => expr` | right |

Bitwise binds tighter than comparison (no C precedence trap).

### 7.3 Operators
- Arithmetic `+ - * / %` on same-typed numerics after widening. Integer `/` and `%` by zero panic. Overflow per §13.2. `+` also concatenates `str + str`.
- Bitwise `& | ^ ~ << >>` on integers and `@flags` enums. Shift amount ≥ bit width panics in checked mode.
- `==`/`!=`: any two types where one is assignable to the other. Requires `Eq` (structs: automatic field-wise; classes: reference identity unless `Eq` implemented; unions: compare runtime type, then value). Comparison with `null` is always permitted on types containing `null`.
- `< > <= >=`: requires `Ord`. Built-in for numerics, `char`, `str` (byte-lexicographic), enums.
- `&& || !`: short-circuit, operands per §6.6, result `bool`.
- `a ?? b`: if `a` is `null`, evaluates `b`. Type: `(A minus null) | B`. Only null is defaulted (errors are not).
- `x is T`: runtime test, `bool`. `T` may be any type including traits, literal types and unions (true if any member matches). Narrows (§9).
- Ranges `a..b` (half-open), `a..=b` (inclusive): integer or `char` operands; type `Range<T>` (implements `Iterable<T>`).
- **Overloadable** via traits (§16): `+ - * / %` unary `-`, `== !=` (`Eq`), `< > <= >=` (`Ord`), `[]` (`Index`, `IndexMut`), `& | ^ ~ << >>`. Compound assignment uses the binary operator. **Not** overloadable: `&& || ! ?? ? is as as? ..`, assignment.

### 7.4 Conditional expression
`a if cond else b` — evaluates `cond` first, then exactly one branch. Type: `A | B`. `cond` per §6.6. In `a`, narrowing from `cond` applies; in `b`, the negated narrowing applies.

### 7.5 Calls, members, generic arguments
- `f(a, b, opt: c)`; methods `x.m(args)`; static `Type::f(args)`; module `mod::f(args)`; generic `f<T>(args)`, `Type<T>::f()`, `T::default()` (T a type parameter bounded by a trait declaring `default`).
- **Generic-argument disambiguation:** in expression position, after an identifier or `::` path segment, `<` begins a type-argument list iff the tokens up to the matching `>` parse as a type-argument list **and** the next token is `(` or `::`. Otherwise it is less-than. `>>` is split into two `>` inside type-argument lists. Consequence: `f(a < b, c > (d))` parses as a generic call; parenthesize to force comparisons.
- Every value has built-in method `.type() -> Type`.

### 7.6 Indexing
`c[i]` calls `Index::index`; as an assignment target, `IndexMut::set`. `[T]` panics on out-of-range indices; `Map[k]` panics on missing keys (use `.get(k) -> V | null`).

### 7.7 Literals
- Tuple `(a, b)`; access `.0`, `.1`.
- Collection `[a, b, c]`, repeat `[v; n]`, map `[k: v, k2: v2]`, empty map `[:]`. **Target-typed:** `[...]` builds `[T]`, `Set<T>`, or `[T; N]` according to the expected type (default `[T]`); `[]` is valid for any expected collection type, error without one. `[k: v]` builds `Map<K, V>`.
- Initializers per §4.7.

### 7.8 Lambdas
```rs
x => x + 1
(a, b) => a + b
(x: i32, y: i32) => x * y
mut c => c.update()
x => { let y = x * 2; return y; }
() => work()
```
- Parameter types inferred from the expected `fn` type; else must be annotated. Return type inferred from body.
- Captures enclosing variables **by reference** (shared, live; mutating a captured `let mut` is visible outside). Lambdas are reference-type values of `fn(...) -> R` type.
- `?` inside a lambda propagates out of the lambda, not the enclosing function.
- A named function may be used as a `fn` value by name. Methods may not (use a lambda).

### 7.9 Casts
- `x as T`:
  - numeric → numeric: truncation/wrap for integers, IEEE conversion for floats, float→int truncates toward zero and saturates (NaN → 0). Never panics.
  - integer ↔ enum: §4.11.
  - downcast/trait/union narrowing: checked at runtime, **panics** on failure. Struct from box yields a copy.
  - upcast: always succeeds (redundant but permitted).
- `x as? T`: same checks for non-numeric conversions, yielding `T | null` instead of panicking. If `T` already contains `null`, a null `x` is indistinguishable from failure.
- A cast that can never succeed statically ⇒ error.

### 7.10 `?` propagation
- Postfix `e?` where `e: U`. Let `F` = members of `U` that are `null` or implement `Error`. If `F` is empty ⇒ error.
- At runtime, if the value's type is in `F`, the enclosing function (or lambda) returns it immediately (running defers). Otherwise the expression yields the value, typed `U minus F`.
- Every member of `F` must be assignable to the enclosing return type ⇒ else error.

### 7.11 `match`
```rs
let label = match value {
    0 => "zero",
    1 | 2 => "small",
    n if n < 0 => "negative",
    _ => "big",
};
```
- Expression; arms `pattern [if guard] => body`, comma-separated (trailing comma allowed; a block arm needs no comma). First matching arm wins.
- Type: union of arm result types (diverging arms contribute nothing).
- Exhaustiveness (error if not exhaustive): over `bool`, literal unions, non-flags enums, union members (by type), `null`, list patterns (lengths). Guarded arms do not count toward exhaustiveness. Otherwise a `_` or binding catch-all is required.

### 7.12 `unsafe` expression
`unsafe => expr` — evaluates `expr` in an unsafe context (§14.6).

### 7.13 Evaluation order
Left to right: receiver, then arguments in source order (positional, then named in source order, then defaults). Binary operands left then right. Assignment: place subexpressions, then right-hand side.

---

## 8. Dispatch

### 8.1 Static dispatch
Static functions; non-virtual methods on a statically known type; generic calls `T: Trait` (monomorphization vs. erased dispatch is an implementation choice — semantics identical).

### 8.2 Virtual dispatch
A call to a `virtual`/`abstract`/`override` method through a class reference selects the most-derived override of the runtime class.

### 8.3 Trait references
- A trait name used as a type (`x: Speak`, `Map<Type, Component>`) is a **trait reference**: runtime polymorphism, dynamic dispatch on every call (regardless of `virtual`).
- For a class, the trait method resolves through the class's implementation; if that implementation is virtual, the most-derived override runs.
- Static trait functions are not callable through trait references.
- Generic methods are callable on trait references (generics are reified, §10).

### 8.4 Receivers through dispatch
Receiver mutability rules (§5.2) apply to the static type at the call site regardless of dispatch kind.

### 8.5 Method lookup
For `x.m(...)`: inherent methods of `x`'s static type (and base classes), then methods of all traits it implements (program-wide). Same name from two traits with identical signatures and no inherent method ⇒ ambiguity error; disambiguate with `Trait::m(x, ...)`.

---

## 9. Narrowing

- Applies to: locals, parameters, and `self`. Not to fields (always potentially mutable through aliases), statics, indexers, or getter results — bind first: `if let p = self.parent { p.x }`.
- Narrowing sources: `x is T` (true branch → `x: T` if `T` is a member or subtype of `x`'s type, else narrowed to `T` itself; false branch removes `T`'s members from a union), `x == null` / `x != null`, `x == literal` (literal unions), truthy test (§6.6), `if let`, match arms, `&&`/`||` composition, `!`.
- **Flow sensitivity:** narrowing persists after an `if` whose branch diverges (`return`, `break`, `continue`, panic): `if x == null { return; } x.use();`.
- Assignment to a narrowed `let mut` local resets its type to the assigned value's type.
- Inside a lambda, captured `let mut` variables are not narrowed.
- Narrowing by `is TraitName` on a reference yields a trait reference to the same object (no intersection types).

---

## 10. Generics

- Generic parameters on functions, methods, structs, classes, traits, aliases: `<T, U: Bound + Bound2>`.
- **Reified:** `typeof(T)`, `x is T`, `x as T` work for type parameters at runtime.
- **Invariant** type arguments.
- **Inference:** type arguments are inferred from argument types (unification against parameter types, including lambda parameter/return types where determinable). Expected-type (return-position) inference is not performed. Uninferable ⇒ explicit type arguments required.
- A bound `T: Trait` permits calling `Trait`'s instance members on `T` values and its static functions via `T::f()`.
- Unbounded `T` permits only: assignment, passing, `== null` / `!= null`, `is`, `as`, `as?`, `.type()`.

---

## 11. Patterns

| Pattern | Matches |
|---|---|
| `_` | anything, no binding |
| `name`, `mut name` | anything, binds (narrowed to the remaining type) |
| literal (`1`, `"a"`, `'c'`, `true`, `null`) | equal value |
| `a..=b`, `a..b` | integer/char in range |
| `p1 \| p2` | either; bindings must be identical in both |
| `(p1, p2)` | tuple |
| `[p1, p2]`, `[first, ..]`, `[a, ..rest, z]` | list/array by length and elements; `..rest` binds a `[T]` slice copy; one rest per pattern |
| `Type { f1, f2: p, .. }` | struct (or class, accessible fields) of `Type`, fields matched; `..` ignores others |
| `name is T`, `is T` | runtime type test, binds narrowed value |
| `Enum::Member` | enum value |
| `pattern if guard` | (arms only) pattern + bool/Truthy guard |

- `let` and `for` require irrefutable patterns (tuples, struct destructure, bindings, `_`).

---

## 12. Errors

- `trait Error { fn message(self) -> str; }`.
- stdlib `pub class Err : Error` with `error(msg: str) -> Err` prelude function. User errors: any struct or class implementing `Error` (or subclassing `Err`).
- Fallible functions return unions: `fn load(p: str) -> File | NotFound | Err`.
- Propagation `?` (§7.10); must-use (§6.1); default `??` handles only null.
- No exceptions: no `try`/`catch`/`throw`.

---

## 13. Runtime semantics

### 13.1 Panic
- `panic(msg: str) -> !` (diverges). Causes: explicit `panic`, failed `as`, index out of range, missing map key via `[]`, integer divide/modulo by zero, checked-mode overflow, abstract method on abstract instance, static init cycle, `unsafe` misuse detected by the runtime.
- Effect: message + stack trace to stderr, process exits with code 101. Not catchable. `defer`s do not run. A panic on any thread terminates the process.

### 13.2 Integer overflow
- Overflow of `+ - *` (and unary `-`, `<<` out-of-range shifts) is a program bug. In **checked mode** (default for debug builds and for interpreters unless configured) it panics. In **release mode** it wraps (two's complement).
- Explicit: `.wrapping_add(x)`, `.checked_add(x) -> T | null`, `.saturating_add(x)` (and `sub`, `mul`, `neg`, `shl`, `shr`).

### 13.3 Garbage collection
- Tracing GC; all class instances, boxes, strings, collections, closures are GC-managed. No destructors or finalizers; use `defer` for deterministic cleanup.
- Implementations may move objects; see §14.5 for pinning.

### 13.4 Threads and memory model
- `std::thread::Thread::spawn<R>(f: fn() -> R) -> Thread<R>`; `t.join() -> R`.
- `std::sync::Mutex<T>`: `Mutex::new(v)`, `m.lock<R>(f: fn(Guard<T>) -> R) -> R` where `Guard<T>` is a class with `pub let value: T` valid only during `f`.
- `std::sync::Atomic<T>` for integer and `bool` `T`: `load`, `store`, `swap`, `compare_exchange`, `fetch_add`, etc. (sequentially consistent).
- Data races do not corrupt memory or produce invalid references; a racy read returns some previously written value, but value types wider than a machine word may tear. Implementations may use a global interpreter lock.

### 13.5 Initialization order
- Module-level and type-level statics: lazy on first access (§4.2). No module initializers otherwise; `main` is the only entry.

---

## 14. C interop

### 14.1 Extern blocks
```rs
@link("m")
extern "C" {
    fn sqrt(x: f64) -> f64;
    fn printf(fmt: Ptr<c::char>, ...) -> c::int;
}
```
- Only the `"C"` ABI. `@link("name")` names a native library (platform naming applied). Functions without `@link` resolve against the process/libc.
- `...` declares a C variadic; variadic arguments must be ABI types (f32 promoted to f64, small integers to `c::int`).
- Extern functions are implicitly `unsafe fn`.

### 14.2 ABI types
Integers, floats, `bool` (C `_Bool`), `Ptr<T>`, `extern fn` types, `@c struct`s, enums (as their backing type), `[T; N]` inside `@c struct`s only. Passing any other type to an extern ⇒ error.

`std::c` (prelude alias `c`) provides `c::char c::uchar c::short c::ushort c::int c::uint c::long c::ulong c::longlong c::ulonglong c::size_t c::ssize_t` as platform-correct aliases, plus `c::alloc<T>(count: usize) -> Ptr<T>`, `c::free<T>(p: Ptr<T>)`.

### 14.3 `@c struct`
```rs
@c struct Timespec { let tv_sec: i64; let tv_nsec: c::long; }
```
C layout (declaration order, C alignment). Fields must be ABI types. Otherwise an ordinary struct.

### 14.4 Pointers
- `Ptr<T>`: raw address, value type, may be null. `Ptr<T>::null()` (type argument required, §10), `p.is_null()`, `p.read() -> T`, `p.write(v)`, `p.offset(n: isize) -> Ptr<T>`, `p.cast<U>() -> Ptr<U>`, `p.addr() -> usize`, `Ptr::from_addr(a: usize)`.
- `T` should be an ABI type; reading/writing a non-ABI `T` is undefined behaviour.
- Strings: `s.to_cstr() -> Ptr<c::char>` allocates a NUL-terminated copy with `c::alloc` (caller frees with `c::free`). `str::from_cstr(p: Ptr<c::char>) -> str` copies (unsafe).

### 14.5 Passing GC memory
- `x.with_ptr<R>(f: fn(Ptr<E>) -> R) -> R` is available on: `[T]` and `[T; N]` (E = element type, must be ABI) and mutable places of `@c struct` type (E = the struct). For the duration of `f` the memory is pinned (not moved or collected) and writes through the pointer are visible in `x`. The pointer MUST NOT be retained after `f` returns (undefined behaviour).
- `with_ptr` itself is safe; using the pointer requires `unsafe`.

### 14.6 `unsafe`
- Requires an unsafe context: calling an `unsafe fn` (including externs); `Ptr` `read`/`write`/`offset`/`from_addr`/`cast`; `str::from_cstr`; `c::free`.
- Unsafe contexts: `unsafe { ... }` block statement, `unsafe => expr` expression, the body of an `unsafe fn`.
- `unsafe fn` declares a function whose callers need an unsafe context. A safe function may contain unsafe blocks.
- Inside an unsafe context, behaviour of invalid pointer use, C-side errors, or misuse of pinned pointers is undefined. That is the third bolt.

### 14.7 Callbacks
- A top-level, non-generic, non-capturing `fn` whose parameter and return types are ABI types converts implicitly to a matching `extern fn(...) -> R`. Closures and methods do not.
- A callback MUST be invoked on a thread created by the Threebolt runtime (§13.4); foreign-thread invocation is undefined behaviour.

---

## 15. Reflection

- `typeof(T)` — a `Type` for type expression `T` (compile-time operand, evaluated at runtime for type parameters).
- `x.type()` — the runtime type of a value (for unions: the actual member; for trait references and boxes: the concrete type).
- `nameof(T) -> str` — the type's declared name (`"Position"`, generic args included: `"List<i32>"`).
- `Type`: implements `Eq`, `Hash`, `Display`; `.name -> str`; `.is_subtype_of(other: Type) -> bool`. No member enumeration in 1.0.

---

## 16. Prelude and core stdlib surface

Implicitly imported names:

**Functions:** `print(s: str)`, `println(s: str)`, `eprintln(s: str)`, `panic(msg: str) -> !`, `error(msg: str) -> Err`, `len<T: Len>(x: T) -> usize`.

**Types:** `Err`, `Done`, `Type`, `List<T>` (`[T]`), `Map<K: Hash + Eq, V>`, `Set<T: Hash + Eq>`, `Range<T>`, `Ptr<T>`, module alias `c`.

**Traits:**

| Trait | Members | Notes |
|---|---|---|
| `Truthy` | `fn truthy(self) -> bool` | §6.6 |
| `Len` | `get fn len(self) -> usize` | `str`, collections |
| `Display` | `fn to_str(self) -> str` | f-strings; primitives, `str`, enums, `Type` |
| `Error` | `fn message(self) -> str` | §12; `Err` also implements `Display` |
| `Default` | `fn default() -> Self` | static |
| `Eq` | `fn eq(self, o: Self) -> bool` | `==`/`!=` |
| `Ord : Eq` | `fn cmp(self, o: Self) -> i32` | `<0`, `0`, `>0` |
| `Hash` | `fn hash(self) -> u64` | Map/Set keys |
| `Add Sub Mul Div Rem` | `fn add(self, o: Self) -> Self` etc. | binary operators (RHS and result `Self`) |
| `Neg` | `fn neg(self) -> Self` | unary `-` |
| `BitAnd BitOr BitXor Not Shl Shr` | analogous | bitwise operators |
| `Index<I, V>` | `fn index(self, i: I) -> V` | `c[i]` read |
| `IndexMut<I, V> : Index<I, V>` | `fn set(mut self, i: I, v: V)` | `c[i] = v` |
| `Iterator<T>` | `fn next(mut self) -> T \| Done` + defaults: `map filter for_each fold any all count enumerate of_type<U> to_list first` | `Done` sentinel avoids null-collapse |
| `Iterable<T>` | `fn iter(self) -> Iterator<T>` | `for` loops |

**Collections** (`[T]`, `Map`, `Set`): implement `Len`, `Truthy`, `Iterable`, `Index`/`IndexMut` (`[T]`, `Map`). `[T]`: `push(mut self, v)`, `pop(mut self) -> T | null`, `insert`, `remove`, getter `first -> T | null`, `enumerate() -> Iterator<(usize, T)>`. `Map`: `get(k) -> V | null`, `contains_key(k)`, getters `keys`, `values` (iterators), `remove`. **Convention:** ergonomic accessors (`get`, `pop`, `first`) use `T | null` and accept null-collapse for nullable element types (disambiguate with `len`/`contains_key`); only `Iterator::next` uses `Done`. Full API is a separate stdlib document.

**Numeric methods:** `min max abs pow sqrt` (floats), `wrapping_* checked_* saturating_*`, `T::parse(s: str) -> T | null`, `T::MIN`, `T::MAX`.

---

## 17. Grammar notes (for the ANTLR4 grammar)

1. Generic-argument disambiguation per §7.5 (semantic predicate or ordered alternative with speculative parse; follow token `(` or `::`).
2. `>>` token splitting inside type-argument lists.
3. Statement-initial `{` is always a block (§6.1).
4. No bare initializer/collection literal in `if`/`while`/`for`/`match` heads (§6.3).
5. In type context `|` is union; in expression context bitwise or. Types appear only after `:`, `->`, `as`, `as?`, `is`, in `<...>`, in `type` aliases, and in pattern `is`.
6. `as?` is one token; `x as T?` is `(x as T)?`.
7. Lambda detection: `ident =>`, `mut ident =>`, `( params ) =>` where params are identifiers with optional `mut`/`: Type`. `(a, b)` not followed by `=>` is a tuple.
8. Conditional expression `a if c else b` only in expression position; statement-initial `if` is the statement.
9. Comparison operators are non-associative: `a < b < c` is a syntax error.
10. F-string lexer modes (§1).
11. Keywords permitted as names after `.` and `::`.
12. After `=>`, a `{` starts an initializer in a function expression body (`fn new() -> Self => { name };`) and a block in a lambda body or match arm (§7.1).
13. In a function type the return type extends over a following union: `fn() -> R | null` is `fn() -> (R | null)`; write `(fn() -> R) | null` for the other reading.
14. A match guard is an expression at `??` precedence or tighter (no lambda, `unsafe =>` or conditional without parentheses), so the arm's `=>` is never taken as a lambda.
15. The §6.3 head restriction applies to any initializer or collection literal not enclosed in `()`, `[]` or `{}`: `for x in [1, 2].iter() {` is an error; write `for x in ([1, 2]).iter() {`.
16. The assembled operators `>=`, `>>`, `>>=` may not contain whitespace (`a > = b` is an error).

---

## 18. Canonical example

```rs
use std::collections::Map;

pub trait Component : Default {}
pub trait Updatable { fn update(mut self); }

pub struct Position : Component, Updatable {
    let x: f32 = 0.0;
    let y: f32 = 0.0;
    pub fn default() -> Self => {};
    pub fn update(mut self) { self.x += 1.0; }
}

pub struct Details : Component {
    let name: str = "UNNAMED";
    pub fn default() -> Self => {};
}

pub class Entity {
    static let mut NEXT_ID: u64 = 0;

    pub let id: u64;
    let components: Map<Type, Component> = [];

    pub fn new() -> Self {
        Entity::NEXT_ID += 1;
        return { id: Entity::NEXT_ID };
    }

    pub fn get<T: Component>(self) -> T | null {
        if let c = self.components.get(typeof(T)) {
            return c as T;
        }
        return null;
    }

    pub fn add<T: Component>(mut self) -> () | Err => self.add(T::default());

    pub fn add<T: Component>(mut self, comp: T) -> () | Err {
        if self.components.contains_key(typeof(T)) {
            return error(f"Entity {self.id} already has a {nameof(T)}");
        }
        self.components[typeof(T)] = comp;
    }

    pub fn update(mut self) {
        self.components.values
            .of_type<Updatable>()
            .for_each(mut c => c.update());
    }
}

pub class EntityBuilder {
    let entity: Entity = Entity::new();
    let built: bool = false;

    pub fn new() -> Self => {};

    pub fn with<T: Component>(mut self) -> Self | Err {
        self.entity.add<T>()?;
        return self;
    }

    pub fn with<T: Component>(mut self, comp: T) -> Self | Err {
        self.entity.add(comp)?;
        return self;
    }

    pub fn build(mut self) -> Entity | Err {
        if self.built {
            return error(f"Entity {self.entity.id} already built");
        }
        self.built = true;
        return self.entity;
    }
}

fn main(args: [str]) -> () | Err {
    let name = match args {
        [n, ..] => n,
        [] => "player1",
    };
    println(f"Welcome, {name}!");

    let player = EntityBuilder::new()
        .with<Details>({ name })?
        .with<Position>()?
        .build()?;

    let entities: [Entity] = [player];
    for mut e in entities {
        e.update();
    }
}
```

---

## Changelog

### 2026-10-27 — lexer (0.1 Phase 1)
- §1 F-string interpolations may contain any expression, including string literals and nested f-strings (was: no unescaped `"`).
- §1 `"..."` literals may not contain raw line breaks. New multi-line strings `"""..."""` and `f"""..."""`.
- §1 Clarified: `\u{HEX}` takes 1–6 digits; float suffixes are allowed on integer digits (`1_f32`); suffixes always start with `_`; malformed numeric literals are errors; digits after `.` never form a float (`t.0.1`); `////` is not a doc comment; a lone `}` in f-string text is an error.
- §1 Added `...` (C variadics, §14.1) to the operator/punctuation list.

### 2026-10-27 — lowercase primitives
- §3.1 Primitive types renamed to lowercase (`I32` → `i32`, `Str` → `str`, `Bool` → `bool`, …); they are predeclared names, not keywords. Applied throughout.
- §14.2 C aliases renamed to C's spelling: `c::int c::uint c::long c::size_t …` (was `c::Int c::SizeT …`).

### 2026-10-27 — contextual `get`
- §1, §4.8 `get` is no longer a reserved keyword; it is contextual (only `get fn` in a member declaration). Getters stay; still no setters.

### 2026-10-27 — parser (0.1 Phase 2)
- §1 Any keyword is also accepted as a name after `::` (needed for `Ptr<T>::null()`, §14.4).
- §17 Added notes 12–16, recording parser decisions the spec left open: `{` after `=>`, union return types in `fn` types, match-guard precedence, the extent of the head restriction, and no whitespace inside `>=`/`>>`/`>>=`.
