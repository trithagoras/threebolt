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
    Main.java                 // CLI: `threebolt run <file.3b> [args]`, `threebolt parse|tokens <file>`
    ast/                      // sealed-interface + record AST, Span
    parse/                    // ParseTree -> AST builder, error listener
    resolve/                  // modules, `use`, scopes, symbol tables
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
- `Driver` (check + run on a 1 GiB-stack thread), CLI `threebolt run <file> [args]`; `check` now type-checks.
- std: `prelude.3b` (Iterator adapters `map filter enumerate of_type for_each fold any all count to_list first` written **in Threebolt** with private `MapIter`/`FilterIter`/`EnumerateIter`/`OfTypeIter`; `NativeIter` for interpreter-backed iterators; `List`, `Range` (struct with start/end/inclusive), `Type`, `Ptr`, `Err`), `collections.3b` (Map/Set), `process.3b` (`exit`). Bodyless std functions are intrinsics keyed `Owner.name`. `std/builtin/{int,float,str,char,bool,enum,flags}.3b` are member lists (not modules) attached to primitives/enums by the declarer; `AstRoundTripTest.builtinMembers` round-trips them.
- Loader: discovers modules named by fully-qualified paths (§2.2) syntactically (paths whose first segment is not an item/import/prelude/primitive/type-param name).
- Conformance runner: cases without errors are run; stdout/stderr compared with `.out`/`.stderr`; `//! exit: N` and `//! args: a b` directives. Documented in `tests/conformance/README.md`.

**Done and verified (all tests green, 2026-10-27):** conformance cases with reviewed goldens in `tests/conformance/basics/` (Phase 4), `values/` (Phase 5), `unions/` (Phase 6), `dispatch/` (Phase 7: inheritance, traits, boxing, generics, operators, iterators, lambdas, reflection, each with an errors case) and `programs/` (spec §18 verbatim, with and without an argument; `arena/` = `examples/arena` with a fixed-seed LCG `dice.3b` instead of libc). Unit tests: `types.TypeSystemTest` (unions, widening, assignability, trait lookup, `nameof`), `interp.OpsTest` (overflow, shifts, casts, float display, parsing).

**Phase 7 fixes made while writing the cases:**
- Lambdas capture a snapshot of the visible cells (`Env.snapshot`): still by reference, but a later binding of the same local (the next loop iteration, a `let` in a loop body) no longer leaks into earlier closures. Before, `for i in 0..3 { fs.push(() => i); }` gave `2 2 2`.
- Bodyless trait members and abstract methods get their default arguments checked and evaluated (calls through a trait reference crashed the interpreter).
- §7.5 hint: a local given type arguments (`f(a < b, c > (d))` reads `a<b, c>(d)`), or a local used as a type argument, reports "parsed as generic arguments; to compare, parenthesize". `Checker.typeArg` is used for every explicit type argument.
- Less cascading: no "must override abstract" for a class's own abstract methods or after a failed `override`; a lambda of the wrong arity reports only that.
- Dead code removed (`Exprs` stubs and `&& false`, `Values.fill/debugFields`, unused imports).

**Known gaps/rough edges:** statics are not thread-safe (Phase 8); recursive aliases are only lightly supported (`AliasRef`); runtime `coerce` picks the first widening member of a union; a missing `IndexMut` on assignment reports E0300 (a mutability code).

**Decisions made while implementing:** confirmed by the user on 2026-10-27 and written into the spec (see its changelog entry "checker and interpreter (0.1 Phases 4–7)"). Implementation-only choices, not in the spec:
- Error recovery: a module with syntax errors is skipped by the checker; the checker runs even after loader errors.
- Diagnostic codes: E00xx syntax, E01xx modules/names (E0107–E0110 added: names, `main`), E02xx types/expressions, E03xx mutability, E04xx declarations; see `diag/Code.java`. Never renumber them.
- std intrinsics: bodyless std functions are keyed `Owner.name`; `std/builtin/*.3b` member lists attach to primitives/enums.

**Phase 8 head start:** `interp/Ffi.java` already does extern calls (scalar, `Ptr`, enum args/returns; variadics with C promotions; `@link` via `System.mapLibraryName`), `Ptr` null/from_addr/is_null/addr/offset/cast/read/write for primitive `T`, `c::alloc`/`c::free` (libc malloc/free), `str::to_cstr`/`from_cstr`, and the `unsafe` context check (E0227). Still missing: `@c struct` layouts, `[T; N]` in C structs, callbacks/upcalls and the `extern fn` conversion (§14.7), `with_ptr` pinning, ABI-type checking of extern signatures at declaration, threads/Mutex/Atomic.


### Phase 8 — C interop & threads
`extern "C"`, `@link`, `@c struct`, `Ptr<T>`, `c::*` aliases, `with_ptr` pinning, `unsafe` checking, callbacks — all via FFM (`Linker.nativeLinker()`, `MemorySegment`, `Arena`, upcall stubs). Then `Thread`, `Mutex`, `Atomic` on Java threads (no GIL needed — JVM is thread-safe; interpreter state must be).

## Conventions for the implementing agent
- The spec is the source of truth; if code and spec disagree, fix one deliberately and record it.
- Every feature lands with conformance tests in `tests/conformance/` (positive run + at least one compile-error case).
- Keep the interpreter simple and obviously correct before fast. No bytecode/JIT in 0.1.
