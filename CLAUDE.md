# Threebolt

Hobbyist general-purpose language (`.3b`): statically typed, GC-managed, Rust-flavoured syntax with C#-flavoured semantics. The "three bolts" ethos: prefer simple, slightly sharp semantics over safety machinery. Currently building **0.1**, a tree-walking interpreter in Java + ANTLR, working towards the 1.0 spec.

## Source of truth

1. `docs/spec/threebolt-1.0.md` — the normative language spec. Read the relevant sections before touching the grammar, checker, or interpreter.
2. `docs/PLAN.md` — implementation phases, stack, repo layout.
3. `docs/history/` — the original design doc and the design-interview decision log. Rationale only; never implement from these when they disagree with the spec.

**Spec first:** if implementation reveals a problem, change the spec (and its `## Changelog` section) before or alongside the code — never let code silently diverge. Ask the user before changing language semantics; they own the design.

## Non-negotiables for 1.0

- No async. No ownership/borrowing/lifetimes — don't import Rust semantics just because the syntax looks like Rust.
- No exceptions: errors are union values (`T | Err`), `?` propagates, `panic` aborts.
- C interop (`extern "C"`, `Ptr<T>`, `unsafe`) is a 1.0 feature — implemented via Java's FFM API.

## Build & run

```sh
./gradlew build                                   # generate parser, compile, test
./gradlew test
./gradlew run --args="tokens examples/arena/main.3b"
./gradlew run --args="parse examples/arena/main.3b"
```

- Requires a JDK ≥ 25 (bytecode targets 25 via `--release 25`). If `java` isn't on PATH, use `JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`.
- ANTLR 4.13.2 comes from Maven via the Gradle `antlr` plugin; never install it separately or commit generated sources.
- Grammars: `src/main/antlr/dev/threebolt/parser/ThreeboltLexer.g4` and `ThreeboltParser.g4` (split grammar; the parser uses `tokenVocab`). Generated code lands in `build/generated-src/antlr/main`.

## Layout

```
src/main/antlr/…/parser/   ANTLR grammars
src/main/java/dev/threebolt/
  Main.java                CLI
  ast/ parse/ resolve/ types/ interp/ runtime/   (created as phases land — see PLAN)
src/test/java/…            JUnit 5 unit tests
tests/conformance/         .3b programs + expected output / expected diagnostics
examples/                  sample Threebolt programs (must keep parsing as the grammar evolves)
```

## Conventions

- Java: records + sealed interfaces for the AST, `var` where the type is obvious, no frameworks. Keep the interpreter obviously correct before fast.
- Every feature lands with tests: unit tests for the component plus a conformance program (and at least one compile-error case where the spec defines an error).
- `examples/` and the spec's code snippets are informal test inputs — if one stops parsing, either the grammar or the snippet is wrong; fix it deliberately.
- Small, focused commits.
