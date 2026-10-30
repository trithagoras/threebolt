# Threebolt

Hobbyist general-purpose language (`.3b`): statically typed, GC-managed, Rust-flavoured syntax with C#-flavoured semantics. The "three bolts" ethos: prefer simple, slightly sharp semantics over safety machinery. Currently building **0.1**, a tree-walking interpreter in Java + ANTLR, working towards the 1.0 spec.

## Source of truth

1. `docs/spec/threebolt-1.0.md` — the normative language spec. Read the relevant sections before touching the grammar, checker, or interpreter.
2. `docs/PLAN.md` — implementation phases, stack, repo layout. `docs/ROADMAP.md` — open decisions on the way to 1.0 (std expansion, backend, packages).
3. `docs/history/` — the original design doc and the design-interview decision log. Rationale only; never implement from these when they disagree with the spec.
4. `site/` — the user-facing docs site (Astro + Starlight), currently the *Tour of Threebolt*. Explanatory, not normative: it follows the spec, never the reverse.

**Spec first:** if implementation reveals a problem, change the spec (and its `## Changelog` section) before or alongside the code — never let code silently diverge. Ask the user before changing language semantics; they own the design.

## Non-negotiables for 1.0

- No async. No ownership/borrowing/lifetimes — don't import Rust semantics just because the syntax looks like Rust.
- No exceptions: errors are union values (`T | Err`), `?` propagates, `panic` aborts.
- C interop (`extern "C"`, `Ptr<T>`, `unsafe`) is a 1.0 feature — implemented via Java's FFM API.

## Build & run

```sh
./gradlew build                                   # generate parser, compile, test
./gradlew test
./gradlew run --args="debug tokens examples/arena/main.3b"
./gradlew run --args="debug parse examples/arena/main.3b"
./gradlew run --args="debug ast examples/arena/main.3b"     # also: debug print, check, run
./gradlew installDist                             # the `bolt` CLI at build/install/bolt/bin/bolt
./gradlew test -PupdateGolden                     # rewrite lexer goldens and conformance .err files
./gradlew vscodeServer                            # build the language server jar into editors/vscode/server/
cd site && npm install && npm run dev             # docs site at localhost:4321; `npm run build` must stay clean
```

- Requires a JDK ≥ 25 (bytecode targets 25 via `--release 25`). If `java` isn't on PATH, use `JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`.
- ANTLR 4.13.2 comes from Maven via the Gradle `antlr` plugin; never install it separately or commit generated sources.
- Grammars: `src/main/antlr/dev/threebolt/parser/ThreeboltLexer.g4` and `ThreeboltParser.g4` (split grammar; the parser uses `tokenVocab`). Generated code lands in `build/generated-src/antlr/main`.

## Layout

```
src/main/antlr/…/parser/   ANTLR grammars
src/main/java/dev/threebolt/
  Main.java                CLI (`bolt`); project/ finds package.toml / workspace.toml, `bolt new`/`add`
  diag/                    Source, Span, diagnostics with stable E0xxx codes
  ast/ parse/ resolve/ types/ interp/ runtime/   (created as phases land — see PLAN)
src/main/resources/std/    std modules in Threebolt (bodyless fns are intrinsics: see PLAN.md § Runtime boundary)
src/test/java/…            JUnit 5 unit tests
tests/conformance/         .3b programs + expected output / expected diagnostics (see its README)
examples/                  sample Threebolt programs (must keep parsing as the grammar evolves)
src/main/java/…/lsp/       language server (`bolt lsp`, LSP4J) over types.SymbolIndex; completion via types.Completer hooks
editors/vscode/            VS Code extension: TextMate grammar (keep in step with ThreeboltLexer.g4) + LSP client
site/                      docs site; the tour is site/src/content/docs/tour/NN-topic.md (its .3b highlighting uses the TextMate grammar above)
tests/conformance/tour/    one program per tour chapter, shown verbatim on its page
```

## Conventions

- Java: records + sealed interfaces for the AST, `var` where the type is obvious. Dependencies: ANTLR runtime and LSP4J (language server only). Keep the interpreter obviously correct before fast.
- Every feature lands with tests: unit tests for the component plus a conformance program (and at least one compile-error case where the spec defines an error).
- `examples/` and the spec's code snippets are informal test inputs — if one stops parsing, either the grammar or the snippet is wrong; fix it deliberately.
- Language server: completion and signature help check a copy of the file with `Completion.PLACEHOLDER` at the cursor; the checker's hooks (`Completer`) list what fits. A new name-resolution path in the checker should call the completer where it meets the placeholder.
- The VS Code extension bundles the server jar, which contains the grammar, checker and std. So after **any** change to the grammar, AST builder, checker, std modules, LSP code or the TextMate grammar, always bump `editors/vscode/package.json`'s patch version, then rebuild and reinstall without being asked: `./gradlew vscodeServer`, then in `editors/vscode`: `npx @vscode/vsce package --allow-missing-repository --skip-license -o threebolt.vsix && code --install-extension threebolt.vsix --force`. Tell the user to run **Developer: Reload Window**. Interpreter-only changes don't need it.
- **Docs follow the language.** Any change to syntax, semantics, std APIs or the CLI that a user can see must also update the site in the same change: grep `site/src/content/docs/` for the feature and fix the prose, and if a chapter's program is affected, edit `tests/conformance/tour/<name>.3b` (and its `.out`) and paste it back into the page verbatim — the page's code block and output must match those files exactly. `TourSyncTest` enforces this; the `/docs-sync` skill (`.claude/skills/docs-sync/`) walks through the whole update. A feature on the beginner path (something every user meets) gets a short section in the right chapter; niche rules stay in the spec. Spec changelog entries are a good checklist for what the site may need.
- Small, focused commits.
