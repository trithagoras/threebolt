# Threebolt — road to 1.0

0.1 is feature-complete against `docs/PLAN.md` (interpreter, C interop, threads, std::math/random/io,
language server with warnings and docs). This document orders the remaining work and lists what is
still to be **decided**, to be worked through with the user (who owns the design). Settle an item,
then move its outcome into the spec (if it is language semantics) or `PLAN.md` (if it is
implementation), and strike it here.

**Order (agreed 2026-10-28):**
1. Runtime boundary: move std into Threebolt over a small, documented intrinsic layer; backend prerequisites.
2. Projects and packages: manifest, workspaces, dependencies, lockfile, multiple executables.
3. Standard library expansion, one module per session: `time` → `env`/`process` → `net`.
4. JVM bytecode backend, when benchmarks show a need or the language has settled.

The small open decisions (§5) are deferred, except 5.2, which is decided and lands in task 1.

---

## 1. Runtime boundary (next)

**Goal:** as much of std as possible written in Threebolt, over an intrinsic layer that is small, explicit
and value-in/value-out, so a future backend reimplements only that layer. Today ~370 std functions are
bodyless (Java): all of `builtin/{str,int,float,char}.3b`, `thread.3b` and `sync.3b`, 97 of 114 in `prelude.3b`,
47 of 82 in `io.3b`. (For the JVM backend Java intrinsics port almost for free; the boundary pays off for any
other backend and for dogfooding the language.)

**Classify every intrinsic:**
- **Primitive operations**: integer/float arithmetic, bit operations, comparisons, conversions. Every backend
  emits these directly. Stay intrinsic.
- **Host capabilities**: what only the platform can do: OS threads, atomics, a raw lock, system calls (`sys_*`
  as in `io.3b`), FFI, libm (`sin`, `sqrt`, …), Unicode tables (case mapping, `char::is_*`), the representation
  core of `str` and the collections (§1.1). Stay intrinsic, as thin as possible.
- **Derivable**: everything expressible in Threebolt over the two above. Moves to `.3b`. Examples: most `[T]`,
  `Map` and `Set` helpers in the prelude; integer `abs`/`pow`/`min`/`max`/`clamp`; `Mutex`/`Guard` over a private
  raw-lock intrinsic (the "store `value` back when `f` returns" and "re-locking panics" rules move into
  Threebolt); `str`'s `trim`, `split`, `replace`, `repeat`, `starts_with`, `ends_with`, `find`, `contains`, `truthy`.

**Steps:**
1. ~~**Benchmarks first**~~ — done (`4d29fa5`): `tests/bench/` (five programs printing checksums),
   `./gradlew bench [-Pbench=name]`, baseline times in `tests/bench/README.md`. Re-run after each step;
   a changed checksum is a bug, a slower time is a cost to note.
2. ~~**One callback interface**~~ — done (`5504a3b`): `interp.Callbacks` (`call(FnVal, args)`, `callTrait`,
   `equal`/`hash`/`display`/`next`, process hooks) is all `Natives`, `Threads`, `Ffi` and `Io` see; `BoundaryTest`
   enforces it. No measurable slowdown.
3. ~~**Bodied members in `builtin/*.3b`**~~ — done (`81244d2`): bodied members are checked once per owner type;
   private members are visible only within std/builtin. Moved: integer `abs`/`min`/`max`, `char::is_digit`,
   `flags::has`. No measurable slowdown.
4. ~~**`str` representation** (§1.1), then move the derivable `str` members~~ — done (`f4a2af9`, `fe52fbf`):
   `Values.Str` (O(1) `len`/`s[i]`, spec §3.1 changed); `trim`, `split`, `replace`, `repeat`, `starts_with`,
   `ends_with`, `find`, `contains`, `truthy`, `to_str` are Threebolt. **Slowdown:** the `strings` benchmark goes
   from ~1.9 s to ~31 s (~16×): searching now runs character by character in the tree-walker. The other
   benchmarks are unchanged. Candidates if it matters before a backend: a faster evaluator (slot-indexed locals
   instead of `HashMap` environments, no exception per `return`), or keeping `find_from` as an intrinsic.
5. **Literal defaulting to `f64`** (§5.2, decided): spec, checker and conformance cases, before `std::math` moves,
   so its float functions can become generic over `Float` as they move.
6. **Move the rest** module by module (prelude collections, `int`/`float`, `sync`), each with the conformance
   suite as the oracle. Behaviour must not change; `.err` goldens may only change if a diagnostic's wording or
   span legitimately moves.
7. **Document the boundary**: a `PLAN.md` section listing every remaining intrinsic, its group, and its
   contract (arguments, result, panics), i.e. what a new backend must provide.

### 1.1 `str` representation — **decided 2026-10-28: C**

Spec §3.1 changes alongside the implementation (step 4): "may take time linear" becomes "constant time" for
`len`, `s[i]` and slicing, with a changelog entry. The extra memory is a cached character count per string,
plus an offset table only for strings containing characters outside the BMP; the exact layout is an
implementation detail. The options as considered:

Spec §3.1: a `str` is a sequence of characters (Unicode scalar values) and every length, index and offset
counts characters, Python-style; "operations by index may take time linear in the string's length". The
interpreter stores a `str` as a `java.lang.String` (UTF-16), so `len`, `s[i]` and `s[a..b]` walk code points:
O(n). Consequences: `for i in 0..s.len { … s[i] … }` is quadratic in user code today, and std code written in
Threebolt over indexing would be too.

- **A. Keep the representation; write std over iteration.** Moved members use `chars()` and `str::from_chars`
  instead of indexing. No spec or representation change. But algorithms that need random access or
  backtracking (`find`, `split`/`replace` with multi-character separators) get awkward, and the quadratic
  `s[i]` trap in user code stays.
- **B. UTF-8 with byte offsets (Rust/Go).** Lengths and offsets count bytes; `s[i]` goes away or returns a byte.
  The natural representation for a native backend and C interop. But a language change that reverses an
  earlier decision (the changelog moved `str` *away* from byte offsets), and un-Pythonic.
- **C. Keep character semantics; guarantee O(1) `len`, `s[i]` and slicing (Python's PEP 393 approach).**
  A `Str` runtime value wraps the `String` plus its character count; strings with characters outside the BMP
  (rare) also carry an index of code-point offsets, so indexing is O(1) for every string. The spec's
  "may take time linear" becomes "constant time". Costs one interpreter refactor (`String` is used as the
  value directly: ~17 casts in `Ops`, `Io`, `Ffi`, `Natives`, `Interpreter`, plus map keys and f-strings), with
  the conformance suite as the oracle. A native backend can use PEP 393's flexible 1/2/4-byte layout.
- **D. Opaque string indices (Swift's `String.Index`).** Correct and efficient but a large API change and
  heavier to use; rejected for DevX.

**Recommendation: C.** It keeps the semantics users see, removes the quadratic trap for user code as well as
std, and lets the derivable members be plain index-based Threebolt. Under C the `str` intrinsics are:
`len`, `index`, `slice`, `add`, `eq`, `cmp`, `hash`, `chars`, `bytes`, `from_chars`, `from_utf8`, `to_cstr`,
`from_cstr`, `upper`, `lower` (Unicode case mapping can change the length: `"ß".upper()` is `"SS"`).
Repeated `+` in a loop stays quadratic; `[str].join("")` or `str::from_chars` over a `[char]` are the
idioms, and a `StrBuilder` can be decided with the std expansion.

## 2. Projects and packages

Today a program is an entry file plus the modules reachable from it under its directory (spec §2.1), and
`std::` is a reserved root. None of this needs std to be finished.

**Agreed direction:**
- **One-off scripts stay first-class.** `threebolt run script.3b` with no manifest behaves as today: the source
  root is the file's directory, plus std.
- **Module cycles are allowed inside a package; package cycles are an error.** Cyclic imports between modules
  already work (§2.2: resolution is whole-program, statics initialise lazily), which is the C# assembly / Rust
  crate model and is not Python's problem (Python's comes from imports executing at import time against
  half-initialised modules). The package dependency graph must be a DAG; a cycle is a compile error naming it.
- **Cargo-shaped manifest and lockfile; workspaces for multi-package repos.** A package has at most one library
  and any number of executables:

  ```toml
  # threebolt.toml at the repo root
  [workspace]
  members = ["shared", "server", "client"]

  # server/threebolt.toml
  [package]
  name = "server"
  version = "0.1.0"

  [[bin]]
  name = "server"
  entry = "main.3b"

  [dependencies]
  shared = { path = "../shared" }
  ```

  `server` and `client` are executables that cannot see each other; both use `shared` as a library. The
  lockfile (`threebolt.lock`) records exact revisions and content hashes.

**To decide (in a design doc before building):**
- Manifest name (`threebolt.toml`) and fields: library entry (`lib.3b` by convention?), `[[bin]]` defaults
  (`main.3b` alone ⇒ one bin named after the package?), C libraries for `@link`.
- **Dependency → module mapping**: a dependency becomes a top-level module root like `std` (`use shared::model::User;`).
  Clash with a local directory of the same name ⇒ error?
- Does `threebolt run script.3b` *inside* a project use the project's dependencies? (Recommendation: yes, if a
  manifest is found above it.)
- Sources: local paths and git for now; a registry later. Versioning: semver ranges, one version per package
  per program.
- Visibility: is `pub` enough at package boundaries, or is a package-private level needed?
- CLI: `threebolt new`, `add`, `run [bin]`, `build`, `test`, `check`, `fmt`. A test runner (`#[test]`-like
  attribute? `tests/` convention?) belongs here.
- Native code: how a package declares the C libraries it links, and whether packages may ship C.
- Language server: find the project root via the manifest instead of the nearest `main.3b`.

## 3. Standard library expansion

`std::time`, `std::env`, `std::net`, following the task-1 pattern: Threebolt over thin intrinsics, errors as
`T | SomeErr` unions, Python-flavoured names. One module (one design section, one session) at a time.

- **`std::time`** (first: `Duration` is needed by sleep and net timeouts): one `Instant` (monotonic) and one
  `DateTime` (wall clock), or Python's split (`time.time()`, `time.monotonic()`, `datetime`)? `Duration` as a
  struct of nanoseconds (`i64`)? Time zones: UTC + local only for 1.0? Formatting/parsing (`strftime`-style
  patterns or ISO 8601 only)? Move `thread::sleep(ms)` to take a `Duration`?
- **`std::env`**: `args()` (today only via `main(args)`), `var(name) -> str | null`, `set_var`, `vars()`,
  `cwd()`/`set_cwd()`, `exit` (currently `std::process::exit`: merge `process` into `env`, or keep both?).
  Running subprocesses (`process::run(cmd, args) -> Output | IoErr`)?
- **`std::net`**: blocking TCP (`TcpListener::bind`, `accept`, `TcpStream::connect`, `read`/`write` like
  `File`) and UDP. With no async (non-negotiable), concurrency is threads; is that the story, with a
  `serve(addr, handler)` helper that spawns per connection? DNS lookup? HTTP client in std or a package?
  TLS: out of scope for 1.0? Decide here whether `TcpStream` and `File` share `Read`/`Write` traits (affects
  `std::io`'s existing API).
- **Cross-cutting**: f-string format specifiers (`{x:.2}`, `{n:>5}`) are a language change std would want;
  decide alongside. `StrBuilder`?
- Also missing from std that programs reach for: sorting (`[T].sort()` with `Ord`, `sort_by`), `min`/`max`
  over iterators, `zip`, `take`/`skip`, `sum`, string `format`/padding, JSON (std or package?).

## 4. Execution backend

**Decision: JVM bytecode** via Java 25's `java.lang.classfile` API, compiling `ir.Ir` to classes: the JIT and
GC for free, FFM interop unchanged, and Java intrinsics callable directly. Rejected: an own bytecode VM
(an interpreter loop on the JVM is slower than generated classes) and native code via LLVM/C (needs its own
GC and runtime; far beyond 1.0).

**Timing: after 1–3**, when the task-1 benchmarks show performance hurting or the language has settled.
Building it earlier means implementing every language change twice (interpreter and codegen) unless the
interpreter is retired. Known hard parts: struct value semantics (no value types on the JVM: explicit copies),
generics under erasure, unions boxed as `Object`, closures, panics, and intrinsics that call back into
Threebolt (made uniform by task 1's callback interface). The conformance suite is the oracle: the backend
must produce identical output on every case.

## 5. Small open decisions (deferred)

### 5.1 `with_ptr` visibility (spec §14.5)
`with_ptr` copies a list/array/`@c struct` into native memory for the duration of `f` and back
afterwards, so writes through the pointer are not visible through `x` *during* `f`; the spec says
they are. Also observed: `with_ptr` writes back into an immutable `let` array (it is a value type),
so `mut` is not required to mutate through it.
- **A. Relax the spec**: "writes become visible when `f` returns". Matches the implementation; simple.
- **B. Implement real pinning**: back the collection by a `MemorySegment` while pinned, so reads
  inside `f` see it. Costly: every list/array access must check for a pinned backing store.
- **Recommendation:** A, and decide separately whether `with_ptr` on a value should require a `mut` place
  (it does write the value), which would be a new compile error for array/struct receivers.

### 5.2 Literal defaulting to `f64` — **decided 2026-10-28: yes, narrowly**; implemented (spec §10.1)
A literal-only type argument whose bounds `i32` does not satisfy (`T: Float`) could default to
`f64` (Haskell-style). With it, `std::math`'s float functions and `random::uniform` could be generic
over `Float` without breaking `math::sqrt(2)`. Worth settling before task 1 moves `std::math`, since it
decides those signatures.
- **Recommendation:** yes, narrowly: only when every argument for `T` is a literal and `i32` fails `T`'s bounds.

### 5.3 Warning conventions in the spec
Warnings (W0001–W0006, `PLAN.md` § Warnings) are tooling, not semantics, so the spec does not
mention them. One convention leaks into how people write code: **a name starting with `_` is never
reported unused**.
- **Recommendation:** add one informative sentence to spec §1 (identifiers) so it is not a hidden rule;
  keep the warning list itself in `PLAN.md`.
- Also: should `///` that documents nothing (inside a body, before a statement) warn? Cheap to add.
