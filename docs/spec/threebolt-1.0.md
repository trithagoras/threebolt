# Threebolt 1.0 — Language Reference

Normative technical reference for implementers (human or agent). Supersedes `docs/history/design-doc.md` and `docs/history/decisions-1.0.md`. Terse by design: every rule is meant to be implementable and testable. "Error" without qualification means compile-time error. "Panic" means the runtime abort defined in §13.

**Character:** statically typed, GC-managed, Rust-flavoured syntax, C#-flavoured semantics. Structs (values), classes (references, single inheritance), traits (behaviour + runtime polymorphism), flattened union types, explicit `null`, no exceptions, easy C interop. Hobbyist "three-bolt" ethos: prefer simple, slightly sharp semantics over safety machinery.

**Not in 1.0:** async; ownership/borrowing/lifetimes; exceptions; ADT enums with payloads; interfaces (traits instead); setters; method hiding; sealing; `Try` trait; blanket impls; `where` clauses; variance; full reflection; finalizers/destructors; labeled `break`; format specifiers; method references as values; static fields in generic types; a package registry and versioned or remote dependencies (path dependencies only, §2.5).

---

## 1. Lexical structure

- Source is UTF-8. File extension `.3b`.
- Comments: `// line`, `/* block */` (nestable), `/// doc` (line, attaches to next item; exactly three slashes — `////` is an ordinary comment).
- Identifiers: `[A-Za-z_][A-Za-z0-9_]*`. `_` alone is the discard/wildcard token, not an identifier.
- Semicolons terminate statements. Whitespace is insignificant.

**Keywords:**
`abstract as break class const continue defer else enum extern false fn for if impl in internal is let loop match mut nameof null override prot pub return Self self static struct super trait true type typeof unsafe use virtual while`

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
- Every `.3b` file is a module of a package (§2.5). Module path = file path relative to the package's **source root**, `/` → `::`, extension dropped (`game/entity.3b` → `game::entity`). Module paths are case-sensitive. A dependency's modules are named under its key: with dependency `shared`, `shared::model` is the module `model.3b` of that package.
- The **program** is the entry module, every module reachable from it (transitively, across packages) through `use` or a fully-qualified path (§2.2), and the standard library modules. Other files are not part of it (their `impl`s do not apply).
- Standard library modules live under `std::`. The prelude (§16) is implicitly imported everywhere.
- A module contains only items: `use`, `fn`, `struct`, `class`, `trait`, `impl`, `enum`, `type`, `const`, `static let`, `extern` blocks.

### 2.2 `use`
```rs
use std::io::File;              // item
use game::component::{Component, Position};
use game::entity;               // module; then entity::Entity
use std::collections::Map as Dict;
use std::math::*;               // every pub item of a module
```
- Only items visible to the importing module (§2.3) may be imported from another module. Cyclic module imports are permitted (resolution is whole-program).
- `use p::n` imports module `p::n` if that file exists, else the items named `n` of module `p` (for a function, every `pub` overload). If both exist ⇒ error (ambiguous import).
- `use p::*` (a **glob import**) imports every `pub` item of module `p` (`p` must be a module, not a type or enum). The module's own items and its explicit imports silently shadow glob-imported names; two glob imports that bring the same name for different things ⇒ error (at the later `use`), even if the name is never used.
- A module's items and imports share one namespace: an import may not have the same name as an item of the module, or as another import of something different (importing the same thing twice is permitted). Functions with the same name form an overload set (§4.4); any other repeated name ⇒ error. Items and imports shadow prelude names.
- **Re-exports.** A `use` is an item, and may carry `pub` or `internal` (§2.3). The names it binds are then visible to other modules, with that visibility, as if declared in the importing module: after `pub use model::user::User;` in module `api`, both `use api::User;` and `api::User` name `model::user::User`. Glob imports include re-exported names, and `pub use p::*;` re-exports every name the glob binds, each at the lesser of the two visibilities (names it is shadowed for are not bound, so not re-exported). An explicit re-export may not be more visible than what it names: `pub use` of an `internal` item ⇒ error. Re-exports may form cycles; a name reached only through a cycle is not found. Modules cannot be re-exported: a `pub use` or `internal use` naming a module ⇒ error.
- **Fully-qualified paths** need no `use`: in a type or expression path `a::b::C` whose first segment is not a name in scope (item, import or prelude name), the leading segments name a module from the source root, a dependency (§2.5) or `std::…`, e.g. `std::process::exit(1)`, `game::entity::Entity::new()`. A name in scope wins: after `use game::entity;`, `entity::Entity` uses the import. The same rules as `use` apply: only visible items (§2.3) of other modules are accessible.
- Trait methods are callable on any implementing type without importing the trait (impls are global, §8.5).

### 2.3 Visibility
- Items and members are **private by default** (visible within the declaring module; for members, within the declaring type, including every `impl` block for that type, §4.10).
- `pub`: visible everywhere it can be imported/accessed.
- `internal`: items and members; visible anywhere in the declaring package (§2.5). In a program without a manifest every module but std's is in one package, so there `internal` differs from `pub` only for the standard library's own items.
- `prot`: class members only; visible to the declaring class and its subclasses.
- Struct fields are always public; writing `pub` on them is permitted and redundant.
- Trait method implementations are always public.

### 2.4 Entry point
- The entry module MUST declare `fn main()` or `fn main(args: [str])`, return type `()` or `() | E` where every non-`()` member implements `Err`.
- `args` excludes the program name.
- Returning an error: its `message()` (just the message, followed by a newline) is written to stderr, exit code 1. Normal return: exit code 0. `std::process::exit(code: i32)` terminates immediately; pending `defer`s do not run.

---

### 2.5 Packages
- A **package** is a directory containing a manifest, `package.toml`; the directory is the source root of its modules. The manifest's `[package]` table gives the package's `name`, and its `[dependencies]` table maps each **key** to a dependency, `shared = { path = "../shared" }` (relative to the manifest). Its other contents (executables, native library directories) and workspaces belong to the tooling (`docs/PLAN.md`).
- A program whose entry file is run without a manifest is one package with no dependencies, rooted at the entry file's directory.
- The **application package** is the package of the entry module. The standard library is a package that every package depends on under the key `std`.
- Each key is a module root within the depending package (`use shared::model::User;`). Dependencies are not transitive: a package sees only its own dependencies and `std`.
- Package names and keys are identifiers other than `std`. A key that equals the name of a top-level module (`k.3b`) or directory (`k/`) of the depending package ⇒ error.
- A package is identified by its directory (symbolic links resolved): dependency paths that reach the same directory are one package, whose modules and statics exist once in the program.
- A subdirectory containing its own `package.toml` is a separate package, not part of the enclosing one: its files are not modules of the enclosing package, and its name is not a top-level module or directory of it (so `vendor/shared/` may be depended on from its parent).
- The dependency graph must be acyclic; a cycle ⇒ error naming it. (Module cycles within a package are permitted, §2.2.)

---

## 3. Types

### 3.1 Primitive types
`i8 i16 i32 i64 isize u8 u16 u32 u64 usize f32 f64 bool char str`

- Primitive names are lowercase and predeclared, not keywords; static members use paths as for any type (`i32::parse(s)`, `u64::MAX`, `str::from_cstr(p)`). Declaring a type with a primitive's name is an error (outside the standard library: `c::char`, §14.2, is an alias in `std::c`). Nominal and stdlib types are PascalCase (`Type`, `MsgErr`, `List`, `Ptr`, …).
- `str`: immutable Unicode text, reference semantics but immutable (identity unobservable). A `str` is a sequence of characters (Unicode scalar values, i.e. `char`s), and every length, index and offset counts characters, as in Python: `.len`, `s[i]` (a `char`), `s[a..b]` (a `str`, §7.6), `.find`, `.slice`. `.bytes()` gives its UTF-8 encoding (`.to_utf8()` as a `[u8]`). `.len` and `s[i]` take constant time, and `s[a..b]` (like `.slice`) time proportional to the slice's length, whatever the string holds. Concatenation copies, so building a string by repeated `+` in a loop is quadratic; collect the parts and use `[str].join` or `str::from_chars`.
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
- Fields cannot be accessed on a union-typed value, and neither can methods or getters except through the traits below; narrow first (§9). Exception: a union whose members are all literals of one base type (`"left" | "right"`) behaves as that base type for member access and trait implementations (`d.len`, f-string `Display`). Unions participate in conditions (§6.6), `==` (§7.3), and `Hash` (when every member implements `Hash`).
- **Traits of unions:** a union implements a trait `X` when every member implements the same instance of `X` and `X` is *union-safe*: neither `X` nor any of its supertraits has a static member, and `Self` appears in their members only as the receiver. So `i32 | str` and `T | Err` implement `Display` (`println(x)`, `f"{x}"`, `x.to_str()`), while `Add`, `Ord` and `Default` are never implemented by a union. A call through such a trait dispatches on the value's runtime member, as through a trait reference (§8.3). These implementations are automatic and cannot be written with `impl`. `Eq` and `Hash` follow their own rules above.
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
- Implicit (lossless) widening: signed→wider signed, unsigned→wider unsigned, unsigned→strictly wider signed, `i8..i32`/`u8..u32`→`f64`, `i8 i16 u8 u16`→`f32`, `f32`→`f64`. `isize`/`usize` widen only to themselves and `i64`/`u64` respectively, and nothing widens implicitly to `isize`/`usize` (platform independence).
- Everything else requires `as` (§7.9).
- Integer literals adopt the expected type if it is an integer or float type and the value fits (error if not). Float literals adopt an expected float type. Against a numeric type parameter, see §10.1. With no expected type: integer → `i32`, float → `f64`.
- Against a union expected type, an unsuffixed integer literal takes: an equal literal member if there is one; else `i32` if it is a member and the value fits; else the first integer or float member (in declaration order) that the value fits. `let w: i64 | str = 5;` stores an `i64`.
- Inference widens a literal type to its base type: `let d = "left";` has type `str`; `let d: Direction = "left";` has type `Direction`. A union with literal members is kept, since it comes from a declared type: `let o = a.cmp(b);` has type `Ordering`, and `match o` is exhaustive over its three members. This applies wherever a type is inferred from a value (`let`, `if let`/`while let`, pattern bindings, collection literal elements, lambda returns). Without an expected type, the arms of `if`/`else` and `match` expressions are widened this way before they are joined: `let n = 1 if c else 2;` has type `i32`.

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
  4. Remaining tie ⇒ error. Overloads differing only in return type ⇒ error at declaration. Widenings are not ranked against each other: an `i16` argument with `i32` and `i64` overloads is ambiguous.
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
- A unit struct's name is also its value: `Done` ≡ `Done {}`.
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
- A trait's static functions, including inherited default bodies, can be called through an implementing type: `Position::default()`, `Mod7::zero()` (§10.1).

### 4.10 `impl`
```rs
impl Describe for Vec2 { fn describe(self) -> str => f"({self.x}, {self.y})"; }
impl<T: Display> Display for [T] { ... }
```
- `struct S : T` / `class C : T` with methods in the body ≡ a separate `impl T for S { ... }`; both may be mixed for different traits but each (trait, type) pair is implemented exactly once program-wide. Listing `T` in the base list is itself that pair's implementation, even when its members come from defaults, so a separate `impl T for S` is then a duplicate.
- A base list also implements the supertraits of each listed trait from the body's methods, unless they are implemented elsewhere: `struct Position : Component { pub fn default() -> Self => {}; }` implements `Default` (§18).
- **Orphan rule.** An `impl` (or base-list implementation) must be in the package (§2.5) that declares the trait or the target's type constructor (for a trait-reference target, its trait); primitive, collection, tuple and function types belong to `std`. Otherwise ⇒ error, except in the **application package**, which may implement any trait for any type: its orphan impls can only conflict when its own author changes a dependency. So a package with orphan impls can be run but not depended on.
- Two impls of the same (trait, type-constructor) pair anywhere in the program ⇒ error; a clash with a standard-library impl is reported at the other impl, with the std impl as a note.
- Generic impls are allowed for a generic type constructor (`impl<T: Eq> Eq for [T]`). Blanket impls over a bare type parameter (`impl<T: X> Y for T`) are not permitted in 1.0.
- The target may be a trait reference (§8.3): `impl<T: Eq> Contains<T> for Iterator<T>`. Values whose static type is that trait reference implement the trait; types that implement `Iterator` do not thereby implement `Contains`. A call through the impl dispatches to the receiver's own implementation of the trait if its runtime type has one, and otherwise runs the impl. Implementing a trait for a trait reference that already has it as itself or a supertrait (`impl Eq for Ord`) is an error.
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
- index `c[i]`: `c`'s type implements `IndexMut` and `c` is a mutable place, whether `c` has value or reference type, as for calling `IndexMut::set` (a `mut self` method, §5.2). A temporary of reference type also counts (`make_list()[0] = 1`).

### 5.2 What `mut` permits
- **Assignment** (`=`, compound) requires a mutable place.
- **Calling a `mut self` method** requires the receiver expression to be a mutable place **or a temporary** (non-place, e.g. a call result — enables builder chaining). Receivers reached through a reference-typed immutable binding are not mutable: `let e = Entity::new(); e.add(c);` ⇒ error.
- Index assignment follows §5.1, like any other mutation: `let xs = [1]; xs[0] = 2;` ⇒ error, as is `xs.push(3)` (`push` takes `mut self`); with `let mut xs` both are permitted. Inside a `self` method, `self.items[i] = v` ⇒ error; declare the method `mut self`.
- Mutability is **shallow** and expresses intent, not a guarantee: `let a = Entity::new(); let mut b = a; b.add(c);` is legal and mutates the object `a` refers to.
- Inside a `mut self` method, `self` is a mutable place; inside a `self` method it is not (so neither fields of `self` nor `mut self` methods on `self` are usable mutably; fields holding references may still have non-`mut` methods called).

### 5.3 Structs (values)
- Assigning, passing, returning, capturing by closure value, and storing a struct copies it.
- A `mut self` method on a struct operates **in place** on the receiver place.
- **Read-modify-write through indexers:** for `c[i].f = v` or `c[i].m()` (`m` a `mut self` method) where the element type is a value type, the compiler emits `let mut t = c[i]; t.f = v; c[i] = t;` (resp. `t.m()`), requiring `c` to implement `Index` and `IndexMut`. Applies recursively to nested value-type paths.
- Getters return copies; `obj.getter.f = v` ⇒ error.
- `for mut x in xs` over value-type elements binds copies; mutations do not affect `xs`.

### 5.4 Classes (references)
- Assignment copies the reference. Identity is preserved. Classes and trait references automatically implement `Eq` as reference equality, so they satisfy `T: Eq` bounds; an explicit `Eq` impl overrides it. This includes `[T]`, `Map` and `Set`: `[1] == [1]` is `false`.

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
- Expression statements: any expression. Discarding a value whose type contains a member implementing `Err` ⇒ error; use `_ = expr;`.

### 6.2 Assignment
`place = expr;` and `place op= expr;` for `+ - * / % & | ^ << >>`. `place op= e` evaluates `place` once. `_ = expr;` evaluates and discards.

### 6.3 `if`
```rs
if cond { ... } else if cond2 { ... } else { ... }
if let x = expr { ... } else { ... }
if let mut x = expr { ... }
```
- `cond` follows §6.6. No parentheses required; a struct/class initializer (`Name { ... }` or `{ ... }`) may not appear directly in a condition, `while` head, `for` iterable, or `match` scrutinee unless parenthesized. The same holds for a struct pattern after `is` (§7.3): `if (p is Point { x: 0, .. }) { ... }`; unparenthesized, `if p is Point { ... }` is a type test followed by the block. Collection literals are allowed (`for x in [1, 2].iter() { ... }`).
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
- Each iteration binds `p` afresh, as each execution of a `let` creates a new binding; closures created in different iterations capture different variables (§7.8): `for i in 0..3 { fs.push(() => i); }` captures `0`, `1`, `2`.
- Iterating a `[T]` walks it live by index: elements pushed during the loop are visited. `Map` and `Set` iterate in insertion order.
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
| 11 | `== != < > <= >=` `is` `in` | none (no chaining) |
| 12 | `&&` | left |
| 13 | `\|\|` | left |
| 14 | `??` | right |
| 15 | `a if c else b` | right |
| 16 | lambda `params => body`, `unsafe => expr` | right |

Bitwise binds tighter than comparison (no C precedence trap).

### 7.3 Operators
- Arithmetic `+ - * / %` on same-typed numerics after widening. Integer `/` and `%` by zero panic. Overflow per §13.2. `+` also concatenates `str + str`.
- Bitwise `& | ^ ~ << >>` on integers and `@flags` enums. The shift amount may be any integer type; the result has the left operand's type. A shift amount ≥ bit width (or negative) panics in checked mode.
- `==`/`!=`: any two types where one is assignable to the other. Requires `Eq` (structs: automatic field-wise; classes: reference identity unless `Eq` implemented; unions: compare runtime type, then value). Comparison with `null` is always permitted on types containing `null`.
- `< > <= >=`: requires `Ord`. Built-in for numerics, `char`, `str` (byte-lexicographic), enums.
- `&& || !`: short-circuit, operands per §6.6, result `bool`.
- `a ?? b`: if `a` is `null`, evaluates `b`. Type: `(A minus null) | B`. Only null is defaulted (errors are not).
- `x is T`: runtime test, `bool`. `T` may be any type including traits, literal types and unions (true if any member matches). Narrows (§9).
- `x is pattern`: `true` if `x` matches the refutable pattern (§11); its bindings are in scope where the test is known to be true: the then-block of an `if`/`while`, the right operand of `&&`, the `a` of `a if c else b`, and the code after an `if !(x is p) { ... }` whose block diverges (§9). Guards are not allowed (`&&` instead).
  - What follows `is` is decided syntactically: `[` or `(` starts a pattern (`xs is []`, `xs is [first, ..rest]`, `t is (0, _)`); so do `Name { ... }` (a struct pattern, subject to §6.3) and a literal followed by `..`/`..=` (`n is 1..=9`). Anything else is a type, as before (`x is i32`, `x is Foo<T>`, `x is i32 | null`, `x is "a"`, `x is null`).
  - So a list or tuple **type** cannot be written after `is` with brackets: write `x is List<str>` (`[str]` is `List<str>`), or a pattern of type tests `t is (is i32, is str)`. A name that denotes a type where a pattern expects a binding (`xs is [str]`, `x is (i32 | str)`) is an error.
  - Or-patterns are allowed inside brackets (`xs is [1 | 2, ..]`); at the top level, `|` after a type extends the union and after a pattern is a bitwise or.
- `x in c`: membership test through the `Contains` trait (§16): `c`'s type must implement `Contains<E>`, `x` is checked against `E`, and the result is `Contains::contains(c, x)` (`c` is evaluated before `x`). Implemented by `[T]`, `Set<T>`, `Range<T>`, `Map<K, V>` (tests keys), `Iterator<T>` (searches the remaining elements, consuming the iterator up to the first match) and `str` (as `Contains<char>`, so `'e' in s`; substrings use `s.contains("ab")`).
- Ranges `a..b` (half-open), `a..=b` (inclusive): integer or `char` operands; type `Range<T>` (implements `Iterable<T>`).
- **Overloadable** via traits (§16): `+ - * / %` unary `-`, `== !=` (`Eq`), `< > <= >=` (`Ord`), `[]` (`Index`, `IndexMut`), `in` (`Contains`), `& | ^ ~ << >>`. Compound assignment uses the binary operator. **Not** overloadable: `&& || ! ?? ? is as as? ..`, assignment.

### 7.4 Conditional expression
`a if cond else b` — evaluates `cond` first, then exactly one branch. Type: `A | B`. `cond` per §6.6. In `a`, narrowing from `cond` applies; in `b`, the negated narrowing applies.

### 7.5 Calls, members, generic arguments
- `f(a, b, opt: c)`; methods `x.m(args)`; static `Type::f(args)`; module `mod::f(args)`; generic `f<T>(args)`, `Type<T>::f()`, `T::default()` (T a type parameter bounded by a trait declaring `default`).
- **Generic-argument disambiguation:** in expression position, after an identifier or `::` path segment, `<` begins a type-argument list iff the tokens up to the matching `>` parse as a type-argument list **and** the next token is `(` or `::`. Otherwise it is less-than. `>>` is split into two `>` inside type-argument lists. Consequence: `f(a < b, c > (d))` parses as a generic call; parenthesize to force comparisons.
- Every value has built-in method `.type() -> Type`.

### 7.6 Indexing
`c[i]` calls `Index::index`; as an assignment target, `IndexMut::set`. `[T]` and `str` panic on out-of-range indices; `Map[k]` panics on missing keys (use `.get(k) -> V | null`). `s[i]` on a `str` is its `i`th character (`str` implements `Index<usize, char>`, not `IndexMut`: strings are immutable).

**Slicing:** on `[T]`, `[T; N]` and `str`, a range inside the brackets selects elements (for `str`, characters): `xs[a..b]`, `xs[a..=b]`, `xs[a..]` (to the end), `xs[..b]`, `xs[..=b]` (from the start), `xs[..]` (all).
- Bounds are `usize`; the result is a new `[T]` holding copies of the elements (like `..rest` in a pattern, §11), or for a `str`, the `str` of the selected characters (`"héllo"[1..3]` is `"él"`). It is not a place: `xs[1..] = ys` and `xs[1..].push(v)` do not change `xs`, and the first is an error.
- Panics unless `start <= end <= len` (with `end` one past an inclusive bound).
- Slicing is built in, not provided through `Index`. Open-ended ranges (`a..`, `..b`, `..`) exist only inside index brackets. On other types a bracketed range is an ordinary `Range` index (`c[a..b]` calls `Index` with a `Range`).

### 7.7 Literals
- Tuple `(a, b)`; access `.0`, `.1` (a plain decimal: no suffix, `_` or leading zeros).
- Collection `[a, b, c]`, repeat `[v; n]`, map `[k: v, k2: v2]`, empty map `[:]`. **Target-typed:** `[...]` builds `[T]`, `Set<T>`, or `[T; N]` according to the expected type (default `[T]`); `[]` is valid for any expected collection type (`Map` included), error without one. `[k: v]` builds `Map<K, V>`. `[v; n]` builds `[T; N]` when the expected type is an array (`n` then must equal `N`), else a `[T]` of `n` copies (`n: usize`, evaluated at runtime).
- Initializers per §4.7.

### 7.8 Lambdas
```rs
x => x + 1
(a, b) => a + b
(x: i32, y: i32) => x * y
mut c => c.update()
x => { let y = x * 2; return y; }
() => work()
((i, c)) => c if i > 0 else '_'
(acc, Point { x, y }) => acc + x * y
```
- Inside the parentheses each parameter is an irrefutable pattern (§11), optionally annotated: `((a, b): (i32, i32)) => a + b`. The number of parameters is the number of patterns, so `(a, b) => …` takes two arguments while `((a, b)) => …` takes one tuple and destructures it. A plain name is a binding pattern; an unparenthesized parameter can only be a name (`x`, `mut x`).
- Parameter types inferred from the expected `fn` type; else must be annotated. Return type inferred from body.
- Captures enclosing variables **by reference** (shared, live; mutating a captured `let mut` is visible outside). A capture refers to the binding in scope when the lambda is created; a later binding of the same name (shadowing, the next loop iteration, §6.4) is a different variable. Lambdas are reference-type values of `fn(...) -> R` type.
- `?` inside a lambda propagates out of the lambda, not the enclosing function.
- A named function may be used as a `fn` value by name. Methods may not (use a lambda).

### 7.9 Casts
- `x as T`:
  - numeric → numeric: truncation/wrap for integers, IEEE conversion for floats, float→int truncates toward zero and saturates (NaN → 0). Never panics. This also applies to type parameters whose runtime arguments are numeric (§10.1).
  - integer ↔ enum: §4.11.
  - `char` → integer: the scalar value, wrapped like an integer cast. Integer → `char`: panics if the value is not a Unicode scalar value. `bool` → integer: `0`/`1`.
  - downcast/trait/union narrowing: checked at runtime, **panics** on failure. Struct from box yields a copy.
  - upcast: always succeeds (redundant but permitted).
- `x as? T`: same checks for non-numeric conversions, yielding `T | null` instead of panicking (integer → `char` yields `null` for a non-scalar value). It is also accepted on numeric casts, which never yield `null`. If `T` already contains `null`, a null `x` is indistinguishable from failure.
- A cast that can never succeed statically ⇒ error.
- A collection literal operand (`[...]`, `[v; n]`, `[k: v]`, `[]`, `[:]`) takes `T` as its expected type (§7.7): `[] as [i32]`, `[:] as Map<str, i32>`.

### 7.10 `?` propagation
- Postfix `e?` where `e: U`. Let `F` = members of `U` that are `null` or implement `Err`. If `F` is empty ⇒ error.
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
For `x.m(...)`: inherent methods of `x`'s static type (and base classes), then methods of all traits it implements (program-wide). Same name from two traits with identical signatures and no inherent method ⇒ ambiguity error; disambiguate with `Trait::m(x, ...)`, which calls `Trait`'s member `m` with receiver `x`.

---

## 9. Narrowing

- Applies to: locals, parameters, and `self`. Not to fields (always potentially mutable through aliases), statics, indexers, or getter results — bind first: `if let p = self.parent { p.x }`.
- Narrowing sources: `x is pattern` (bindings as §7.3; in the true branch `x` also narrows to the types the pattern can match, e.g. `[T]` for a list pattern on `[T] | null`), `x is T` (true branch → `x: T` if `T` is a member or subtype of `x`'s type, else narrowed to `T` itself; false branch removes `T`'s members from a union), `x == null` / `x != null`, `x == literal` (literal unions), truthy test (§6.6), `if let`, match arms, `&&`/`||` composition, `!`.
- **Flow sensitivity:** narrowing persists after an `if` whose branch diverges (`return`, `break`, `continue`, panic): `if x == null { return; } x.use();`.
- Assignment to a narrowed `let mut` local resets its type to the assigned value's type.
- Inside a lambda, captured `let mut` variables are not narrowed.
- Narrowing by `is TraitName` on a reference yields a trait reference to the same object (no intersection types).

---

## 10. Generics

- Generic parameters on functions, methods, structs, classes, traits, aliases: `<T, U: Bound + Bound2>`.
- **Reified:** `typeof(T)`, `x is T`, `x as T` work for type parameters at runtime.
- **Invariant** type arguments.
- **Inference:** type arguments are inferred from argument types (unification against parameter types, including lambda parameter/return types where determinable). This includes the type arguments of a generic type in a static call: `Pair::new(1, "a")` is `Pair<i32, str>::new(1, "a")`. Expected-type (return-position) inference is not performed, with one exception: when a type parameter `T` is the call's return type and appears only as the type of parameters whose arguments are unsuffixed numeric literals, and the call has a non-union expected type that meets `T`'s bounds and that every such literal fits, `T` is the expected type (`let b: u8 = random::randint(0, 255);` is `randint<u8>`). Uninferable ⇒ explicit type arguments required.
- A bound `T: Trait` permits calling `Trait`'s instance members on `T` values and its static functions via `T::f()`.
- Unbounded `T` permits only: assignment, passing, `== null` / `!= null`, `is`, `as`, `as?`, `.type()`.

### 10.1 Numeric traits
Prelude traits for code generic over numbers, after Haskell's numeric classes:

| Trait | Supertraits | Members |
|---|---|---|
| `Num` | `Eq Ord Display Add Sub Mul Div Rem` | `from_i64(n: i64) -> Self` (static; panics if `n` does not fit), `zero()`, `one()` (static; default `from_i64(0)`/`(1)`), `abs`, `min`, `max` (defaults via `Ord`) |
| `Signed` | `Num Neg` | — |
| `Integer` | `Num Hash BitAnd BitOr BitXor Not Shl Shr` | `min_value() max_value() bits()` (static), `pow(self, exp: u32)`, `checked_ wrapping_ saturating_` `add sub mul` |
| `Float` | `Signed` | `from_f64(x: f64) -> Self`, `nan() infinity() epsilon()` (static), `pow sqrt floor ceil round trunc sin cos tan exp ln log10 is_nan is_infinite` |

- Every integer and float type implements `Num`; the signed integers and floats `Signed`; the integers `Integer`; `f32`/`f64` `Float`. User types may implement them like any trait.
- **Literals:** an unsuffixed integer literal whose expected type is a type parameter `T` with `T: Num` is `T::from_i64(n)` (the literal must fit in `i64`, else error); a float literal whose expected type is a `T: Float` is `T::from_f64(x)`. So `fn double<T: Num>(x: T) -> T => x * 2;` works for every number type, and a literal that does not fit the type argument panics at runtime (`double` cannot overflow-check `2` against `u8` statically).
- **Defaulting to `f64`:** when every argument that determines a type parameter `T` is an unsuffixed integer literal passed for a parameter of type `T` (so inference would give `i32`), and `i32` does not meet `T`'s bounds but `f64` does, `T` is `f64` and the literals are `f64` values: with `fn sqrt<T: Float>(x: T) -> T`, `sqrt(2)` is `sqrt<f64>(2.0)`. An expected type takes precedence (§10: `let r: f32 = sqrt(2);` is `sqrt<f32>`). A variable of type `i32` is never converted: `sqrt(n)` with `n: i32` is still an error.
- **Conversions:** `x as U` where `x` or `U` involves a type parameter converts numerically (§7.9) when, after substitution, the value is a primitive number and `U` a primitive numeric type; otherwise it is the ordinary checked cast.
- Unions never implement these traits (§3.4).

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

- Patterns appear in `match` arms, `let`, `for`, lambda parameters (§7.8), and after `is` (§7.3).
- `let`, `for` and lambda parameters require irrefutable patterns (tuples, struct destructure, bindings, `_`).

---

## 12. Errors

- `trait Err { fn message(self) -> str; }`. User errors: any struct or class implementing `Err`.
- `Err` as a type is a trait reference (§8.3) holding any error, so `T | Err` is the everyday fallible result: a function returning it may return `error(...)`, any user error, or propagate either with `?`. The value keeps its runtime type (`e is NotFound`).
- The prelude has `impl Display for Err` (`to_str` = `message()`), so every value of static type `Err` is printable; a user error type is `Display` only if it implements it itself.
- stdlib `pub class MsgErr : Err, Display` with `pub fn new(msg: str) -> Self`, `pub virtual fn message(self) -> str` (subclasses may override it) and `to_str` = `message()`. Prelude function `error(msg: str) -> Err` ≡ `MsgErr::new(msg)`. Its result is an `Err`, not assignable to a specific error type: `fn f() -> i32 | NotFound { return error("x"); }` ⇒ error.
- Fallible functions return unions: `fn load(p: str) -> File | NotFound | Err`.
- Propagation `?` (§7.10); must-use (§6.1); default `??` handles only null.
- No exceptions: no `try`/`catch`/`throw`.

---

## 13. Runtime semantics

### 13.1 Panic
- `panic(msg: str) -> !` (diverges). Causes: explicit `panic`, failed `as`, index out of range, missing map key via `[]`, integer divide/modulo by zero, checked-mode overflow, abstract method on abstract instance, static init cycle, `unsafe` misuse detected by the runtime.
- Effect: message + stack trace to stderr, process exits with code 101. Format: `panic: <message>`, then one `    at <function> (<file>:<line>:<col>)` line per active call, innermost first. Not catchable. `defer`s do not run. A panic on any thread terminates the process.

### 13.2 Integer overflow
- Overflow of `+ - *` (and unary `-`, `<<` out-of-range shifts) is a program bug. In **checked mode** (default for debug builds and for interpreters unless configured) it panics. In **release mode** it wraps (two's complement).
- Explicit: `.wrapping_add(x)`, `.checked_add(x) -> T | null`, `.saturating_add(x)` (and `sub`, `mul`, `neg`, `shl`, `shr`).

### 13.3 Garbage collection
- Tracing GC; all class instances, boxes, strings, collections, closures are GC-managed. No destructors or finalizers; use `defer` for deterministic cleanup.
- Implementations may move objects; see §14.5 for pinning.

### 13.4 Threads and memory model
- `std::thread::Thread::spawn<R>(f: fn() -> R) -> Thread<R>`; `t.join() -> R` (a copy for value types; joining again returns the result again). `std::thread::sleep(d: std::time::Duration)` (no wait for a zero or negative `d`).
- When `main` returns (or the process otherwise ends, §2.4, §13.1), threads still running are stopped; their `defer`s do not run.
- `std::sync::Mutex<T>`: `Mutex::new(v)`, `m.lock<R>(f: fn(Guard<T>) -> R) -> R` where `Guard<T>` is a class with `pub let value: T` valid only during `f`. When `f` returns, the guard's `value` is stored back into the mutex (so `(mut g) => g.value += 1` works for value types). Locking a mutex the current thread already holds panics.
- `std::sync::Atomic<T>` for integer and `bool` `T` (checked at `Atomic::new`): `load`, `store`, `swap`, `compare_exchange(expected, desired)`, `fetch_add`, `fetch_sub` (integers only), `fetch_and`, `fetch_or`, `fetch_xor`. All are sequentially consistent; `swap`, `compare_exchange` and the `fetch_*` operations return the previous value (`compare_exchange` succeeded iff that equals `expected`); arithmetic wraps.
- Data races do not corrupt memory or produce invalid references; a racy read returns some previously written value, but value types wider than a machine word may tear. The primitive operations on a `[T]`, `Map` or `Set` are each atomic with respect to the others: length, indexing and index assignment, `push`, `pop`, `insert`, `remove`, `clear`, one step of iteration, and `Map`/`Set` insertion, lookup and removal. Members built from several of them (`contains`, `reverse`, `first`, `last`, `join`, …) are not atomic as a whole; a thread that reads a collection while another writes it may observe intermediate states, so share a collection between threads under a `Mutex` (or don't). Implementations may use a global interpreter lock.

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
- `...` declares a C variadic; variadic arguments must be scalar ABI types — integers, floats, `bool`, `Ptr<T>`, enums, `extern fn` — and are promoted as in C (f32 to f64; `bool`, enums and integers narrower than 32 bits to `c::int`).
- Extern functions are implicitly `unsafe fn`. Their parameters cannot have defaults. Used as a value, an extern function has its `extern fn` type.

### 14.2 ABI types
Integers, floats, `bool` (C `_Bool`), `Ptr<T>`, `extern fn` types, `@c struct`s, enums (as their backing type), `[T; N]` inside `@c struct`s only. Not `str` or `char`. Using any other type in an extern signature, an `extern fn` type or an `@c struct` field ⇒ error.

`std::c` (prelude alias `c`) provides `c::char c::uchar c::short c::ushort c::int c::uint c::long c::ulong c::longlong c::ulonglong c::size_t c::ssize_t` as platform-correct aliases (`c::long` is 32-bit on Windows; `c::char` is `u8` where C's `char` is unsigned), plus `c::alloc<T>(count: usize) -> Ptr<T>` (uninitialized, like `malloc`), `c::free<T>(p: Ptr<T>)`.

### 14.3 `@c struct`
```rs
@c struct Timespec { let tv_sec: i64; let tv_nsec: c::long; }
```
C layout (declaration order, C alignment). Fields must be ABI types; an `@c struct` cannot be generic. Otherwise an ordinary struct.

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
- A panic (or `exit`) inside a callback ends the process (§13.1) without unwinding through the C frames: the C code gets a zero result from that and any later callback, and the process stops when control returns to Threebolt.
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

**Functions:** from `std::io`: `print<T: Display>(x: T)`, `println<T: Display>(x: T)`, `eprint<T: Display>(x: T)`, `eprintln<T: Display>(x: T)` (write `x.to_str()` to stdout, or stderr for the `e` forms; the `ln` forms add a newline), `readln() -> str | null` and `readln(prompt: str) -> str | null` (write `prompt` to stdout, flush stdout, then read one line from stdin as UTF-8, invalid bytes becoming U+FFFD; the result excludes the terminating `\n` or `\r\n`; a final line without a terminator is still returned; `null` at end of input; an I/O error panics); `panic(msg: str) -> !`, `error(msg: str) -> Err`, `len<T: Len>(x: T) -> usize`.

**Types:** `MsgErr`, `Done`, `Type`, `Ordering`, `List<T>` (`[T]`), `Map<K: Hash + Eq, V>`, `Set<T: Hash + Eq>`, `Range<T>`, `Ptr<T>`, module alias `c`.

**Traits:**

| Trait | Members | Notes |
|---|---|---|
| `Truthy` | `fn truthy(self) -> bool` | §6.6 |
| `Len` | `get fn len(self) -> usize` | `str`, collections |
| `Display` | `fn to_str(self) -> str` | f-strings; primitives, `str`, enums, `Type` |
| `Err` | `fn message(self) -> str` | §12; `Err` (as a trait reference) and `MsgErr` implement `Display` |
| `Default` | `fn default() -> Self` | static |
| `Eq` | `fn eq(self, o: Self) -> bool` | `==`/`!=` |
| `Ord : Eq` | `fn cmp(self, o: Self) -> Ordering` | `<`/`<=`/`>`/`>=`; `type Ordering = "less" \| "equal" \| "greater"` |
| `Hash` | `fn hash(self) -> u64` | Map/Set keys |
| `Add Sub Mul Div Rem` | `fn add(self, o: Self) -> Self` etc. | binary operators (RHS and result `Self`) |
| `Neg` | `fn neg(self) -> Self` | unary `-` |
| `BitAnd BitOr BitXor Not Shl Shr` | analogous | bitwise operators |
| `Index<I, V>` | `fn index(self, i: I) -> V` | `c[i]` read |
| `IndexMut<I, V> : Index<I, V>` | `fn set(mut self, i: I, v: V)` | `c[i] = v` |
| `Iterator<T>` | `fn next(mut self) -> T \| Done` + defaults: `map filter for_each fold any all count enumerate of_type<U> to_list first` | `Done` sentinel avoids null-collapse |
| `Iterable<T>` | `fn iter(self) -> Iterator<T>` | `for` loops |
| `Contains<T: Eq>` | `fn contains(self, x: T) -> bool` | `in` (§7.3) |
| `Num Signed Integer Float` | §10.1 | generic numeric code |

**Collections** (`[T]`, `Map`, `Set`): implement `Len`, `Truthy`, `Iterable`, `Contains` (`Map` tests keys; `[T]` when `T: Eq`), `Index`/`IndexMut` (`[T]`, `Map`). `[T]`: `push(mut self, v)`, `pop(mut self) -> T | null`, `insert`, `remove`, getter `first -> T | null`, `enumerate() -> Iterator<(usize, T)>`. `Map`: `get(k) -> V | null`, `contains_key(k)`, getters `keys`, `values` (iterators), `remove`. **Convention:** ergonomic accessors (`get`, `pop`, `first`) use `T | null` and accept null-collapse for nullable element types (disambiguate with `len`/`contains_key`); only `Iterator::next` uses `Done`. Full API is a separate stdlib document.

**Numeric methods:** the members of the numeric traits (§10.1); `min max abs pow sqrt` (floats), `wrapping_* checked_* saturating_*`, `T::parse(s: str) -> T | null`, `T::MIN`, `T::MAX`.

**Primitive trait impls:** every primitive implements `Display` and `Eq`; all but `bool` implement `Ord`; all but floats implement `Hash`. Numeric types implement `Add Sub Mul Div Rem`, signed ones (and floats) `Neg`; integers implement `BitAnd BitOr BitXor Not Shl Shr`. `str` also implements `Len`, `Truthy`, `Add` (concatenation), `Index<usize, char>`, `Iterable<char>` and `Contains<char>`.

**`Display` formats:** `null` as `null` (`null` implements `Display`, so `T | null` does when `T` does); integers in decimal; `bool` as `true`/`false`; `char` as itself. Floats print the shortest digits that round-trip, never with an exponent, and integral values without a fraction: `1`, `0.5`, `0.30000000000000004`, `1000000000000000000000`, `-0`, `inf`, `-inf`, `NaN`.

**Copies out of collections:** reading an element of a collection (indexing, iteration, `get`, `pop`, …) yields a copy when the element type is a value type (§5.3).

**`str` and `char` members:** `str` lengths and offsets count characters (§3.1): `len`, `find`, `slice(start, end)` (≡ `s[start..end]`; panics unless `start <= end <= len`). `bytes()` iterates the UTF-8 encoding. `str::from_chars(cs: Iterable<char>) -> str` and `str::from_chars(cs: Iterator<char>) -> str` build a string from characters (`as` never converts `[char]` to `str`, §7.9); `str::from_utf8(bytes: [u8]) -> str` decodes UTF-8, invalid sequences becoming U+FFFD, and `to_utf8(self) -> [u8]` encodes it. `[T].join(sep: str) -> str` (also on `[T; N]`) concatenates the elements' `to_str()` with `sep` between them; it requires `T: Display` (`["a", "b"].join(", ")` is `"a, b"`, `[1, 2].join("")` is `"12"`). Besides the members above, the 1.0 stdlib provides `str`: `chars bytes contains starts_with ends_with find slice upper lower trim split replace repeat`; `char`: `is_digit is_alphabetic is_alphanumeric is_whitespace is_uppercase is_lowercase to_upper to_lower`; floats: `floor ceil round trunc sin cos tan exp ln log10 is_nan is_infinite` and constants `EPSILON INFINITY NEG_INFINITY NAN`; `[T]`: `clear reverse contains`, getter `last`; `Map`: `clear`; `Set`: `insert contains remove clear`. The stdlib sources are the full reference.

**`std::math`** (not in the prelude; `use std::math;`): Python-style functions on `f64` (narrower numerics widen, §3.6). Constants `PI E TAU INF NAN`. Rounding and parts: `floor ceil trunc round copysign fmod`, `modf(x) -> (f64, f64)` (fraction, whole); `isnan isinf isfinite`, `isclose(a, b, rel_tol: f64 = 1e-9, abs_tol: f64 = 0.0)`; `clamp<T: Num>(x: T, lo: T, hi: T) -> T`. Powers and logarithms: `pow sqrt cbrt exp exp2 expm1 log2 log10 log1p`, `log(x, base: f64 = E)`. Trigonometry (radians): `sin cos tan asin acos atan atan2(y, x) hypot degrees radians sinh cosh tanh asinh acosh atanh`. `fsum(xs: [f64])` (compensated sum). Integers: `gcd<T: Integer>(a: T, b: T) -> T` and `lcm` (results non-negative), and `isqrt factorial comb perm` on `u64`; `comb`/`perm` are `0` when `k > n`, and a result that does not fit in `u64` panics.

**`std::random`** (not in the prelude): pseudo-random numbers in the style of Python's `random`, not for cryptography. `Random` is a xoshiro256** generator seeded through splitmix64, so a seed determines the same sequence on every implementation: `Random::new()` (seeded from the OS), `Random::new(seed: u64)`, and `mut self` methods `seed(s: u64)`, `next_u64() -> u64`, `getrandbits(k: u32) -> u64` (`k <= 64`), `random() -> f64` (`0.0..1.0`, 53 bits), `uniform(a: f64, b: f64) -> f64`, `randint<T: Integer>(a: T, b: T) -> T` (`a..=b`), `randrange<T: Integer>(stop: T)`, `randrange<T: Integer>(start: T, stop: T, step: T = 1) -> T`, `chance(p: f64) -> bool`, `gauss(mu: f64, sigma: f64) -> f64`, `expovariate(lambd: f64) -> f64`, `choice<T>(xs: [T]) -> T`, `choices<T>(xs: [T], k: usize = 1, weights: [f64] | null = null) -> [T]` (with replacement), `shuffle<T>(xs: [T])` (in place), `sample<T>(xs: [T], k: usize) -> [T]` (without replacement). Integer draws are unbiased (rejection sampling). An empty range, an empty list, `k` larger than the list in `sample`, or mismatched or non-positive weights panic. The module's free functions of the same names (except `next_u64`) use one shared generator, seeded from the OS on first use; it is a mutable static, so unsynchronized (§4.2).

**`std::time`** (not in the prelude): durations and clocks. `Duration` is a struct of signed `i64` nanoseconds (field `nanos`; about ±292 years), so subtracting times never fails: a later time subtracted from an earlier one is negative. Constructors `from_secs from_millis from_micros from_nanos` (`i64`) and `from_secs_f64` (truncating and saturating like a cast, §7.9); `as_secs as_millis as_micros as_nanos` (rounded toward zero) and `as_secs_f64`; `is_zero is_negative abs`; statics `ZERO MIN MAX`. Durations implement `Eq Ord Hash Add Sub Neg Display`; `mul(n: i64)` and `div(n: i64)` scale them (`div` rounds toward zero). Arithmetic outside the `i64` range panics as integer arithmetic does (§13.2). `Display` uses the largest unit that keeps a whole part (`ns`, `µs`, `ms`, `s`), with the remainder as decimals without trailing zeros: `1.5s`, `250ms`, `-2s`, `90s`. `Instant` (monotonic clock, for measuring; its value is meaningful only relative to another `Instant`) and `Timestamp` (wall clock: nanoseconds since the Unix epoch, 1970-01-01 UTC, ignoring leap seconds; may go backwards when the system clock changes; static `UNIX_EPOCH`, `since_epoch() -> Duration`) both have `now()`, `elapsed() -> Duration`, `since(earlier) -> Duration` (`self - earlier`), `add(d: Duration)`, `sub(d: Duration)` and implement `Eq Ord Hash`. Operators need `Self` on both sides (§16 trait table), hence the methods. `measure<R>(f: fn() -> R) -> (R, Duration)` calls `f` and times it on the monotonic clock. Calendars, time zones and formatting are not part of `std::time`.

**`std::io`** (not in the prelude, except the printing functions above): files, the standard streams and paths, in the style of Python. Fallible operations return `T | IoErr`.
- *Errors:* `IoErr` (a `MsgErr` subclass with `pub let path: str | null`; its message is `"<description>: <path>"`) and its subclasses `NotFoundErr`, `PermissionErr`, `ExistsErr`, `IsDirErr`, `NotDirErr`, `NotEmptyErr`, `ClosedErr` (the `File` is closed) and `ModeErr` (the file's mode does not allow the operation). Other failures are an `IoErr` with the system's description.
- *Opening:* `open(path: str) -> File | IoErr` (≡ mode `"r"`) and `open(path: str, mode: Mode) -> File | IoErr`, also as `File::open`. `type Mode` is the union of the literals made of a base letter, optionally `+`, optionally `t` or `b` (`+` may come before or after it): `r` reads; `w` writes, creating the file or emptying it; `a` writes at the end (every write), creating the file; `x` writes a file that must not exist yet (`ExistsErr`); `+` adds the other direction without changing the rest (`r+` neither creates nor empties; `a+` reads from the start). `t` (default) opens as text and `b` as binary. Opening a directory is `IsDirErr`.
- *Traits:* `Read` and `Write` are what a source or sink of bytes and text offers, so code can take any of them (`File`, `std::net::TcpStream`). Both are union-safe (§3.4). `Read` requires `read() -> str | IoErr`, `read_line() -> str | null | IoErr`, `read_bytes() -> [u8] | IoErr` and `read_bytes(n: usize) -> [u8] | IoErr`, and provides `lines() -> Iterator<str | IoErr>` and `read_lines() -> [str] | IoErr` over `read_line`. `Write` requires `write(s: str) -> () | IoErr` and `write_bytes(bytes: [u8]) -> () | IoErr`, and provides `write_line(s: str)` (`write(s + "\n")`) and `flush() -> () | IoErr` (does nothing). All are `mut self`; the members' meanings are `File`'s below.
- *`File`* (a class, `: Read + Write`): getters `path -> str`, `closed -> bool`; `close(mut self)` (closing again does nothing). Text files have `read() -> str | IoErr` (the rest), `read_line() -> str | null | IoErr` (as `readln`: without `\n`/`\r\n`, a last line without a terminator counts, `null` at the end), `lines() -> Iterator<str | IoErr>` (lines as `read_line` reads them; an error is yielded once and ends the iteration), `read_lines() -> [str] | IoErr`, `write(s: str) -> () | IoErr` and `write_line(s: str)` (adds `\n`). Binary files have `read_bytes() -> [u8] | IoErr` (the rest), `read_bytes(n: usize)` (up to `n`, fewer only at the end) and `write_bytes(bytes: [u8]) -> () | IoErr`. Calling the other family, reading a file not opened for reading or writing one not opened for writing is `ModeErr`; reading, writing, seeking or flushing a closed file is `ClosedErr`. Text is UTF-8 (invalid bytes read as U+FFFD) and line endings are not translated. `seek(offset: i64, from: SeekFrom = SeekFrom::Start) -> u64 | IoErr` (`enum SeekFrom { Start, Current, End }`; returns the new position) and `tell() -> u64 | IoErr` count bytes. Reads may be buffered, but writes reach the system before `write` returns; `flush() -> () | IoErr` matters only for stdout. Files are not closed by the garbage collector (§13.3): use `defer f.close();`.
- *Standard streams:* `stdin()`, `stdout()`, `stderr()` return the `File`s for the process's streams. They are text files that also allow the byte methods; they cannot seek. `stdin()` and `readln` share one buffer, and the printing functions write to the same streams as `stdout()` and `stderr()`. Closing one closes only that `File`, not the stream (but `readln` then panics, as it reads through `stdin()`). Writing to stderr and reading stdin first flush stdout.
- *Whole files:* `read_text(path) -> str | IoErr`, `read_lines(path) -> [str] | IoErr`, `read_bytes(path) -> [u8] | IoErr`, `write_text(path, s: str) -> () | IoErr` (replace or create), `append_text(path, s: str)`, `write_bytes(path, bytes: [u8])`, `copy(from, to)` (replaces `to`), and `lines(path) -> Iterator<str | IoErr> | IoErr`, whose file closes when the iteration ends (at the end or on an error), otherwise only when the program does.
- *Paths:* `exists(path) -> bool`, `is_file`, `is_dir` (following symbolic links), `file_size(path) -> u64 | IoErr` (bytes), `remove(path) -> () | IoErr` (a file or an empty directory; `NotEmptyErr`), `rename(from, to)` (replaces a file at `to`), `create_dir(path)` (the parent must exist; `ExistsErr` if anything is at `path`), `create_dirs(path)` (with missing parents; an existing directory is fine), `list_dir(path) -> [str] | IoErr` (the entry names without `.` and `..`, sorted), `temp_dir() -> str`. Relative paths are relative to the process's working directory.

**Keyword-named members:** a standard library declaration may name a member with a keyword followed by `_` (`fn null_()`); it is called by the keyword (`Ptr<T>::null()`). User code cannot do this.

---

## 17. Grammar notes (for the ANTLR4 grammar)

1. Generic-argument disambiguation per §7.5 (semantic predicate or ordered alternative with speculative parse; follow token `(` or `::`).
2. `>>` token splitting inside type-argument lists.
3. Statement-initial `{` is always a block (§6.1).
4. No bare initializer in `if`/`while`/`for`/`match` heads (§6.3); collection literals are fine.
5. In type context `|` is union; in expression context bitwise or. Types appear only after `:`, `->`, `as`, `as?`, `is` (unless a pattern follows, §17 note 18), in `<...>`, in `type` aliases, and in pattern `is`.
6. `as?` is one token; `x as T?` is `(x as T)?`.
7. Lambda detection: `ident =>`, `mut ident =>`, `( params ) =>` where each param is a pattern with an optional `: Type`. `(a, b)` not followed by `=>` is a tuple.
8. Conditional expression `a if c else b` only in expression position; statement-initial `if` is the statement.
9. Comparison operators are non-associative: `a < b < c` is a syntax error.
10. F-string lexer modes (§1).
11. Keywords permitted as names after `.` and `::`.
12. After `=>`, a `{` starts an initializer in a function expression body (`fn new() -> Self => { name };`) and a block in a lambda body or match arm (§7.1).
13. In a function type the return type extends over a following union: `fn() -> R | null` is `fn() -> (R | null)`; write `(fn() -> R) | null` for the other reading.
14. A match guard is an expression at `??` precedence or tighter (no lambda, `unsafe =>` or conditional without parentheses), so the arm's `=>` is never taken as a lambda.
15. The §6.3 head restriction applies to any initializer not enclosed in `()`, `[]` or `{}`: `if p == Point { x: 0 }.norm() {` is an error; write `if p == (Point { x: 0 }).norm() {`.
16. The assembled operators `>=`, `>>`, `>>=` may not contain whitespace (`a > = b` is an error).
17. Inside index brackets, a range may omit either bound or both (`xs[1..]`, `xs[..n]`, `xs[..]`, §7.6); `..=` requires an upper bound.
18. After `is`, a pattern follows iff the next tokens are `[`, `(`, a path followed by `{` (where initializers are allowed, §6.3), or a literal (optionally negated) followed by `..`/`..=`; otherwise a type follows (§7.3).

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
- §6.3 Collection literals are allowed in `if`/`while`/`for`/`match` heads; only `{`-initializers need parentheses.
- §17 Added notes 12–16, recording parser decisions the spec left open: `{` after `=>`, union return types in `fn` types, match-guard precedence, the extent of the head restriction, and no whitespace inside `>=`/`>>`/`>>=`.

### 2026-10-27 — front end (0.1 Phase 3)
- §2.1 The program is the entry module plus the modules reachable through `use`, plus the stdlib; module paths are case-sensitive.
- §2.2 Defined how `use p::n` chooses between a module and an item (both ⇒ ambiguity error), the shared item/import namespace, and that items and imports shadow the prelude. `use` cannot be `pub` (no re-exports in 1.0; added to "Not in 1.0").
- §3.1 The primitive-name rule exempts the standard library (`c::char`).
- §7.7 Tuple indices are plain decimals (`t.01`, `t.0_u8` are errors).
- §2.2 Fully-qualified paths (`std::process::exit(1)`) work without a `use`; modules they name are part of the program (§2.1).

### 2026-10-27 — checker and interpreter (0.1 Phases 4–7)
Semantics the spec left open, decided while implementing:
- §2.3 `impl` blocks for a type may access its private members.
- §2.4 `main`'s error result prints only `message()` to stderr.
- §3.4 A union of literals of one base type behaves as the base type for member access and trait impls.
- §3.6 Nothing widens implicitly to `isize`/`usize`. Rules for an unsuffixed integer literal against a union expected type.
- §4.4 Widenings are not ranked against each other (all-widening ties are ambiguous).
- §4.5 A unit struct's name is its value.
- §4.10 A base-list trait is that pair's implementation even with no methods in the body; a base list also implements the listed traits' supertraits from the body's methods.
- §5.2 Index assignment through an immutable binding of a reference-typed container is permitted.
- §5.4 `[T]`, `Map`, `Set` compare by identity.
- §6.4, §7.8 Each loop iteration is a fresh binding; captures refer to the binding in scope at lambda creation. Lists iterate live; maps and sets in insertion order.
- §7.3 Shift amounts may be any integer type.
- §7.7 `[v; n]` without an expected array type builds a `[T]`; `[]` works for an expected `Map`.
- §7.9 `char` ↔ integer and `bool` → integer casts; `as?` on numeric casts.
- §8.5, §10 `Trait::m(x)` semantics; static calls infer a generic type's arguments.
- §12 The `Err` class API.
- §13.1 Panic message and trace format.
- §16 Primitive trait impls, `Display` formats (floats), value copies out of collections, `str` byte offsets, additional `str`/`char`/float/collection members, std keyword-named members.

### 2026-10-27 — slicing, printing any `Display`
- §7.6 Slicing `[T]` and `[T; N]` with a range in index brackets, including open-ended ranges (`xs[1..]`); the result is a copy. §17 note 17.
- §16 `print`, `println` and `eprintln` take any `Display` value (`println(true)`).

### 2026-10-27 — patterns after `is`
- §7.3 `x is pattern` tests a refutable pattern and binds its names where the test holds (`if xs is [first, ..rest]`). What follows `is` is chosen syntactically (§17 note 18): `[`, `(`, `Name {` and literal ranges start patterns, anything else is a type. List and tuple *types* after `is` are written `List<T>` or `(is A, is B)`.
- §6.3 Struct patterns after `is` are subject to the head restriction. §9 pattern tests narrow. §11 lists where patterns appear.

### 2026-10-27 — casts target-type collection literals
- §7.9 A collection literal operand of `as`/`as?` takes the cast type as its expected type, so `[] as [i32]` works without a typed `let`.

### 2026-10-27 — C interop and threads (0.1 Phase 8)
Semantics the spec left open, decided while implementing:
- §13.4 `std::thread::sleep`; `join` returns a copy and may be repeated; threads still running when `main` returns are stopped; a `Mutex` stores the guard's value back when `f` returns, and relocking on the same thread panics; the `Atomic` operation list, previous-value results (including `compare_exchange`), wrapping arithmetic, `fetch_add`/`fetch_sub` only for integers; each collection operation is atomic.
- §14.1 Which types may be C variadic arguments and how they are promoted; extern parameters have no defaults; an extern used as a value has an `extern fn` type.
- §14.2 `char` and `str` are not ABI types; the `c::` aliases follow the platform (including `c::char`'s signedness); `c::alloc` does not zero memory.
- §14.3 `@c struct`s cannot be generic.
- §14.7 A panic or `exit` inside a callback ends the process without unwinding through C.

### 2026-10-27 — `std::math` and `std::random`
- §16 Two std modules, outside the prelude: `std::math` (Python's `math` on `f64`, plus integer `gcd lcm isqrt factorial comb perm`) and `std::random` (Python's `random`: a `Random` class and free functions over a shared generator). The generator algorithm (xoshiro256** seeded through splitmix64) is part of the spec, so seeded sequences are portable.

### 2026-10-27 — traits of unions, `null` displays
- §3.4 A union implements a union-safe trait (no static members; `Self` only as the receiver, also in supertraits) when every member implements the same instance of it; its members are callable on the union and dispatch on the runtime member. Before, only `Eq`, `Hash` and conversions to trait references worked, so `println(x)` on an `i32 | str` was an error while `let d: Display = x;` was not.
- §16 `null` implements `Display` (as `null`).

### 2026-10-27 — numeric traits
- §10.1 Prelude traits `Num`, `Signed`, `Integer`, `Float`, implemented by the primitives (and open to user types). Literals of a `Num`/`Float` type parameter call `T::from_i64`/`T::from_f64`; `as` converts numerically through type parameters.
- §10 Inference takes the expected type for a return-type parameter determined only by unsuffixed numeric literals.
- §16 `std::random`'s `randint`/`randrange` and `std::math`'s `gcd`/`lcm`/`clamp` are generic. A trait's static members (including default bodies) can be called through an implementing type: `Mod7::zero()`.
- Fix: `abs` on an unsigned integer is the identity (it negated values with the top bit set).

### 2026-10-27 — `in` operator
- §7.2, §7.3, §16 `x in c` is a membership test at comparison precedence, through the new prelude trait `Contains<T: Eq>`. Implemented by `[T]`, `Set`, `Range`, `Map` (keys; `Map` gains `contains(k)`), `Iterator<T>` (consuming search) and `str` (`Contains<char>`). `str` also implements `Iterable<char>`, so `for ch in s` works.
- §4.10 An impl may target a trait reference (`impl<T: Eq> Contains<T> for Iterator<T>`); it applies to values of that static type, and runtime dispatch prefers the value's own implementation.
- §5.4 Classes and trait references implement `Eq` (identity) automatically, so they meet `T: Eq` bounds; before, `==` worked on them but the bound did not.

### 2026-10-27 — index assignment needs a mutable place
- §5.1, §5.2 `c[i] = v` requires `c` to be a mutable place even when `c` has reference type, matching `c.set(i, v)` (`IndexMut::set` takes `mut self`). Before, `let xs = [1]; xs[0] = 2;` and `self.map[k] = v` in a `self` method were permitted while the equivalent `set` call was not.

### 2026-10-27 — glob imports
- §2.2 Added `use p::*`: imports every `pub` item of module `p`; items and explicit imports shadow glob names; two globs disagreeing on a name ⇒ error.

### 2026-10-27 — `Err` is the error trait
- §12, §16 Renamed the error trait `Error` to `Err` and the prelude's message-error class `Err` to `MsgErr`. `error(msg)` now returns `Err` (a `MsgErr` behind the trait reference), so `T | Err` accepts `error(...)` and every user error alike; `impl Display for Err` keeps any `Err` printable. Before, `T | Err` meant the concrete class, so a user error that implemented `Error` without subclassing `Err` could not be returned or `?`-propagated through it.

### 2026-10-27 — `readln`
- §16 Prelude functions `readln()` and `readln(prompt: str)`, returning `str | null`: print the prompt (an overload rather than a default parameter, so it is positional), flush stdout, and read a line from stdin without its `\n`/`\r\n`; `null` at end of input (like `pop`, not an error); an I/O error panics.

### 2026-10-27 — strings count characters
- §3.1, §7.6, §16 `str` lengths, indices and offsets count characters (Unicode scalar values) instead of UTF-8 bytes, as in Python: `len`, `find` and `slice` change meaning, and `slice` no longer panics on a mid-character offset. `str` implements `Index<usize, char>` (`s[i]`) and supports range slicing (`s[a..b]`, `s[a..]`, …) yielding a `str`. This reverses the original decision of no integer indexing on `str`: developer experience over constant-time indexing. Byte-level access stays available through `bytes()`.

### 2026-10-27 — `str::from_chars` and `join`
- §16 `str::from_chars(cs: Iterable<char> | Iterator<char> overloads) -> str` (the way back from `chars()`, since `as` only narrows) and `[T].join(sep: str) -> str` for `T: Display`.

### 2026-10-27 — destructuring lambda parameters
- §7.8, §11, §17 Lambda parameters inside parentheses are irrefutable patterns: `((i, c)) => …` takes one tuple and binds its elements, `(acc, Point { x, y }) => …` destructures its second argument. Parameter count is unchanged by destructuring (`(a, b) =>` is still two parameters), so there is no implicit tuple splatting.

### 2026-10-28 — `std::io`
- §16 New module `std::io`: `File` (opened with Python-style modes, typed as the literal union `Mode`, so a bad mode is a compile error), the standard streams, whole-file helpers, path operations, and the `IoErr` hierarchy for the failures worth telling apart (`NotFoundErr`, `PermissionErr`, `ExistsErr`, ...). Text and binary files have separate method families. `File::close` returns `()` and may be repeated, so `defer f.close();` needs no discard.
- §16 `print`, `println`, `eprintln` and `readln` are now declared in `std::io` (still in the prelude, as `Map` and `Set` come from `std::collections`), and the prelude gains `eprint`. Their behaviour is unchanged.
- §16 `str::from_utf8(bytes: [u8]) -> str`, the inverse of `bytes()`.

### 2026-10-28 — orphan impls
- §4.10 Non-normative note: a future package system will add an orphan rule for library packages only; 1.0 keeps impls unrestricted. A duplicate of a standard-library impl is reported at the program's impl, with the std impl as the note (was: reported in std, since std is declared last).

### 2026-10-28 — constant-time `str` indexing
- §3.1 `.len` and `s[i]` take constant time, and slicing time proportional to the slice (was: operations by index may take time linear in the string's length). Semantics are unchanged: lengths, indices and offsets still count characters. Implementations keep a character count per string and, for strings with characters outside the BMP, an offset table (as Python's PEP 393 does with its flexible layout).

### 2026-10-28 — literals default to `f64` for `Float` parameters
- §10.1 A type parameter determined only by unsuffixed integer literals is `f64`, not `i32`, when its bounds exclude `i32` and admit `f64` (`sqrt(2)` for `sqrt<T: Float>`), so float functions can be generic over `Float` without breaking calls with integer literals. Narrow by design: variables are never converted, and other bounds (`T: Num`) still infer `i32`.

### 2026-10-28 — collection atomicity narrowed
- §13.4 Only the primitive collection operations are atomic (length, indexing, index assignment, `push`, `pop`, `insert`, `remove`, `clear`, one iteration step, `Map`/`Set` insertion, lookup and removal); members composed of several (`contains`, `reverse`, `first`, `last`, `join`, …) are not atomic as a whole (was: every operation). Races still never corrupt a collection. As in C#, Java and Go, concurrent reading and writing of one collection should go through a `Mutex`; dedicated concurrent collections may come with the standard library.

### 2026-10-28 — packages, `internal`, re-exports
- §2.5 New: packages. A directory with a `package.toml` is a package and the source root of its modules (was: the entry file's directory, which remains the root of a program run without a manifest). Path dependencies only, imported under their manifest key; not transitive; one copy per directory; the dependency graph must be acyclic. Removed "package manager/dependencies" from "Not in 1.0" (a registry and versioned or remote dependencies stay out).
- §1, §2.3 New keyword and visibility `internal`: visible within the declaring package.
- §2.2 Re-exports: `pub use` / `internal use` make the imported names visible to other modules as if declared there (was: `use` cannot be `pub`). Modules cannot be re-exported. Removed from "Not in 1.0".
- §4.10 Orphan rule (replacing the non-normative note): an impl must be in the package declaring the trait or the target's type constructor, except in the application package.

### 2026-10-28 — nested packages
- §2.5 A subdirectory holding a `package.toml` is not part of the enclosing package (was: unspecified, so its files were modules of both packages).

### 2026-10-28 — `Ordering`
- §3.6 Inference widens only a lone literal type; a union with literal members is kept (was: every literal member widened, so `let o = a.cmp(b);` was `str`). Literal arms of `if`/`else` and `match` without an expected type still join as their base types.
- §16 `Ord::cmp` returns the new prelude alias `Ordering = "less" | "equal" | "greater"` (was: `i32`, negative/zero/positive). `match` on it is exhaustive without `_`.

### 2026-10-28 — `std::time`
- §16 New module `std::time`: `Duration` (signed `i64` nanoseconds), `Instant` (monotonic clock), `Timestamp` (wall clock, nanoseconds since the Unix epoch) and `measure`. Mixed arithmetic (`Instant` with `Duration`) is by method, since operator traits take `Self` on both sides.
- §13.4 `std::thread::sleep` takes a `Duration` (was: `ms: u64`).

### 2026-10-28 — `Read` and `Write`
- §16 `std::io` traits `Read` (`read`, `read_line`, `read_bytes()`, `read_bytes(n)`; `lines`, `read_lines` provided) and `Write` (`write`, `write_bytes`; `write_line`, `flush` provided). `File` implements both with its existing methods.
- §16 `str::to_utf8(self) -> [u8]`, the inverse of `from_utf8`.
