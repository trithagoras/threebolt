# Threebolt 1.0 — Decision Log

Running log of decisions from the 1.0 design interview. **Superseded by `docs/spec/threebolt-1.0.md`**, which refines some entries (e.g. generic-arg follow tokens, named-only optional params); kept for rationale/history.

## Scope

- No async in 1.0.
- C interop is a 1.0 feature (syscalls, threading go through it).

## Round 1 — Bindings, assignment, conditions

- **Assignment:** `=` everywhere (init, reassign, index/field writes). `:=` is removed. Compound ops `+= -= *= /= %=` exist.
- **Binding mutability is shallow:** `let` forbids rebinding. Calling a `mut self` method or writing a field requires a `let mut` binding/field/parameter. Aliasing is allowed: `let mut b = a;` is legal even if `a` is immutable — `mut` expresses intent, not a guarantee.
- **Explicit `self.`** is required for field/method access inside methods.
- **`if let x = e`** is a presence test only: runs when `e` is non-null, narrows `T | null` → `T`. Never consults `Truthy`.
- **`if e`** accepts `Bool` or any type implementing `Truthy` (desugars to `e.truthy()`).
  - stdlib implements `Truthy` for `Bool`, `Str` (empty = false), collections (empty = false). **Not** numbers.
  - `T | null` in a plain `if`: null is false (language rule), else delegates to member's `Truthy`; compile error if any member lacks `Truthy`.
  - `!`, `&&`, `||` accept `Truthy` operands and return `Bool`. `??` is the null-default operator.

## Round 2 — Structs and classes

- **Fields:** one syntax for structs and classes: `let name: T;` / `let mut name: T;` with optional `= default`. Struct/initializer literals use commas.
- **Structs:** value types, no inheritance, can implement traits, can have static members, fields always public.
- **Classes:** reference types, single class inheritance, any number of traits. Base class (if any) listed first: `class Dog : Animal, Speak`. Fields and static members are inherited.
- **`abstract struct` removed.** `abstract class` exists (not instantiable).
- **Struct → trait type = C# boxing:** converting a struct to a trait type copies it into a GC box; calls through the trait type mutate the box in place; `as StructType` copies it back out. (`Component` in the example becomes a trait.)
- **Constructors:** `new` is an ordinary static function returning `Self` (or `Self | Err`). No `self` inside `new`, so partially-initialized objects are unobservable. Allocation only via the `{ ... }` initializer, which must supply every field without a default.
- **Base initialization:** spread a base instance: `{ ..Animal::new(name), breed: "lab" }` copies base fields from a base-class value.
- **Visibility:** private by default; `pub` = public; `prot` = visible to subtypes. Applies to class fields and methods. `pub let mut` field is publicly writable. Struct fields are always public.

## Round 3 — Traits and dispatch

- **No `dyn`:** a bare trait name used as a type (`x: Speak`, `Map<Type, Component>`) is a runtime trait reference with dynamic dispatch. `T: Speak` generic bounds are static dispatch (monomorphize vs erase is an implementation choice; semantics identical).
- **Class methods non-virtual by default.** `virtual` opts in; `override` required to override; `abstract fn` (no body, only in `abstract class`, implies virtual).
- **No method hiding:** redeclaring a non-virtual base method's signature in a derived class is a compile error.
- **No sealing** in 1.0.
- Calls through a trait type are always dynamically dispatched, regardless of `virtual`.
- **Supertraits:** `trait Component : Default` — implementors must also implement `Default`.
- Trait methods may have default bodies. Traits may declare static functions (`fn default() -> Self;`), callable via a generic param (`T::default()`), not via a trait reference.
- Overrides and trait impls must match the signature exactly, including `self` vs `mut self`. Calling a `mut self` method through any reference requires a mutable binding.
- `super.method(...)` calls the base implementation from an override.
- Structs cannot have `virtual`/`abstract` methods.
- A class may not re-declare a trait its base already implements; override the virtual method instead.
- **`impl Trait for Type` may appear anywhere.** Two impls for the same (trait, type) pair in one program is a compile error. No orphan rules.

## Round 4 — Unions, narrowing, errors

- **Unions** are flattened and unordered (`A | (B | A)` ≡ `A | B`); aliases are transparent. Values carry a runtime type tag (no wrapper at the type level).
- **Literal types** allowed for `Str`, integers, `Bool`.
- **Narrowing** via `is`, `== literal`, `!= null`, `if let`, `match` arms, and early `return`/`break`/`continue`. Applies to locals, parameters, and immutable (`let`) fields — not `let mut` fields.
- A function whose return type includes `()` implicitly returns `()` when control reaches the end.
- `main` returns `()` or `() | E` (`E: Error`); a returned error is printed and exits with code 1.
- **Panics:** `panic(msg)` aborts the process with message + stack trace; uncatchable. Used for out-of-bounds, divide-by-zero, failed `as`, etc.
- **Casts:** `x as T` asserts (panics on failure); `x as? T` yields `T | null`. Numeric `as` conversions truncate/wrap and never panic. Prefer `is` narrowing.
- **Errors:** `trait Error { fn message(self) -> Str; }`. stdlib `class Err : Error`; `error("msg")` returns an `Err`. Specific errors are any struct/class implementing `Error` (or extending `Err`).
- **`?` (postfix):** early-returns every member of the operand's type that implements `Error`, plus `null`; result type has those removed. The enclosing function's return type must accept everything propagated. No `Try` trait in 1.0.
- **Must-use errors:** discarding a value whose type contains an `Error` member is a compile error. Discard explicitly with `_ = expr;`.

## Round 5 — Expressions and syntax

- Semicolons required. Comments: `//`, `/* */`, `///` (doc).
- **Literals/initializers:** `Vec2 { x: 1.0, y: 2.0 }` (named), `{ x: 1.0 }` (target-typed, only where the expected type is known), `{ name }` field shorthand. A class initializer is only legal where all its fields are visible (effectively inside the class); outside code calls `new`.
- **Brace rule:** no bare literal/initializer directly in an `if`/`while`/`for`/`match` head unless parenthesized; `{` there always starts the block.
- **Control flow:** `loop`, `while cond`, `for x in it` / `for mut x in it`, `break`, `continue`. Ranges `a..b`, `a..=b`.
- **`match`** is an expression; arms `pattern => expr` or `pattern => { block }`, comma-separated.
- **Conditional expression (Python-style):** `a if cond else b`. Lowest precedence above lambdas/assignment, right-associative, `else` mandatory. Narrowing applies to the true branch. No `c ? a : b` ternary — `?` means only propagation.
- **Lambdas:** `x => e`, `(a, b) => e`, `(x: I32) => e`, `mut c => e`, `x => { ... }`. Captures by reference (GC).
- **Function types:** `fn(I32, Str) -> Bool`.
- **Generic call syntax C#-style:** `foo<T>()`, `Map<Str, I32>::new()`. Parser speculatively parses type args after `Name <`; accepted when followed by `(`, `::`, or `.`. Ambiguous `f(a < b, c > (d))` resolves as a generic call (parenthesize to force comparisons). `>>` splits in type context. Union/literal types are legal type args (`Map<null | I32, Str>`).
- `|` is union in type context, bitwise OR in expression context. Bitwise ops `& | ^ ~ << >>` exist.
- **Comparisons are non-associative:** `a < b < c` is a syntax error.
- **Equality:** `==` on structs is field-wise; on classes is reference equality unless the class implements `Eq`.
- **Operator overloading** via stdlib traits: `Add Sub Mul Div Rem Neg Eq Ord Index IndexMut` (+ bitwise traits). Not overloadable: `&& || ! ?? ? is as`, assignment.
- **Tuples:** `(I32, Str)` types, `(1, "a")` values, `.0`/`.1` access, destructuring `let (a, b) = ...;`, `for (k, v) in map`. `()` is the empty tuple.

## Round 6 — Primitives, collections, patterns

- **Primitives:** `I8 I16 I32 I64 U8 U16 U32 U64 ISize USize F32 F64 Bool Char Str`, plus `()`, `null`, `Type`. `Char` = Unicode scalar. `Str` = immutable UTF-8, no integer indexing (`.chars()`, `.bytes()`).
- **Numeric literals** are context-typed; unconstrained integer → `I32`, float → `F64`. Suffixes `10_u64`, `1.5_f32`; `_` digit separators.
- **Implicit conversions:** lossless widening only (e.g. `I32→I64`, `U8→U32`, `I32→F64`); everything else needs `as`.
- **Integer overflow is a bug:** in checked/debug mode `+ - *` (and shifts) must panic on overflow; release mode may wrap. Intentional behaviour via `wrapping_*`, `checked_*` (→ `T | null`), `saturating_*` methods.
- **List repeat literal:** `[0; 4096]`.
- **Strings:** `f"...{expr}..."` interpolation, `{{`/`}}` escapes.
- **Collections** are stdlib reference types: `[T]` (growable list), `Map<K, V>`, `Set<T>`. Mutators take `mut self`, so mutation needs a `let mut` binding.
- **Collection literals** are target-typed: `[1, 2]` → `[I32]` or `Set<I32>` by context; `[]` empty for any known collection type. **Map literal:** `["bob": 30]`; `[:]` explicit empty map.
- **Patterns:** literals, `_`, bindings, `mut x`, tuples, list patterns `[first, ..]` / `[a, b, ..rest]` (rest is `..`, not `...`), struct patterns `Vec2 { x, y: 0 }`, or-patterns `"a" | "b"`, ranges `0..=9`, guards `p if p > 0`, **type patterns** `p is Position` / bare `is Velocity`.
- **`match` must be exhaustive** (over unions, literal types, `Bool`) or include `_`; otherwise compile error.

## Round 7 — C interop and runtime

- **Attributes:** `@name` / `@name(args)` on declarations.
- **Extern:** `@link("m") extern "C" { fn sqrt(x: F64) -> F64; }`. Variadic externs allowed (`...`).
- **C types:** `c` module with platform aliases `c::Int`, `c::Long`, `c::SizeT`, `c::Char`, ….
- **Pointers:** `Ptr<T>`, nullable natively (`Ptr::null()`), `.read()`, `.write(v)`, `.offset(n)`.
- **C layout:** `@c struct` has C layout. Only `@c` structs, primitives, and `Ptr<T>` cross the boundary.
- **Strings:** `s.to_cstr()` copies to native null-terminated memory; `Str::from_cstr(p)` copies back.
- **Callbacks:** top-level non-capturing `fn`s convert to `extern fn(...) -> R` types; closures cannot.
- **`unsafe` required:** extern calls and `Ptr` dereference/arithmetic must be inside `unsafe { ... }` (statement block) or `unsafe => expr` (expression form, matching the `=>` convention). `unsafe fn` marks a function whose callers need `unsafe`; extern fns are implicitly `unsafe`. Safe functions may wrap unsafe code.
- **GC memory to C:** scoped pinning `buf.with_ptr(p => ...)` — object neither moves nor is collected during the closure; C must not retain the pointer afterwards. Implementations remain free to use a moving GC.
- **Threads:** stdlib `Thread::spawn(closure)`, `.join()`, `Mutex<T>`, `Atomic<T>`, built on OS threads by the runtime (GC aware of all threads). Data races yield unspecified values, never memory corruption. Early interpreters may use a global lock. (Mutex API must hand out a guard reference or use `update(v => new_v)` — a `mut v` closure param on a value type would mutate a copy.)

## Round 8 — Modules, cleanup, reflection, entry

- **Bare blocks** `{ ... }` are statements and create a scope. A `{` at statement start is always a block; initializers `{ x: 1 }` only appear in expression position.
- **`defer expr;`** / **`defer { ... }`** runs when the enclosing **block** exits by any path (`return`, `?`, `break`, `continue`, fall-through), LIFO order. Does not run on `panic` or `process::exit`.
- **Modules:** each `.3b` file is a module named by its path; directories nest. `pub` items are exported. `use std::io::File;`, `use game::{Entity, Component};`, `use x::y as z;`.
- Module-level `static let` / `static let mut` allowed (mutable statics unsynchronized).
- **Reflection (minimal):** `typeof(T)` (type → `Type`), `x.type()` (runtime type → `Type`), `nameof(T)` → `Str`. `Type` supports `==`, hashing (usable as a Map key), `.name`, `is_subtype_of`. No member enumeration in 1.0. Implies **reified generics** (`typeof(T)`, `x is T` work for generic `T`).
- **Generics:** invariant; bounds `T: A + B`; no `where` clauses in 1.0; type args inferred from arguments where possible, else explicit.
- **Overload resolution:** arity → exact match → widening/upcast; remaining ambiguity is a compile error; no return-type-only overloads.
- **Entry point:** `fn main()` or `fn main(args: [Str])`, returning `()` or `() | E` (`E: Error`). Returned error → `message()` to stderr, exit code 1. `std::process::exit(code: I32)` exits immediately (defers do not run).

## Round 9 — Remaining gaps

- **Null-collapse fix:** stdlib unit sentinel `struct Done;`. `trait Iterator<T> { fn next(mut self) -> T | Done; }` so nullable elements iterate correctly. `Map.get(k) -> V | null`; `contains_key` disambiguates stored nulls.
- **Shadowing** allowed (Rust-style). **Static initializers** run lazily on first access. `'a'` is a `Char` literal. `+` concatenates `Str`. Integer division by zero panics.
- **Getter-only properties:** `pub get fn len(self) -> USize => ...;`, accessed without parens (`xs.len`). Traits may declare getters. No setters.
- **`trait Len { get fn len(self) -> USize; }`** implemented by `Str` and all collections (named `Len`, not `Sized`, to avoid Rust's unrelated meaning). Prelude free function `fn len<T: Len>(x: T) -> USize => x.len;` so both `len(xs)` and `xs.len` work.
- **Enums:** C-style, no payloads: `enum Color { Red, Green, Blue }`. Backing type optional (default `I32`): `enum Signal : c::Int { Int = 2, Kill = 9, Term = 15 }`. Unassigned members count up from the previous value. `as` to the backing integer; integer `as Enum` panics on an undefined value, `as?` → `Enum | null`. Exhaustive `match`.
- **`@flags` enums:** `@flags enum Perm : U8 { Read, Write, Exec }` — unassigned members get successive powers of two; `| & ^ ~` defined on the enum; `.has(flag)`; any bit combination is valid, so `match` needs `_`.
- **Default parameters + named arguments** coexist with overloading. _(pending clarification: keyword-only params?)_
- **`const NAME: T = expr;`** — compile-time evaluated; usable in literal types, enum values, `[0; N]`, `@c` array sizes. `static let` for runtime-initialized values.

## Post-interview amendments

- **Fields have no `mut`:** fields are `let name: T;` and are writable iff the access path is mutable (`mut self`, `let mut` binding). No init-only fields in 1.0 (private field + `pub get fn` instead). Narrowing does not apply to fields.
