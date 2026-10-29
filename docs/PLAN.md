# Threebolt 0.1 — Implementation Plan

Target: a working **tree-walking interpreter** for the language in `docs/spec/threebolt-1.0.md` (the spec). 0.1 implements the spec incrementally; spec changes discovered during implementation are made **in the spec first**, then in code, and noted in the spec changelog (add a `## Changelog` section at the bottom of the spec on first change).

## Stack

- **JDK 25+** (bytecode targets Java 25 LTS via `--release 25`; developed on JDK 27), Gradle 9 (Kotlin DSL) with the built-in `antlr` plugin, **ANTLR 4.13.2**, JUnit 5.
- Why Java: ANTLR's primary target with the best documentation; the JVM provides the GC, OS threads, and the **FFM API** (`java.lang.foreign`, final since Java 22), which gives runtime-defined C calls (incl. variadics), native memory, and upcalls (callbacks) with no JNI/C code. That covers spec §13–14 almost for free. A native backend (C or LLVM IR emission) can reuse the same front end later.
- Prereqs: a JDK ≥ 25 and Gradle (only to bootstrap; then use `./gradlew`). ANTLR is fetched by Gradle — no separate install.

## Repository layout

```
threebolt/
  CLAUDE.md, README.md
  docs/spec/threebolt-1.0.md, docs/PLAN.md, docs/history/
  examples/
  settings.gradle.kts, build.gradle.kts, gradlew
  src/main/antlr/dev/threebolt/parser/ThreeboltLexer.g4, ThreeboltParser.g4
  src/main/java/dev/threebolt/
    Main.java                 // CLI `bolt`: new, add, run [bin | file.3b] [-- args], check, debug tokens|parse|ast|print, lsp
    project/                  // package.toml / workspace.toml (night-config), finding the project, `bolt new`/`add`
    ast/                      // sealed-interface + record AST, Span
    parse/                    // ParseTree -> AST builder, error listener
    resolve/                  // packages, modules, `use`, scopes, symbol tables
    types/                    // type representations, checker, narrowing, overloads
    interp/                   // tree-walking evaluator, runtime values
    runtime/                  // stdlib/prelude natives, FFI (FFM), threads
  src/test/java/...           // unit tests
  tests/conformance/          // *.3b programs + expected stdout/stderr/exit code; compile-error cases with expected diagnostic
```

## Phases

Each phase ends with green tests. Work in **vertical slices** after Phase 3: each feature goes through checker *and* interpreter together, because semantics (overloads, target-typed literals, numeric literal typing, narrowing) depend on static types.

### Phase 1 — Lexer ✅
- `ThreeboltLexer.g4`: keywords, identifiers, all literal forms (§1) incl. suffixes and `_` separators, comments (nested block comments), operators (`as?` single token).
- F-strings via lexer modes: `f"` pushes `FSTRING` mode; `{` pushes default mode with brace-depth tracking; `}` at depth 0 pops. `{{`/`}}` escapes.
- `>` is always a single `GT` token — no `>=`, `>>` or `>>=` tokens. The parser combines adjacent `>` `=` / `>` `>` / `>` `>` `=` (checking no whitespace via token positions). Simplest fix for nested generics, and also covers `fn f() -> Box<T>=> x`.
- The lexer never fails: malformed input becomes an `INVALID_*` / `UNTERMINATED_*` / `UNEXPECTED_CHAR` token; `parse.LexErrors` turns those into messages. Doc comments go to the `DOC` channel.
- Literal *values* (escape decoding, `\u{…}` scalar-range check, integer parsing) are left to the AST builder (Phase 3).
- Tests: token-stream golden tests for tricky inputs (`src/test/resources/lexer`, regenerate with `./gradlew test -PupdateGolden`).

### Phase 2 — Parser ✅
- `ThreeboltParser.g4` covers spec §2–§11. One rule per precedence level of §7.2 (left-recursive for left-associative levels; comparisons and ranges take an optional operator suffix, so they don't chain).
- Generic-args disambiguation (§7.5): `typeArgsAhead()` runs a speculative `typeArgs` parse on a throwaway parser over the same token stream and checks the follow token is `(` or `::`. **Pitfall:** ANTLR only evaluates a predicate at the left edge of the decision being made; a decision further out that looks ahead through it treats it as true. So alternatives must not share an expression prefix that contains a path. `comparisonExpr` uses an optional suffix for this reason (as separate alternatives, it parsed `f(a < b, c > (d))` as comparisons).
- §6.3 head restriction: `noStructExpr` pushes "forbidden" on a stack in the parser; `block`, `callArgs`, `nested` (parens/index/tuples), `initializer`, `collectionLiteral`, `matchBody` and `fstring` push "allowed" (`@init`/`finally`). The initializer alternatives in `primary` are guarded by `structOk()`; collection literals are always allowed.
- Statement-initial `{` is a block (`exprStmt`/`defer` predicate). After `=>`, `{` is an initializer in fn bodies and a block in lambdas and match arms. `match` statements need no `;`.
- `>=`, `>>`, `>>=` are adjacent `GT`/`ASSIGN` tokens checked by `adjacent()` predicates with `<fail=...>` messages.
- Entry rules: `compilationUnit`, plus `memberList`, `stmtList`, `exprOnly`, `typeOnly` for snippets and tests.
- `parse.Parsing` lexes and parses and returns `ParseError`s in source order (lexer errors, plus syntax errors from `SyntaxErrorStrategy`, which drops parser errors at lexer error tokens). `parse.TreeDump` renders compact s-expressions (`threebolt parse <file>`).
- Spec clarifications from this phase: §1 keywords after `::`; §6.3 collection literals allowed in heads; §17 notes 12–16.
- Tests: `ParserTest` (precedence, disambiguation, negative cases with line/col); `ParserAcceptanceTest` (every `examples/` file and every spec ```` ```rs ```` snippet parses; snippets may be modules, member lists, statement lists or one expression per line; `{ ... }` placeholder bodies are elided).

### Phase 3 — AST + front-end plumbing ✅
- `ast/`: records under sealed interfaces (`Item`, `Member`, `Stmt`, `Expr`, `TypeExpr`, `Pattern`, `Param`; `Body` = what follows `=>`), each with a `Span`. Parentheses and other purely syntactic choices are dropped (`{ x }` → field `x: x`, `x as T?` → `Propagate(Cast)`, struct-pattern shorthand → binding); unions are kept as written, not flattened (that is §3.4 semantics). Optional parts are `null`.
- `parse.AstBuilder` turns an error-free parse tree into the AST and decodes literal values (escapes, `\u{…}` scalar range → E0002, integer radix/suffix into `BigInteger` + suffix, float digits kept as text). Range checks against the target type are the checker's job (Phase 4). Tuple indices must be plain decimals (E0003). `parse.AstParser` = parse + build, reporting syntax errors as diagnostics; no AST when the parse failed.
- `parse.AstPrinter` prints source that parses back to the same AST. Besides precedence it re-inserts parentheses for: initializers in heads (§6.3); `<` comparisons inside comma lists (else §7.5 may read `(a < b), c > (d)` as generic args); casts followed by `|`/`<` (would extend the type); statement-, lambda- and arm-initial `{` and statement-initial `match`. It adds `;` after a match statement when the next statement starts with `(`, `[` or `-`. `ast.AstDump` (reflective, span-free s-expressions) defines AST equality for the round-trip tests.
- `diag/`: `Source` (code-point offsets, matching ANTLR), `Span`, `Code` (stable `E0xxx` ids: E00xx syntax, E01xx modules/names), `Diagnostic` (header + caret excerpt, notes), `Diagnostics`.
- `resolve.ModuleLoader`: the program is the entry module plus its `use` closure (decided with the user over "all files under the root", spec §2.1). `std::` modules are classpath resources `src/main/resources/std/*.3b`; bodyless functions there are intrinsics. Builds each `ModuleScope` (items → imports → prelude) and reports: missing module/item, private item, ambiguous module-vs-item import, duplicate names (fn overloads excepted), primitive type names, unknown attributes, `pub use`. A module with syntax errors suppresses follow-on import errors.
- Prelude: a built-in table, namely the `pub` items of `std::prelude`, plus `Map`/`Set` from `std::collections` and the module alias `c` (`std::c`, LP64 aliases for now). The std files only declare names; members land with their phases.
- CLI: `threebolt check|ast|print <file>` (besides `tokens`/`parse`).
- Tests: `AstBuilderTest`, `AstPrinterTest`, `AstRoundTripTest` (examples, std, conformance, spec snippets, `src/test/resources/printer/tricky.3b`), `ModuleLoaderTest`, `ConformanceTest` (`tests/conformance`, see its README; `.err` files, `-PupdateGolden`).
- Left for Phase 4: the `main` signature check (§2.4, confirmed as specified); resolving names inside bodies, including fully-qualified paths without a `use` (decided: spec §2.2). Modules named only by such paths must also be loaded, so the loader has to discover them from paths in types and bodies, not just from `use` items.

### Phases 4–7 ✅ — status (branch `phase-4-7-interpreter`)

Built in one go rather than slice by slice, because one checker and one interpreter serve every slice. Committed in focused chunks on 2026-10-27 (each commit builds and passes its tests); not yet pushed or merged.

**Architecture (new packages):**
- `types/`: `Type` (sealed; `Prim` enum; unions normalized by `TypeSystem.union`; `AliasRef` only for recursive aliases), `TypeInfo`/`FnInfo`/`FieldInfo`/`StaticInfo`/`ConstInfo`/`ImplInfo`/`TypeParamInfo` (mutable semantic info), `TypeSystem` (assignability §3.5, `implementsAs` incl. bounds/supertraits/base classes/auto struct-tuple-union Eq+Hash, impl table, subst, unify, `name()` for `nameof`), `Names` (module paths, fully-qualified paths), `TypeResolver`, `Declarer` (collect → signatures → bases/layout → impls → decl checks: overrides, hiding, abstract, trait conformance, overload conflicts, getters, `main`), `Checker` (contexts, statements, flow-sensitive narrowing, places/mutability, coercion), `Exprs`, `Calls` (method lookup, overloads with muted speculative typing, inference incl. deferred lambdas, UFCS `Trait::m(x)`), `Patterns` (patterns, match, exhaustiveness), `ConstEval` (consts, enum values, array sizes — checks then evaluates IR).
- `ir/`: `Ir` (typed, resolved program: places, `Call` with `CallKind` DIRECT/VIRTUAL/TRAIT/TRAIT_STATIC, `Coerce` for widening/boxing, `Unbox`, `Hole` for compound assignment), `Local`.
- `interp/`: `Interpreter` (tree walker; `store()` implements in-place `mut self` and read-modify-write through indexers; reified generics via per-frame type env + owner bindings from the receiver's runtime type; defers per block; panics carry a trace), `Values` (runtime values; structs/tuples/arrays copied on place reads), `Ops` (checked arithmetic, casts, float display, parsing), `Natives` (intrinsics), `Ffi` (minimal FFM: extern calls with scalar/`Ptr` args incl. variadics and `@link`, `Ptr` basics, `c::alloc/free`, `to_cstr/from_cstr`), `Panic`.
- `Driver` (check + run on a Threebolt thread, see Phase 8), CLI `threebolt run <file> [args]`; `check` now type-checks.
- std: `prelude.3b` (Iterator adapters `map filter enumerate of_type for_each fold any all count to_list first` written **in Threebolt** with private `MapIter`/`FilterIter`/`EnumerateIter`/`OfTypeIter`; `NativeIter` for interpreter-backed iterators; `List`, `Range` (struct with start/end/inclusive), `Type`, `Ptr`, `Err` trait with `MsgErr`), `collections.3b` (Map/Set), `process.3b` (`exit`). Bodyless std functions are intrinsics keyed `Owner.name`. `std/builtin/{int,float,str,char,bool,enum,flags}.3b` are member lists (not modules) attached to primitives/enums by the declarer; bodyless members are intrinsics, members with bodies are checked once per owner type in the prelude's scope, and a private member is visible only to the other members of std/builtin (`Calls.checkMethodAccess`); `AstRoundTripTest.builtinMembers` round-trips them.
- Loader: discovers modules named by fully-qualified paths (§2.2) syntactically (paths whose first segment is not an item/import/prelude/primitive/type-param name).
- Conformance runner: cases without errors are run; stdout/stderr compared with `.out`/`.stderr`; `//! exit: N` and `//! args: a b` directives. Documented in `tests/conformance/README.md`.

**Done and verified (all tests green, 2026-10-27):** conformance cases with reviewed goldens in `tests/conformance/basics/` (Phase 4), `values/` (Phase 5), `unions/` (Phase 6), `dispatch/` (Phase 7: inheritance, traits, boxing, generics, operators, iterators, lambdas, reflection, each with an errors case) and `programs/` (spec §18 verbatim, with and without an argument; `arena/` = `examples/arena` with a fixed-seed LCG `dice.3b` instead of libc). Unit tests: `types.TypeSystemTest` (unions, widening, assignability, trait lookup, `nameof`), `interp.OpsTest` (overflow, shifts, casts, float display, parsing).

**Phase 7 fixes made while writing the cases:**
- Lambdas capture a snapshot of the visible cells (`Env.snapshot`): still by reference, but a later binding of the same local (the next loop iteration, a `let` in a loop body) no longer leaks into earlier closures. Before, `for i in 0..3 { fs.push(() => i); }` gave `2 2 2`.
- Bodyless trait members and abstract methods get their default arguments checked and evaluated (calls through a trait reference crashed the interpreter).
- §7.5 hint: a local given type arguments (`f(a < b, c > (d))` reads `a<b, c>(d)`), or a local used as a type argument, reports "parsed as generic arguments; to compare, parenthesize". `Checker.typeArg` is used for every explicit type argument.
- Less cascading: no "must override abstract" for a class's own abstract methods or after a failed `override`; a lambda of the wrong arity reports only that.
- Dead code removed (`Exprs` stubs and `&& false`, `Values.fill/debugFields`, unused imports).

**Language additions after the Phase 7 review (spec changelog 2026-10-27, "slicing…" and "patterns after `is`"):**
- Slicing `xs[a..b]`, `xs[a..]`, `xs[..b]`, `xs[..]` on lists and arrays (`Expr.Slice` for open-ended forms, `Ir.Slice`; closed ranges stay `Expr.Index` and the checker picks slice vs `Index`). A range index is not a receiver place.
- `print`/`println`/`eprintln` take any `Display`.
- `x is pattern`: `isTarget` in the grammar, chosen by `isPatternAhead()`. Bindings are conditional `Local`s (`isConditional`) whose unnarrowed flow state is `Type.UNBOUND`; the condition's `whenTrue` binds them, so all the flow rules (`&&`, `!`, merges, diverging `if`) apply unchanged. Reading one elsewhere is E0231.
- Fixed: `match` on a literal-typed scrutinee (`match 5 { 1..=9 => … }`); "cannot infer type argument" no longer follows an argument that already failed to type.

**Known gaps/rough edges:** an `[]` element inside a collection literal gets no type from its siblings (`[[1], []]` needs an annotation); recursive aliases are only lightly supported (`AliasRef`); runtime `coerce` picks the first widening member of a union; a missing `IndexMut` on assignment reports E0300 (a mutability code).

**Decisions made while implementing:** confirmed by the user on 2026-10-27 and written into the spec (see its changelog entry "checker and interpreter (0.1 Phases 4–7)"). Implementation-only choices, not in the spec:
- Error recovery: a module with syntax errors is skipped by the checker; the checker runs even after loader errors.
- Diagnostic codes: E00xx syntax, E01xx modules/names (E0107–E0110 added: names, `main`), E02xx types/expressions, E03xx mutability, E04xx declarations; see `diag/Code.java`. Never renumber them.
- std intrinsics: bodyless std functions are keyed `Owner.name`; `std/builtin/*.3b` member lists attach to primitives/enums. Intrinsics reach the evaluator only through `interp.Callbacks` (`BoundaryTest`).

### Phase 8 — C interop & threads ✅ — status (branch `phase-8-ffi-threads`)
`extern "C"`, `@link`, `@c struct`, `Ptr<T>`, `c::*` aliases, `with_ptr` pinning, `unsafe` checking, callbacks — all via FFM (`Linker.nativeLinker()`, `MemorySegment`, `Arena`, upcall stubs). Then `Thread`, `Mutex`, `Atomic` on Java threads (no GIL needed — JVM is thread-safe; interpreter state must be).

**C interop (`interp/Ffi.java`):**
- Layouts: scalars, enums (backing type), `Ptr` and `extern fn` (addresses), `@c struct`s (C alignment and padding, computed once per type), `[T; N]` (sequence layouts) inside them. `load`/`store` move any ABI value between a `MemorySegment` and runtime values, so `Ptr<T>` read/write/offset and `c::alloc` work for structs and nested arrays.
- Downcalls: structs by value in both directions (a `SegmentAllocator` for struct returns), variadics (`firstVariadicArg`, C promotions), `@link` libraries. One confined arena per call.
- `extern fn` values: `FnVal.pointer(type, addr)` for addresses from C; calling one is a downcall to the address. Passing a Threebolt function to C creates an upcall stub (one per function, global arena) that calls the interpreter on the C thread. Nothing can unwind through C frames, so a panic or `exit` in a callback ends the process (`Interpreter.panicked`/`terminate`), returns zero to C, and the caller stops when the downcall returns.
- `with_ptr` (lists, arrays, `@c struct` places via `std/builtin/cstruct.3b`) copies into a native buffer for the duration of `f` and copies back afterwards.
- `std::c`'s integer aliases are generated per platform by `ModuleLoader.cIntegerAliases()` from the linker's canonical layouts.
- Checker: ABI types (`TypeSystem.isAbi`) in extern signatures and `@c struct` fields (`Declarer.checkAbi`) and `extern fn` types (`TypeResolver`), E0232; variadic calls and their argument types (`Calls.bind`); a top-level function converts to `extern fn` where one is expected, lambdas/methods/generics cannot (E0233); `with_ptr` element types and `Atomic<T>` checked in `Calls.stdCallRules`.

**Threads (`interp/Threads.java`, `std/thread.3b`, `std/sync.3b`):**
- One `Interpreter` for all threads: call stacks are `ThreadLocal`; statics live in a `ConcurrentHashMap` and initialize under one `ReentrantLock` (exactly once; a cycle still panics); caches are concurrent; list/map/set operations lock the collection (not around `with_ptr`'s callback).
- Ending the process: the first of `main` returning, a panic or an `exit` on any thread sets the exit code (`terminate`), prints (for a panic), and interrupts the other threads; they unwind with `Interpreter.Abort` at their next call, loop iteration, print or blocking operation. `Driver.run` waits for the exit code, not for the main thread. Threebolt threads, `main` included, are virtual threads (`Interpreter.threads`); the evaluator recurses deeply and `-Xss` caps a virtual thread's stack, so the launcher and test JVMs pass `-Xss1g`. Without it (a plain `java -jar`) they fall back to daemon platform threads with 1 GiB stacks. An interpreter bug reports `internal error` with exit code 70 instead of hanging.
- `Thread`, `Scope`, `Mutex`, `Atomic` are std classes whose Java state is in `Obj.nat` (`Threads.Strand`, `ScopeState`, `ReentrantLock`, `AtomicLong`).
- Cancellation (§13.4): each thread's `Strand` holds the sticky flag, its result, a done latch and the scopes it owns; one lock guards the tree, so `cancel` sets flags down owned scopes and their children atomically. `Thread.interrupt()` only wakes a thread blocked in a cancellation point, which then reads the flag; waits that are not cancellation points (`RawLock.acquire`, the statics lock, a scope's wait) retry after an interrupt unless the process is ending. `join`/`sleep`/`check`/`scope` are Threebolt over `wait`, `result`, `sleep_nanos`, `cancelled` and `Scope::open`/`close`.
- `--enable-native-access=ALL-UNNAMED` is passed to the test JVM too.

**Tests:** conformance `ffi/` (structs by value, through pointers and `with_ptr`, padding/arrays/enums/bools; strings, variadics, `@link("m")`; callbacks with `qsort`, `extern fn` values both ways and through C memory; a panicking callback; an errors case) and `threads/` (spawn/join, Atomic, Mutex over a list and a struct, statics initialized once under contention, panics and `exit` on threads, `main` returning with a spinning thread, mutex relock, errors). Unit tests: `TypeSystemTest.abiTypes`, `ModuleLoaderTest.cIntegerAliasesFollowThePlatform`. The C cases assume a little-endian macOS or glibc system.

**Known gaps:** `with_ptr` copies in and out, so inside `f` writes through the pointer are not yet visible when reading `x` directly (spec §14.5 says they are visible during `f`; pending a decision). One lock serializes all static initializers, so an initializer that waits for another thread which needs an uninitialized static deadlocks. A generic function cannot be named with explicit type arguments as a value (`id<i32>` without a call is not §7.5 syntax), so generic functions never convert to `extern fn`. Callbacks invoked on foreign threads work but are undefined behaviour per §14.7.

### After 0.1 phases — stdlib additions
- `std::math` (`std/math.3b`) and `std::random` (`std/random.3b`), spec §16. Most of both is written in Threebolt; intrinsics (owner `math`/`random` in `Natives`) cover libm calls Java provides, the `u64` combinatorics (via `BigInteger`, panicking past `u64`), and `random`'s private `entropy()` (`SecureRandom`). `Random` (xoshiro256**/splitmix64) is pure Threebolt and was checked against a reference implementation. Tests: conformance `stdlib/`.
- New std modules must be added to `ModuleLoader.STD_MODULES`.
- `std::json` (`std/json.3b`): pure Threebolt, no intrinsics. A private `Parser` class (recursive descent over `str` indexing, depth limit 512) and a `[str]`-buffer writer; `ToJson`/`FromJson` impls per primitive (integers through a private `integer<T: Integer>` range check). Tests: conformance `stdlib/json*`.
- `std::time` (`std/time.3b`): `Duration` (signed `i64` nanoseconds), `Instant`, `Timestamp`, `measure`, all Threebolt over two clock intrinsics; `thread::sleep` takes a `Duration`. Tests: conformance `stdlib/time*`. Working plan for `time` and `net`: `docs/plans/std-time-net.md`.
- Traits of unions (§3.4): `TypeSystem.implementsAs` (members must agree on the instance; `unionSafe`), `Calls.methodCandidates` (union case, searches implemented traits), `TypeSystem.same` (alias-aware equality used by `assignable`). `null` implements `Display`. Tests: conformance `unions/traits*`, `TypeSystemTest`.
- Numeric traits (§10.1) in `prelude.3b`, wired in `Declarer.builtinImpls`; primitive members in `std/builtin/int.3b`/`float.3b`. Literals of a numeric type parameter become `TRAIT_STATIC` calls of `from_i64`/`from_f64` (`Exprs.numLiteral`); the literal-only inference fallback is `Calls.literalOnly` (the expected type reaches `bind` through `callExpected`). Tests: conformance `numeric/`.
- Not done: Haskell-style defaulting (a literal-only type argument whose bounds `i32` fails, e.g. `Float`, could default to `f64`); with it, `std::math`'s float functions and `random::uniform` could be generic over `Float` without breaking `math::sqrt(2)`.

### Editor support — language server ✅ (branch `lsp`)
`threebolt lsp` serves the Language Server Protocol over stdio (LSP4J); `editors/vscode` starts it. Features: diagnostics as you type (whole program, every file), outline, hover (types and signatures), go to definition (std too), references, highlights, rename.

- `types.SymbolIndex`: what every resolved name refers to (Local, FnInfo, TypeInfo, FieldInfo, StaticInfo, ConstInfo, TypeParamInfo, EnumMember, Module) and its type there. Filled by the checker when constructed with one (`new Checker(program, diag, index)`); the default constructor records nothing. Hooks: `Names` (every resolved path segment), `Checker.declare`/`readLocal`/`place`, `TypeResolver.named` (type parameters), `Exprs` (fields, getters, `Type::member`, enum members, init fields), `Calls` (the chosen overload), `Patterns` (struct-pattern fields); `use` paths and declaration names are added after checking. A record made while diagnostics are muted (speculative typing) only fills a gap. Spans that name two kinds of thing (shorthand `{ x }`) are flagged so rename refuses them.
- `ModuleLoader` takes a reader (the editor's unsaved buffers) and `Module.file()` keeps each module's path.
- `lsp/`: `Workspace` (open buffers; one analysis thread with a 512 MiB stack; 200 ms debounce; entry = nearest `main.3b` at or above the file within the workspace folder, else the file alone, in which case a missing `main` is not reported), `Analysis` (one check), `StdFiles` (std sources copied to a temp dir so the editor can open them), `Server` (LSP methods), `Outline`, `Positions` (UTF-16 columns ↔ code points). Queries use the newest analysis in which the file parsed, so hover/navigation survive syntax errors (positions may drift until it parses again).
- Rename refuses std and built-in names, `self`, non-identifiers and shorthand fields; it renames a method's whole override chain, edits `use` paths, and leaves uses through an import alias alone.
- Build: `./gradlew vscodeServer` (fat jar `build/server/threebolt.jar`, copied into `editors/vscode/server/`). The `antlr` configuration is kept off the runtime classpath (the tool is 17 MB).
- Tests: `lsp.ServerTest` (in-process server over a temp program), `types.SymbolIndexTest` (every name in examples and conformance spells its target and its definition).
- Not done: formatting (`AstPrinter` drops comments), renaming trait methods across impls (only override chains are linked), named arguments (`f(x: 1)`) are not indexed.

### Editor support — completion and signature help ✅ (branch `lsp`)
`textDocument/completion` after `.`, `::` and on bare names (trigger characters `.` and `:`, the latter only as `::`), and `textDocument/signatureHelp` inside call parentheses (triggers `(` `,`, re-trigger `)`). No language semantics changed, so the spec did not change.

- **Placeholder patching** (`lsp.Completions`): the server inserts `types.Completion.PLACEHOLDER` (`__3b_complete`) at the cursor (`p.` → `p.__3b_complete`, `pri` → `pri__3b_complete`, so the prefix is what precedes it) and tries, in order: nothing more, `;`, the `(`/`[` left open since the innermost unclosed `{` plus `;`, the same plus ` = 0;` (`let x: T` needs a value), the same plus ` {}` (a signature). Braces still open at end of file are closed there. The first variant that parses is checked by `Workspace.check` on the analysis thread (`submit`, so ahead of debounced work not yet due) and never recorded. Nothing is offered inside comments or strings. A warm completion on `examples/arena` takes about 30 ms, so std is not cached.
- **Checker hooks** (`types.Completer`, created by `Checker` only when its `SymbolIndex` carries a `Completion`): `Exprs.member` and `Calls.methodCall` (`x.|`, receiver type after narrowing), `Exprs.pathTarget` (`Type::|` before `member`; a bare name), `Names.continueFrom` (`module::|`) and `Names.fullyQualified` (`std::|`, a prefix that is not a module), `TypeResolver.named` (type positions: types, modules and type parameters only). As in `SymbolIndex`, a site recorded while muted only fills a gap.
- **Listing:** members are found by asking the checker's own lookups about every candidate name: `Calls.methodCandidates` + `checkMethodAccess` (muted; `Diagnostics.dropped()` tells whether it objected) after `.`, `Exprs.memberTarget` after `Type::`. So the list is exactly what would resolve; no separate `allMembers`. Struct fields are always listed (§2.3: always public). Ranks: locals 0, members declared on the type / the scope's own items and type parameters 1, inherited and trait members / module items / modules 2, prelude and primitives 3, keywords 4.
- **`use` lines** are recognised from tokens; the module path is loaded (not checked) by `Workspace.load` with a one-line `use path::placeholder;` file, then its items are listed (`pub` only from another module). Submodules come from the files under the source root, `std::` from `ModuleLoader.STD_MODULES` (now public).
- **Fallback** when no variant parses: the module-level names of the last analysis in which the file parsed, the prelude, primitives and keywords (from `ThreeboltLexer`'s vocabulary).
- **Signature help:** variants without the placeholder first (`f(1, ` + `);` parses), then with it. `Completer.call`/`callValue` (hooks in `Calls.pathCall`, `methodCall`, `superCall`, `valueCall`) keep the innermost call around the cursor in the file being edited, with every overload rendered by `SymbolIndex.signature(f, env, params, selfIsParam)` (owner type parameters substituted; parameter offsets for the LSP labels; a UFCS receiver is parameter `self`). The active parameter is counted from depth-0 commas after the call's `(`; `name:` selects by name.
- Tests: `lsp.CompletionTest` (members, private/inherited/trait members, narrowed unions, instantiated generics, enum members, statics, primitive constants, `pub`-only module items, submodules, locals in scope only, `self`/imports/type parameters, type positions incl. signatures, missing `;`, unclosed calls, the fallback, comments/strings, `use` lines, signature help by position, by name, nested and on a generic receiver).
- Not done: snippets (methods insert only their name); completion in patterns beyond paths, in struct-initializer field names, and of named arguments.

### Warnings ✅ (branch `lsp-docs-warnings`)
Checker warnings, `W0xxx` codes (`diag.Code.isWarning`): they never count as errors (`Diagnostics.count`/`hasErrors` ignore them), are dropped while muted without counting as dropped, and are reported once per text. Tooling only: no language semantics changed, so not in the spec. `threebolt check` prints them; `run` only prints diagnostics when there are errors.

| Code | Warning | Not warned |
|---|---|---|
| W0001 | unused local, pattern binding, lambda or function parameter | names starting with `_`; parameters whose signature is imposed: bodyless, extern, virtual/abstract/override, trait members, trait implementations (`Lints.signatureImposed`) |
| W0002 | `mut` binding never assigned or given to a `mut self` method (root of the place: `x.a[i] = v` changes `x`) | |
| W0003 | unused import (a group's names separately; a glob when nothing from it is used) | |
| W0004 | unreachable statements: from the first one after a statement that never completes to the end of its block | |
| W0005 | private function, method, type, const, static, or class field that is never named | `main`, overrides, trait implementations, externs; struct fields (always public, §2.3) |
| W0006 | expression statement without effect (literals, names, operators on them, uncalled lambdas); `x == 1;` suggests `=` | anything with a call, member access, index, cast or `?` in it |

- `types.Lints` collects facts while checking (hooks: `Checker.declare`/`assign`/`block`/`exprStmt`, `Calls.methodCall` for `mut self`, `Names.resolve`/`lookup` for imports, `SymbolIndex.put` for every use, even with the index disabled) and reports the unused ones at the end of `Checker.check`. Only locals declared while unmuted are candidates; any use counts. Std is never warned about.
- Conformance: a case with errors compares only its errors; `tests/conformance/warnings/` covers each warning, including what is not warned.
- Language server: severity Warning; unused and unreachable code carry the `Unnecessary` tag (faded).

### Editor support — docs, quick fixes, hints, highlighting ✅ (branch `lsp-docs-warnings`)
- **Doc comments** (`parse.DocComments`): a run of `///` lines documents the declaration whose first token follows it (attributes included). Looked up by `SymbolIndex.declaration(target)`; an undocumented override or trait implementation uses `SymbolIndex.inheritsDocsFrom` (overridden method, implemented trait member). `lsp.Docs` caches each source's comments (weak keys). Shown in hovers (signature, `---`, Markdown), completion items (`Completion.Item.target`) and signature help (`Completion.Signature.fn`). Std's prelude, builtin members, collections and math are documented.
- **Hovers** show the call's instantiation (`SymbolIndex.Ref.env`, from `Calls.env(Bound)`: `fn List::iter(self) -> Iterator<Fighter>`), default values and constant values as source, enum member values, `self: T`.
- **Quick fixes** (`lsp.CodeActions`): `_` prefix (W0001), remove `mut` (W0002), remove an unused import or group name (W0003), `==` → `=` (W0006), `let mut` for E0300/E0301, and imports for E0107: every `pub` item or module of that name in std or the program's files (found by loading a throwaway program that glob-imports them all). The new `use` goes after the last `use`, else after the file's leading comments.
- **Inlay hints** (`lsp.InlayHints`): inferred types after bindings written without one (not after `:` or `is`, not when the type has an error).
- **Semantic tokens** (`lsp.SemanticTokens`): every indexed name by kind; modifiers `declaration`, `readonly`, `static`, `defaultLibrary` (std), and `mutable`, which the extension underlines by default (`configurationDefaults`). Primitive type names are left to the TextMate grammar.
- **Workspace symbols** (declarations outside std, letters of the query in order) and **go to implementation** (a trait's impls, a class's subclasses, a method's overrides and implementations; `TypeSystem.implsOfTrait`).
- Member completion sorts methods that operators call (`add`, `eq`, `cmp`, `truthy`, ...) after the rest when the receiver implements their trait.
- Not done: a warning for a `///` that documents nothing; formatting.

## Runtime boundary (roadmap task 1)

Std is Threebolt except for its **intrinsics**: bodyless functions in `src/main/resources/std` (keyed `Owner.name`,
dispatched by `interp.Natives`). This section is what a new backend must provide. Signatures are in the std sources;
listed here is what they do not say. Every intrinsic is value-in/value-out: it receives evaluated arguments (and
`self`), returns a value, and may panic (`Panic`, process exit 101 with the message shown). Intrinsics reach the
evaluator only through `interp.Callbacks` (`BoundaryTest` checks this):

| `Callbacks` member | Contract |
|---|---|
| `call(f, args…)` | calls a function value: closure, named function or C function pointer |
| `callTrait(recv, trait, name, args…)` | calls trait member `name` on `recv`'s runtime type (§8.3), including the automatic `Eq`/`Hash`/`Display` of structs, tuples and `null` |
| `equal`, `hash`, `display`, `debug`, `next`, `done()`, `typeOf` | `==` (§7.3), `Hash`, `Display` text, display-or-type-name for messages, one `Iterator::next` step, the `Done` sentinel, the runtime type (§15) |
| `out()`, `err()`, `stdin()` | the process streams (stdin read under its own lock) |
| `checkAbort`, `isTerminating`, `startThread`, `panicked`, `terminate` | process control (§13.1): stop this thread if the process is ending; start a Threebolt thread; report a panic that cannot unwind (C upcalls); end the process |
| `Abort`, `ExitSignal` | the unwinding signals: process ending elsewhere (no defers run), `process::exit` |

Values (`interp.Values`): integers carry their kind (`u64`/`usize` hold raw two's-complement bits), `f32` values are
kept rounded to `f32`, `str` is `Str` (UTF-16 + character count + offsets outside the BMP: O(1) `len`/`s[i]`, §3.1),
lists/maps/sets are mutable reference objects that lock themselves per operation (§13.4), arrays/tuples/structs are
copied on read (§5.3). Reading an element out of a collection yields a copy (§16).

### Primitive operations (every backend emits these directly)

The operators on primitives are evaluated directly (`interp.Ops`), never through their trait methods, so std
writes those methods over them (`pub fn add(self, o: Self) -> Self => self + o;`).

| Owner | Operations | Contract |
|---|---|---|
| integer types (`builtin/int.3b`) | operators `+ - * / %` unary `-`, `<< >>`, `& \| ^ ~`, `== < <= > >=`; intrinsic `hash` | §13.2: overflow panics `integer overflow (T)`; `/`/`%` by zero panic `division by zero` / `remainder by zero` (`%` takes the dividend's sign); a shift amount outside `0..bits` panics `shift amount n out of range for T`. `hash` mixes the value (64-bit finalizer). The trait methods (`add`, `eq`, `cmp`, …) and `parse` are Threebolt over the operators |
| | `wrapping_* checked_* saturating_*` (`add sub mul neg shl shr`) | modulo 2^bits / `null` on overflow / clamped to `MIN`/`MAX`. Shifts: `wrapping_` masks the amount; `checked_` is `null` outside `0..bits`; `saturating_` gives `0` (or `-1` for a negative `shr`) |
| | `from_i64(n)`, `bits()`, `to_str` | `from_i64` panics `n does not fit in 'T'`; `to_str` is decimal (unsigned for `u64`/`usize`) |
| float types (`builtin/float.3b`) | operators `+ - * / %` unary `-`, `== < <= > >=`; intrinsics `cmp`, `abs min max pow sqrt floor ceil trunc round sin cos tan exp ln log10 is_nan is_infinite`, `from_i64 from_f64 parse to_str` | IEEE 754 in `f64`, rounded to `f32` for `f32`. `round` is half away from zero; `min`/`max` follow Java's `Math.min/max` (NaN wins, `-0 < 0`); `cmp` orders NaN above everything (Java `Double.compare`). `parse` accepts decimal (`1`, `.5`, `-1e3`) and exactly `inf`, `+inf`, `infinity`, `-inf`, `-infinity`, `NaN`. `to_str` per §16's float format |
| `bool`, `char`, enums | operators `==`, `< <= > >=` (not `bool`), flags `& \| ^ ~`; intrinsics `hash`, `to_str` (not `bool`) | enum `to_str` is the member name, `A \| B` for flag combinations, the integer for undeclared bits; `~` keeps to the backing type's bits. `eq`, `cmp` and the flags methods are Threebolt over the operators |
| `char` | `is_alphabetic is_alphanumeric is_whitespace is_uppercase is_lowercase to_upper to_lower` | Unicode properties and simple case mapping (Java `Character`) |
| `str` (`builtin/str.3b`) | operators `+`, `== < <= > >=`; intrinsics `len`, `index` (`s[i]`), `slice` (`s[a..b]`), `hash` | counts characters (§3.1), O(1) `len`/`index`; out-of-range `index`/`slice` panic `index i out of range for a string of length n` / `slice a..b out of range for a string of length n`; `<` is code-point order; `hash` is FNV-1a over UTF-8 |
| | `chars bytes to_utf8 upper lower from_chars from_utf8` | `bytes`/`to_utf8` are UTF-8; `upper`/`lower` are full Unicode case mapping (may change the length: `"ß".upper()` is `"SS"`); `from_utf8` replaces invalid sequences with U+FFFD |
| `Range` | `iter`, `contains` | over integers (any kind; never overflows, the end may be the type's `MAX`) and `char`s (surrogates skipped); `contains` by `<`/`<=` on the bounds |
| `Type` | `name to_str eq hash is_subtype_of` | reflection (§15) |
| prelude | `panic(msg)` | panics with `msg` |

### Host capabilities (thin, platform-provided)

| Owner | Intrinsics | Contract |
|---|---|---|
| `[T]` (`List`, also `[T; N]`) | `len index set push pop insert remove clear iter` | the representation core; each call is atomic (§13.4: only these primitive operations are). `index`/`set`/`remove` panic `index out of range: i >= n`, `insert` panics `insert index out of range: i > n`; `pop` is `null` when empty; `iter` visits elements pushed during the loop |
| | `join(sep)` | linear-time concatenation of the elements' `to_str()`, the way to build a string; reads a snapshot, then calls `to_str` outside the lock. `T: Display` is checked at the call (`Calls.stdCallRules`) |
| prelude (private) | `equal(a, b)` | §7.3 `==` on any `T`, for std members generic over an unbounded `T` whose callers need `T: Eq` (`[T].contains`) |
| `Map`, `Set` (`collections.3b`) | `len iter index set get contains_key remove clear keys values` / `len iter insert contains remove clear` | insertion-ordered hash tables keyed by `Eq`/`Hash` (`Callbacks.equal`/`hash`); `index` panics `key not found in map: <key>`; iterators walk a snapshot |
| `std::thread` | `Thread::spawn cancel finished`, private `wait(nanos)` (a `WAIT_*` code; `i64::MAX` waits forever) and `result`; `Scope::spawn cancel cancelled`, internal `Scope::open close`; `cancelled`, private `sleep_nanos(ns) -> bool` | virtual threads (§13.4); `result` returns a copy and may be repeated; a panic on any thread ends the process; every wait reports cancellation as a code, and Threebolt builds `CancelledErr`/`TimedOutErr` |
| `std::sync` | `Atomic::new load store swap compare_exchange fetch_*`; private `RawLock::new acquire release`, `guard` | sequentially consistent, wrapping; `Atomic<bool>` arithmetic panics. `acquire` waits (not a cancellation point) and panics `mutex is already locked by this thread` on a relock; `guard(v)` builds a `Guard { value: v }` (`Mutex` itself is Threebolt) |
| `std::io` (private) | `sys_open close read write write_str flush seek error kind size remove rename mkdir list_dir temp_dir` | POSIX-shaped: numbered handles (0–2 are the standard streams; files and `std::net` sockets share the table), results ≥ 0 or a negated `E_*` code with a per-thread description for `sys_error`; `sys_kind` is 0 none, 1 file, 2 directory, 3 other. Constants are shared with `io.3b` (`Io.java`) |
| `std::process` | `exit(code)` | flushes stdout and ends the process; no defers run (§2.4) |
| `std::random` (private) | `entropy()` | 64 bits from the OS's secure generator |
| `std::time` (private) | `monotonic_nanos() wall_nanos()` | `i64` nanoseconds: a monotonic clock from an arbitrary origin fixed for the process; the wall clock since the Unix epoch |
| `std::net` (private) | `sys_tcp_connect tcp_listen accept udp_bind udp_connect send_to recv_from set_timeout set_nodelay shutdown addr lookup` | sockets are handles in `std::io`'s table, so `sys_read`/`write`/`write_str`/`close` work on them (`sys_read` on a UDP socket receives one datagram); addresses cross as text (an IP, `ip:port`, `[ip]:port`, without IPv6 scopes); network failures are codes 9–13 after io's (`E_*` in `net.3b`); timeouts are milliseconds, 0 none; sockets are interruptible channels, so threads blocked on them unwind when the process ends |
| `std::math` | `copysign cbrt expm1 log2 log1p asin acos atan atan2 hypot sinh cosh tanh asinh acosh atanh` | libm on `f64`; `log2` is exact for powers of two |
| C interop (§14) | `c::alloc free`, `Ptr::null from_addr is_null addr read write offset cast`, `str::to_cstr from_cstr`, `with_ptr` (lists, arrays, `@c struct`s), `extern` calls and upcalls | `malloc`/`free`; `read`/`write` through a null pointer panic `null pointer dereference`; `offset` scales by the target's size; `to_cstr` is NUL-terminated UTF-8 in `malloc`ed memory; `with_ptr` copies into native memory for the call and back after it (roadmap §5.1). A panic in an upcall ends the process; C gets a zero result |

Moved to Threebolt in task 1 (no longer intrinsics): `str` `trim split replace repeat starts_with ends_with find
contains truthy to_str`; integer `abs min max pow zero one min_value max_value`; float `zero one nan infinity epsilon`;
`char::is_digit`; `flags::has`; collection `truthy`; `[T]` `contains reverse first last`; `Mutex`; `math::isqrt factorial comb perm`.

## Packages (roadmap task 2) ✅ (branch `packages`)

- `project.Manifest` parses `package.toml` (`[package] name`, `[dependencies] k = { path }`, `[[bin]]`) and `workspace.toml`. night-config gives no positions, so spans come from the key's line in the text. `project.Project` finds the package or workspace in one directory. `project.Scaffold` is `bolt new`/`add`, which edit manifests as text.
- `resolve.Package`: root, manifest, dependencies by key; `Package.STD`; `isApplication()`. `ModuleLoader.load(entry)` (no manifest: one package at the entry's directory), `load(entry, root)` and `loadLibrary(root)` (no entry; `Program.entry()` is null). Packages are read first, one per real path, with cycle and key-clash checks. Then modules load per (package, local path); a directory holding a `package.toml` is not part of its parent.
- `Module.samePackage` decides `internal`; `Declarer.checkOrphan` is the §4.10 rule (E0412). `Lints` cover only the application package.
- LSP: `Analysis.Target` (entry or library, plus package root). `Workspace.targetsFor` tries the package's bins, then the library; `Program.pathFrom` names a module as the importing file would (`key::local`).

## Conventions for the implementing agent
- The spec is the source of truth; if code and spec disagree, fix one deliberately and record it.
- Every feature lands with conformance tests in `tests/conformance/` (positive run + at least one compile-error case).
- Keep the interpreter simple and obviously correct before fast. No bytecode/JIT in 0.1.
