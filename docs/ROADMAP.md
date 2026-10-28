# Threebolt — road to 1.0: open decisions

0.1 is feature-complete against `docs/PLAN.md` (interpreter, C interop, threads, std::math/random/io,
language server with warnings and docs). This document lists what is still to be **decided**
before more is built, one section per topic, to be worked through with the user (who owns the
design). Each item gives the question, the options, and a recommendation. Settle an item, then
move its outcome into the spec (if it is language semantics) or `PLAN.md` (if it is implementation),
and strike it here.

Suggested order: 1 (small, unblocks std) → 2 (std design doc) → 3 (backend prerequisites only)
→ 4 (packages, last: needs a stable std and module story).

---

## 1. Small open decisions

### 1.1 `with_ptr` visibility (spec §14.5)
`with_ptr` copies a list/array/`@c struct` into native memory for the duration of `f` and back
afterwards, so writes through the pointer are not visible through `x` *during* `f`; the spec says
they are. Also observed: `with_ptr` writes back into an immutable `let` array (it is a value type),
so `mut` is not required to mutate through it.
- **A. Relax the spec**: "writes become visible when `f` returns". Matches the implementation; simple.
- **B. Implement real pinning**: back the collection by a `MemorySegment` while pinned, so reads
  inside `f` see it. Costly: every list/array access must check for a pinned backing store.
- **Recommendation:** A, and decide separately whether `with_ptr` on a value should require a `mut` place
  (it does write the value), which would be a new compile error for array/struct receivers.

### 1.2 Literal defaulting to `f64`
A literal-only type argument whose bounds `i32` does not satisfy (`T: Float`) could default to
`f64` (Haskell-style). With it, `std::math`'s float functions and `random::uniform` could be generic
over `Float` without breaking `math::sqrt(2)`.
- **Recommendation:** yes, narrowly: only when every argument for `T` is a literal and `i32` fails `T`'s bounds.

### 1.3 Warning conventions in the spec
Warnings (W0001–W0006, `PLAN.md` § Warnings) are tooling, not semantics, so the spec does not
mention them. One convention leaks into how people write code: **a name starting with `_` is never
reported unused**.
- **Recommendation:** add one informative sentence to spec §1 (identifiers) so it is not a hidden rule;
  keep the warning list itself in `PLAN.md`.
- Also: should `///` that documents nothing (inside a body, before a statement) warn? Cheap to add.

---

## 2. Standard library expansion

Agreed in principle: `std::net`, `std::time`, `std::env`. To decide in a design doc (one section each),
following the `std::io` pattern: written in Threebolt over a thin layer of POSIX-like intrinsics
(`interp/Io.java`, owner `io` in `Natives`), errors as `T | SomeErr` unions, Python-flavoured names.

Questions per module:
- **`std::time`**: one `Instant` (monotonic) and one `DateTime` (wall clock), or Python's split
  (`time.time()`, `time.monotonic()`, `datetime`)? `Duration` as a struct of nanoseconds (`i64`)? Time
  zones: UTC + local only for 1.0? Formatting/parsing (`strftime`-style patterns or ISO 8601 only)?
  Move `thread::sleep(ms)` to take a `Duration`?
- **`std::env`**: `args()` (today only via `main(args)`), `var(name) -> str | null`, `set_var`, `vars()`,
  `cwd()`/`set_cwd()`, `exit` (currently `std::process::exit`: merge `process` into `env`, or keep both?).
  Running subprocesses (`process::run(cmd, args) -> Output | IoErr`)?
- **`std::net`**: blocking TCP (`TcpListener::bind`, `accept`, `TcpStream::connect`, `read`/`write` like
  `File`) and UDP. With no async (non-negotiable), concurrency is threads; is that the story, with a
  `serve(addr, handler)` helper that spawns per connection? DNS lookup? HTTP client in std or a package?
  TLS: out of scope for 1.0? Should `TcpStream` share a `Stream`/`Read`/`Write` trait with `File`
  (today `File` methods are concrete)?
- **Cross-cutting**: introduce `Read`/`Write` traits now (affects `std::io`'s existing API)? A `Duration`
  type is needed by net timeouts and sleep, so time comes first. String formatting specifiers in
  f-strings (`{x:.2}`, `{n:>5}`) are a language change that std would want; decide alongside.
- Also missing from std that programs reach for: sorting (`[T].sort()` with `Ord`, `sort_by`), `min`/`max`
  over iterators, `zip`, `take`/`skip`, `sum`, string `format`/padding, JSON (std or package?).

## 3. Execution backend (bytecode / compilation)

**Question:** does growing the stdlib make a later bytecode backend harder, i.e. should the backend come
sooner? **Answer from the 2026-10-28 review: no, do std first**, because:
- Std is mostly Threebolt, lowered to the same `ir.Ir` as user code; a new backend compiles it for free.
  The front end (parser, checker, IR) is unaffected by the backend choice.
- The coupling is the **intrinsic table** (`interp/Natives`, `Io`, `Ffi`). Intrinsics that only take and
  return values carry over unchanged. Intrinsics that call back into Threebolt (sorting with a
  comparator, `Thread::spawn`, `with_ptr`, C upcalls) depend on the tree-walker's calling convention.
- The conformance suite is the oracle: a new backend must produce identical output on every case, so a
  bigger std makes the switch *safer*.

**Prerequisites worth doing now (cheap):**
1. Route every callback from an intrinsic through one interface (`call(FnVal, args) -> Object`) instead
   of reaching into `Interpreter` directly.
2. Keep intrinsics value-in/value-out; no AST or frame access.
3. Add a small benchmark set (`tests/bench/`, not run by `test`) to measure against later.

**Options for later:**
- **A. JVM bytecode** via Java 25's `java.lang.classfile` API: compile `Ir` to classes, get the JIT and GC
  for free; FFM interop stays as is. Biggest speedup per effort. Unions/boxing map to `Object`.
- **B. Own bytecode VM** in Java: more control, a portable format, but an interpreter loop on the JVM is
  slower than A.
- **C. Native** (LLVM/C emission): needs a GC and runtime of its own; far beyond 1.0.
- **Recommendation:** A, after std; decide timing when performance actually hurts (DevX over performance).

## 4. Libraries, packages, dependencies, build tooling

Keep in mind for the 1.0 plan; nothing blocks on it yet. Today a program is an entry file plus the
modules under its directory (§2.1), and `std::` is a reserved root.

To decide:
- **Manifest**: file name and format (`threebolt.toml`?), what it holds (name, version, entry, dependencies,
  C libraries for `@link`).
- **Dependency → module mapping**: a dependency becomes a top-level module root like `std` (`use json::parse;`)?
  Name clashes with local directories?
- **Sources**: local paths, git URLs, a registry (later)? Lockfile with exact revisions/hashes.
- **Versioning**: semver ranges; one version per package per program (simplest) or several?
- **Visibility**: is `pub` enough at package boundaries, or is a package-private level needed?
- **Build tool**: the `threebolt` CLI itself (`threebolt new`, `add`, `run`, `test`, `check`, `fmt`) rather
  than a separate tool. A test runner (`#[test]`-like attribute? `tests/` directory convention?) belongs here.
- **Native code**: how a package declares the C libraries it links, and whether packages may ship C.
- Editor: the language server finds the project root via the manifest instead of the nearest `main.3b`.
