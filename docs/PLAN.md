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
- §6.3 head restriction: `noStructExpr` pushes "forbidden" on a stack in the parser; `block`, `callArgs`, `nested` (parens/index/tuples), `initializer`, `collectionLiteral`, `matchBody` and `fstring` push "allowed" (`@init`/`finally`). Initializer and collection alternatives in `primary` are guarded by `structOk()`.
- Statement-initial `{` is a block (`exprStmt`/`defer` predicate). After `=>`, `{` is an initializer in fn bodies and a block in lambdas and match arms. `match` statements need no `;`.
- `>=`, `>>`, `>>=` are adjacent `GT`/`ASSIGN` tokens checked by `adjacent()` predicates with `<fail=...>` messages.
- Entry rules: `compilationUnit`, plus `memberList`, `stmtList`, `exprOnly`, `typeOnly` for snippets and tests.
- `parse.Parsing` lexes and parses and returns `ParseError`s in source order (lexer errors, plus syntax errors from `SyntaxErrorStrategy`, which drops parser errors at lexer error tokens). `parse.TreeDump` renders compact s-expressions (`threebolt parse <file>`).
- Spec clarifications from this phase: §1 keywords after `::`; §17 notes 12–16.
- Tests: `ParserTest` (precedence, disambiguation, negative cases with line/col); `ParserAcceptanceTest` (every `examples/` file and every spec ```` ```rs ```` snippet parses; snippets may be modules, member lists, statement lists or one expression per line; `{ ... }` placeholder bodies are elided).

### Phase 3 — AST + front-end plumbing
- Records for every node with `Span`. Builder visitor ParseTree → AST (no semantics). AST pretty-printer; round-trip tests (parse → print → parse = same AST).
- Module loader: source root = entry file's directory (§2.1); `use` resolution; prelude injection. Diagnostics infrastructure (error codes, spans, multiple errors per run).

### Phase 4 — Slice: "hello, arithmetic"
`fn main`, `println`, `let`/`let mut`, shadowing, primitives & numeric literal typing/widening (§3.6), operators (§7.3), checked overflow (§13.2), `if`/`while`/`loop`/`for` over ranges, `str` + f-strings (`Display` for primitives), functions, named-only optional params, overloads (§4.4), `const`, `static`, panics + exit codes.

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
