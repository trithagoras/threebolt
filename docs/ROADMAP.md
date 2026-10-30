# Threebolt — road to 1.0

0.1 is feature-complete against `docs/PLAN.md` (interpreter, C interop, threads, std::math/random/io,
language server with warnings and docs). This document orders the remaining work and lists what is
still to be **decided**, to be worked through with the user (who owns the design). Settle an item,
then move its outcome into the spec (if it is language semantics) or `PLAN.md` (if it is
implementation), and strike it here.

**Order (agreed 2026-10-28):**
1. Runtime boundary: move std into Threebolt over a small, documented intrinsic layer; backend prerequisites.
2. Projects and packages: manifest, workspaces, path dependencies, multiple executables, `internal`, `pub use`.
3. Standard library expansion, one module per session: `time` → `env`/`process` → `net`.
4. Concurrency (spec'd 2026-10-30, §6 below): virtual threads, scopes, cancellation, channels, `select`.
   Moved ahead of the backend 2026-10-30: nearly all of it is front end and Threebolt std, shared by both
   backends, and its breaking signature changes should land before a second backend exists.
5. JVM bytecode backend, when benchmarks show a need or the language has settled.

The small open decisions (§5) are deferred, except 5.2, which is decided and lands in task 1.

---

## 1. Runtime boundary (done)

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
5. ~~**Literal defaulting to `f64`**~~ — done (`4057ed3`): spec §10.1, `Calls.literalDefault`, conformance
   `numeric/literal_default*`. No slowdown.
6. ~~**Move the rest**~~ — done as far as the spec allows (`314d457`, `4f2f406`, `59d5c4f`, `d117ad6`): collection
   `truthy`; integer `pow`/`zero`/`one`/`min_value`/`max_value`, float `zero`/`one`/`nan`/`infinity`/`epsilon`;
   `Mutex` over a private `RawLock` (plus a private `sync::guard` constructor intrinsic); `math::isqrt`/
   `factorial`/`comb`/`perm`. Panic traces from moved code now show std frames (`relock`, `math_overflow`
   goldens). **Slowdown:** `threads` ~0.87 s → ~1.6 s (`Mutex::lock` interpreted); the rest unchanged.
   **Decided 2026-10-28 (middle ground):** spec §13.4 narrowed to the primitive collection operations, so `[T]`
   `contains`/`reverse`/`first`/`last` moved to Threebolt too; `join` stays intrinsic as the linear-time way to
   build a string. `std::math`'s float functions keep `f64` signatures (generic over `Float` would break
   `math::sqrt(n)` for an `i32` variable). Concurrent collections, if wanted, belong to task 3.
7. ~~**Document the boundary**~~ — done (`3a9fae7`): `PLAN.md` § Runtime boundary; post-task timings in
   `tests/bench/README.md`.

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

## 2. Projects and packages — design (decided 2026-10-28)

Today a program is an entry file plus the modules reachable from it under its directory (spec §2.1), and
`std::` is a reserved root. None of this needs std to be finished.

### 2.1 Decided

**Names.** The language stays Threebolt (`.3b`); the tool is **`bolt`** (`bolt run`, `bolt new`, …). Puppet's
orchestration tool also installs a `bolt` binary; accepted, as the two are rarely installed together. The unit
of code is a **package**, described by **`package.toml`**; several packages form a **workspace**.

**One-off scripts stay first-class.** `bolt run script.3b` with no manifest in the current directory behaves as
today: the source root is the file's directory, plus std.

**Package = directory.** The directory holding `package.toml` is the package's **source root** (spec §2.1 changes:
today the root is the entry file's directory). Every entry file is a module in it, so `scripts/seed.3b` is the
module `scripts::seed` and can `use game::entity` and the package's dependencies. There is no `[lib]` section and
no `lib.3b` convention: a package is a library simply by being depended on, and its importable modules are those
under its root. Bins are listed explicitly; nothing assumes `main.3b`.

```toml
# server/package.toml
[package]
name = "server"

[[bin]]
name = "server"
entry = "main.3b"

[dependencies]
shared = { path = "../shared" }
```

**Finding the package: current directory only, never walking up.** `bolt run`/`check` use `./package.toml` if
present, or `--project <dir>`. Run from a subdirectory, a failed import may add a hint naming a manifest found
above ("run from there or pass `--project ..`"); that lookup only produces the hint, never changes behaviour.
The language server does the same from the editor's open folder (and each workspace member), replacing the
"nearest `main.3b`" search in `lsp/Workspace.java`.

**Dependencies.**
- Path dependencies only for now: no git, no semver, no lockfile; those come with a registry (or a git need).
  The `{ path = … }` table form leaves room for `{ git = … }` and version strings later. Meanwhile git
  submodules plus path dependencies cover sharing across repos.
- The `[dependencies]` key is the module root the dependency is imported under (`use shared::model::User;`).
- Package names and dependency keys are identifiers (snake_case by convention, no hyphens), never `std`.
- A key that clashes with a top-level module or directory of the depending package ⇒ error naming both; fix it
  by renaming the key.
- Package identity is the canonical path: two packages depending on `../shared` share one copy.
- Module cycles are allowed inside a package; the package graph must be a DAG (a cycle is an error naming it).

**Visibility: new keyword `internal`** — visible anywhere in the declaring package, for items and members. Std's
special rule (private members visible within std/builtin) becomes plain `internal`. In a manifest-less script the
program is one package, so `internal` ≡ `pub` there.

**Re-exports return as `pub use`.** A `use` is an item; a visibility modifier on it makes the imported name visible
to other modules as if declared there (`pub use model::user::User;`, `internal use …`). Same forms as `use`,
including globs, with the existing shadowing rules. Gives libraries a facade independent of their file layout.

**`bolt new`.** No `bolt init`. `bolt new <name>` run in a workspace root adds the new package to
its members and says so (only the current directory is checked).

**CLI (this phase).**

| Command | |
|---|---|
| `bolt new <name> [--lib \| --workspace]` | `--lib`: no bin scaffolded; default scaffolds `main.3b` hello world and its `[[bin]]` |
| `bolt add <path>` | adds a path dependency, keyed by the dependency's package name |
| `bolt run [bin \| file.3b] [-- args]` | `*.3b` ⇒ file, else a bin name; no argument and one bin ⇒ that bin; bin names unique per workspace |
| `bolt check [bin \| file.3b]` | |
| `bolt debug tokens\|parse\|ast\|print <file>` | today's inspection commands |
| `bolt lsp` | |

Later: `bolt test` (an `@test fn` attribute; its own phase). Not planned: `build` (nothing to build until a
backend), `fmt`.

**Native code.** Libraries load at runtime through FFM (closest to C#'s `DllImport` + NuGet's `runtimes/<rid>/native/`).
`@link("name")` in source stays the only declaration. A package may ship **prebuilt** libraries and list
per-platform search directories in `package.toml` (`[native]`; key set settled at implementation); the loader
searches those, then the system. No build scripts and no compiling C: if a library must be built, the package's
README says how to install it.

**Workspaces.** A workspace root holds **`workspace.toml`** (`[workspace] members = [...]`), as Go keeps `go.work`
beside `go.mod`; a directory holding both `package.toml` and `workspace.toml` ⇒ error.

**Orphan rule, with the application exempt.** An impl must live in the package that declares the trait or the
target's type constructor, except in the **application package** (the package of the entry being run), whose
orphan impls can only conflict at an upgrade its own author chooses. A package containing orphan impls therefore
cannot be used as a dependency (error at the impl, naming the dependent). Rust applies the rule everywhere;
the exemption keeps "add `Display` to a dependency's type" a one-liner in apps.

**Manifest parsing** uses a TOML library (the third dependency, after ANTLR and LSP4J).

### 2.2 Steps
1. ~~Spec~~ — done (`fdb3b8e`): §1 keyword `internal`; §2.1 packages and source roots; §2.2 `pub use`; §2.3 `internal`; §4.10 orphan rule
   (normative, application exempt); intro's "Not in 1.0" loses re-exports and package manager; changelog entry.
2. ~~`internal` and `pub use` in grammar and checker (testable without packages); TextMate grammar~~ — done (`3e99db4`).
   Std's private `str` helpers are `internal`, replacing the std/builtin visibility special case. Re-exports resolve
   on demand in `ModuleLoader` (`resolveUse`/`reexport`), so they chain and may form cycles; a glob re-exports each
   name at the lesser visibility. `Module.samePackage` is a placeholder (std vs everything else) until step 3.
3. ~~Manifest loading, package graph, per-package source roots, `internal` across packages, orphan rule~~ — done
   (`dbdcd65`, `6fad8fe`, `d2ab70d`; spec `75ec21f`: a nested package is not part of its parent). `project.Manifest`
   parses `package.toml`/`workspace.toml` with night-config 3.9.0. A `resolve.Package` has a root and dependencies by
   key; `std` is `Package.STD`. A `Module` has its package and local path, and `path()` is for display
   (`shared::model`). Paths resolve per package (`Package.locate`), as in `Program.module(from, path)`. Diagnostics
   name files relative to the application root (`../shared/m.3b`). New codes: E0111 manifest, E0112 names, E0113 key
   clash, E0114 cycle, E0412 orphan impl. E0100 notes a nested package or a key of another package. Lints cover only
   the application package. Conformance: `tests/conformance/packages/`.
4. ~~CLI~~ — done (`74d0fd3`): `bolt new|add|run|check|debug|lsp`, `--project`, the hint. A `.3b` file outside every
   package of the project runs as a script. Program arguments only follow `--`. `bolt check` with no target checks
   every bin, and a package without bins as a library (`ModuleLoader.loadLibrary`). Not done: `[native]` (its key set
   is still to settle, with the first package that ships a prebuilt library).
5. ~~Language server~~ — done (`b325358`): a file's package is the nearest `package.toml` at or above it within the
   open folder, or a member of the folder's workspace. This walks up where the CLI does not, so the conformance packages
   in this repo check in the editor. Files outside any package keep the nearest-`main.3b` rule. Manifest errors
   publish on `package.toml`. The extension activates on manifests and forwards their changes.

## 3. Standard library expansion

`std::time`, `std::env`, `std::net`, following the task-1 pattern: Threebolt over thin intrinsics, errors as
`T | SomeErr` unions, Python-flavoured names. One module (one design section, one session) at a time.

- **`std::time`** — done 2026-10-28 (`docs/plans/std-time-net.md`): `Duration` as signed `i64` nanoseconds, `Instant` + `Timestamp` (wall clock), `sleep(Duration)`, UTC/zones/formatting left to a later `datetime`. Original questions (first: `Duration` is needed by sleep and net timeouts): one `Instant` (monotonic) and one
  `DateTime` (wall clock), or Python's split (`time.time()`, `time.monotonic()`, `datetime`)? `Duration` as a
  struct of nanoseconds (`i64`)? Time zones: UTC + local only for 1.0? Formatting/parsing (`strftime`-style
  patterns or ISO 8601 only)? Move `thread::sleep(ms)` to take a `Duration`?
- **`std::env`**: `args()` (today only via `main(args)`), `var(name) -> str | null`, `set_var`, `vars()`,
  `cwd()`/`set_cwd()`, `exit` (currently `std::process::exit`: merge `process` into `env`, or keep both?).
  Running subprocesses (`process::run(cmd, args) -> Output | IoErr`)?
- **`std::net`** — done 2026-10-28 (`docs/plans/std-time-net.md`): addresses parsed in Threebolt, blocking TCP
  (`serve` spawns a thread per connection) and UDP, `lookup`; `NetErr : IoErr`; `File` and `TcpStream` share
  `std::io`'s `Read`/`Write` traits (pure contracts, buffering shared by composition). HTTP/TLS: later packages.
  Original questions: blocking TCP (`TcpListener::bind`, `accept`, `TcpStream::connect`, `read`/`write` like
  `File`) and UDP. With no async (non-negotiable), concurrency is threads; is that the story, with a
  `serve(addr, handler)` helper that spawns per connection? DNS lookup? HTTP client in std or a package?
  TLS: out of scope for 1.0? Decide here whether `TcpStream` and `File` share `Read`/`Write` traits (affects
  `std::io`'s existing API).
- **Cross-cutting**: f-string format specifiers (`{x:.2}`, `{n:>5}`) are a language change std would want;
  decide alongside. `StrBuilder`?
- Also missing from std that programs reach for: sorting (`[T].sort()` with `Ord`, `sort_by`), `min`/`max`
  over iterators, `zip`, `take`/`skip`, `sum`, string `format`/padding. (JSON: decided, `std::json`, spec §16.)

## 4. Execution backend

**Decision: JVM bytecode** via Java 25's `java.lang.classfile` API, compiling `ir.Ir` to classes: the JIT and
GC for free, FFM interop unchanged, and Java intrinsics callable directly. Rejected: an own bytecode VM
(an interpreter loop on the JVM is slower than generated classes) and native code via LLVM/C (needs its own
GC and runtime; far beyond 1.0).

**Timing: after 1–3 and concurrency (§6)**, when the task-1 benchmarks show performance hurting or the language has settled.
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

## 6. Concurrency — **spec'd 2026-10-30; not implemented**

Spec §1 (contextual `select`), §6.4 (`for p? in`), §7.14 (`select`), §13.4, §16; rationale, JVM mapping and
risks in `docs/plans/concurrency.md`. 0.1 still implements the old §13.4 (platform threads, `join() -> R`,
`sleep -> ()`). Suggested steps, each with conformance cases and an errors case:
1. ~~**Virtual threads**~~ — done: `Interpreter.threads` builds every Threebolt thread, `main` included, as a
   virtual thread. Their stacks are capped by `-Xss` (measured: ~38K Java frames by default, ~44M with `-Xss1g`,
   as a 1 GiB platform thread), so the launcher and test JVMs pass `-Xss1g`; without it, 1 GiB platform
   threads as before. Conformance `threads/virtual` (deep recursion on a thread, 10,000 threads). Benchmarks
   unchanged.
2. ~~**Cancellation and scopes**~~ — done: `std::thread` `CancelledErr`, `cancel`, `cancelled`, `check`,
   `finished`, `join(timeout)`, `scope`/`Scope`; `join`/`sleep` return `| CancelledErr`; `TimedOutErr : Err` in
   `std::time`. Conformance `threads/cancel`, `scopes`, `scope_ended`, `cancel_errors`. Left for step 4: `std::net`
   still has its own `TimedOutErr : NetErr` (moving it changes the socket result types), and files are
   `FileChannel`s, which an interrupt (a `cancel`) closes.
3. ~~**`std::sync`**~~ — done: `Semaphore`, `Sender`/`Receiver`, `Channel` over `interp/Channels.java` (per-channel
   lock, buffer and wait queues; a wait on several cases is won by one compare-and-set, ready for `select`).
   Conformance `threads/channels`, `channel_errors`; unit `ChannelsTest`.
4. **`std::io`/`std::net` cancellation points**: `CancelledErr` in every result, entry checks, chunked file
   loops, socket interruption; `serve` with `max_connections` and its own scope.
5. **`for p? in e`**: grammar, AST, checker, interpreter, LSP, TextMate grammar (VS Code bump).
6. **`select`**: contextual keyword in the grammar, case forms in the checker, runtime intrinsic.
   Then switch the spec's §7.14 snippet fence from `rust` to `rs` so `ParserAcceptanceTest` covers it,
   and put the `for line? in` example back in §6.4's loop block.
7. Make `examples/chatroom/` type-check and run, using `for x? in`.
