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

### Phase 2 — Parser
- `ThreeboltParser.g4` covering all of spec §2–§11 syntax. Lexer hand-offs: keyword tokens accepted as member names after `.` (§1); `get` is a keyword but §18 declares `fn get<T>` — decide (contextual keyword vs. rename) with the user. Priority list from spec §17:
  - generic-args disambiguation (§7.5): an alternative guarded by a semantic predicate that speculatively matches `typeArgs` and checks the follow token is `(` or `::`;
  - statement-initial `{` = block; no bare initializers in `if/while/for/match` heads (a `noStructExpr` variant of `expr`, Rust-style);
  - precedence via ANTLR4 left-recursive `expr` alternatives ordered per §7.2; comparisons non-associative (separate rule level);
  - lambda vs tuple/paren lookahead; `a if c else b`; `unsafe => e`.
- Acceptance: every snippet in the spec and every file in `examples/` parses; negative-syntax tests produce sane errors with line/col.
- Reference grammars (optional): `antlr/grammars-v4` — `csharp/` (generic-arg disambiguation) and `rust/` (no-struct-literal contexts). Fetch on demand; not required.

### Phase 3 — AST + front-end plumbing
- Records for every node with `Span`. Builder visitor ParseTree → AST (no semantics). AST pretty-printer; round-trip tests (parse → print → parse = same AST).
- Module loader: source root = entry file's directory (§2.1); `use` resolution; prelude injection. Diagnostics infrastructure (error codes, spans, multiple errors per run).

### Phase 4 — Slice: "hello, arithmetic"
`fn main`, `println`, `let`/`let mut`, shadowing, primitives & numeric literal typing/widening (§3.6), operators (§7.3), checked overflow (§13.2), `if`/`while`/`loop`/`for` over ranges, `Str` + f-strings (`Display` for primitives), functions, named-only optional params, overloads (§4.4), `const`, `static`, panics + exit codes.

### Phase 5 — Slice: types & values
Structs (value semantics, in-place `mut self`, read-modify-write indexers §5.3), tuples, classes, initializers/spread/`init` fields (§4.7), mutability checking (§5), getters, visibility, `[T]` / `Map` / `Set` + literals, `Truthy`/`Len`.

### Phase 6 — Slice: unions, narrowing, errors
Union types & assignability (§3.4–3.5), literal types, `null`, `is`/`as`/`as?`, narrowing (§9), `if let`/`while let`, `match` + patterns + exhaustiveness (§7.11, §11), `Error`/`Err`/`?`/`??`, must-use, `defer`, enums & `@flags`.

### Phase 7 — Slice: OOP & traits
Inheritance, `virtual`/`override`/`abstract`/`super`, no-hiding rule, traits (default bodies, supertraits, static fns), `impl` blocks + global uniqueness, trait references & boxing (§5.5), generics (reified, bounds, inference §10), operator-overloading traits, `Iterator`/`Iterable` + adapters with the `Done` sentinel, lambdas/closures, `typeof`/`nameof`/`.type()`.
- Milestone: spec §18 canonical example and `examples/arena` run.

### Phase 8 — C interop & threads
`extern "C"`, `@link`, `@c struct`, `Ptr<T>`, `c::*` aliases, `with_ptr` pinning, `unsafe` checking, callbacks — all via FFM (`Linker.nativeLinker()`, `MemorySegment`, `Arena`, upcall stubs). Then `Thread`, `Mutex`, `Atomic` on Java threads (no GIL needed — JVM is thread-safe; interpreter state must be).

## Conventions for the implementing agent
- The spec is the source of truth; if code and spec disagree, fix one deliberately and record it.
- Every feature lands with conformance tests in `tests/conformance/` (positive run + at least one compile-error case).
- Keep the interpreter simple and obviously correct before fast. No bytecode/JIT in 0.1.
