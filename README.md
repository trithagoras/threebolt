# Threebolt

A hobbyist programming language: statically typed, garbage-collected, Rust-like syntax, C#-like semantics. Why "three bolts"? Four bolts is the safe way to hold something down. Three gets the job done, and it's more fun.

```rs
fn main(args: [str]) -> () | Err {
    let name = match args {
        [n, ..] => n,
        [] => "world",
    };
    println(f"Hello, {name}!");
}
```

Structs are values, classes are references with single inheritance, traits give runtime polymorphism, errors are union values (`T | Err`, propagated with `?`), and C functions are a declaration away (`extern "C"`).

**Status:** 0.1, a tree-walking interpreter in Java. It runs everything in the spec, including C interop (through Java's FFM API) and threads; see the [plan](docs/PLAN.md) for the known gaps.

## Documentation

- [`docs/spec/threebolt-1.0.md`](docs/spec/threebolt-1.0.md): the language reference (normative), with a changelog at the end.
- [`docs/PLAN.md`](docs/PLAN.md): implementation phases, architecture and status.
- [`examples/arena`](examples/arena): a sample program across four modules (classes, traits, unions, enums, C interop).
- [`src/main/resources/std`](src/main/resources/std): the standard library, written in Threebolt (bodyless functions are interpreter intrinsics).
- [`editors/vscode`](editors/vscode): VS Code extension with syntax highlighting and a language server — errors as you type, hover, go to definition, references, rename, completion, signature help (local install, see its README).

## Getting started

You need a **JDK 25 or newer**. Everything else (Gradle, ANTLR) is fetched by the Gradle wrapper. If `java` isn't on your `PATH`, point `JAVA_HOME` at the JDK (on macOS with Homebrew: `export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`).

Build the interpreter once:

```sh
./gradlew installDist
```

This puts a launcher at `build/install/threebolt/bin/threebolt`. Put that directory on your `PATH` or alias it:

```sh
alias threebolt="$PWD/build/install/threebolt/bin/threebolt"
```

Rerun `./gradlew installDist` after changing the interpreter or the standard library.

## Running programs

Save the example above as `hello.3b`, then:

```sh
threebolt run hello.3b           # Hello, world!
threebolt run hello.3b bolt      # Hello, bolt!
```

Everything after the file name goes to `main(args: [str])`. The program is the file you name plus every module it reaches through `use` or a fully-qualified path. Modules are found relative to the entry file's directory (`use game::entity;` loads `game/entity.3b`):

```sh
threebolt run examples/arena/main.3b          # 5 rounds
threebolt run examples/arena/main.3b quick    # 3 rounds
threebolt run examples/arena/main.3b 7
```

Exit codes:

| Code | Meaning |
|---|---|
| 0 | `main` returned normally |
| 1 | compile errors (printed to stderr), or `main` returned an error (its `message()` goes to stderr) |
| 101 | panic (message and stack trace on stderr) |
| `n` | `std::process::exit(n)` |
| 2 | bad command line |

You can also skip the install step and run through Gradle (slower to start): `./gradlew run --args="run examples/arena/main.3b quick"`.

### Other commands

```sh
threebolt check  file.3b   # load and type-check without running; prints diagnostics
threebolt tokens file.3b   # token stream
threebolt parse  file.3b   # parse tree
threebolt ast    file.3b   # AST
threebolt print  file.3b   # AST printed back as source
```

Diagnostics have stable codes (`error[E0200]: expected 'i32', found 'str'`) and point at the source with a caret.

## Testing

```sh
./gradlew test                    # all tests
./gradlew build                   # generate the parser, compile, test
./gradlew test --tests 'dev.threebolt.ConformanceTest'   # only the conformance suite
```

There are two kinds of tests:

- **Unit tests** in `src/test/java`, by component: lexer, parser, AST builder and printer, module loader, type system, arithmetic and casts. The parser tests also check that every `examples/` file and every code snippet in the spec parses, and that the printer round-trips them.
- **Conformance tests** in [`tests/conformance`](tests/conformance), grouped by feature (`basics/`, `values/`, `unions/`, `dispatch/`, `programs/`, …). Each case is a Threebolt program next to its expected results:
  - `case.3b`: the program. A directory with a `main.3b` is one multi-module case.
  - `case.err`: the expected compile errors. If this file is missing, the program must compile cleanly. It is then run, and its output is compared with:
  - `case.out` / `case.stderr`: the expected stdout and stderr (a missing file means empty).
  - Directives in the source: `//! exit: 101` sets the expected exit code, `//! args: a b` passes arguments.

To add a conformance case, write the `.3b` file, then generate its expected files from the current behaviour:

```sh
./gradlew test -PupdateGolden     # rewrites .err/.out/.stderr files and the lexer goldens
git diff tests/                   # check every change by hand
```

A changed expectation is a deliberate decision, so check the diff before committing it. See [`tests/conformance/README.md`](tests/conformance/README.md) for the details.

## Repository layout

```
src/main/antlr/…/parser/     ANTLR grammars (lexer + parser)
src/main/java/dev/threebolt/
  Main.java, Driver.java     CLI; check + run
  diag/                      source positions and diagnostics
  parse/ ast/                parse tree -> AST, printer
  resolve/                   modules, `use`, prelude
  types/                     declarations, type checker, narrowing, overloads -> typed IR
  ir/                        the typed, resolved program the interpreter runs
  interp/                    tree-walking interpreter, intrinsics, C calls (Java FFM)
src/main/resources/std/      standard library
tests/conformance/           conformance programs and expected results
examples/                    sample programs
docs/                        spec, plan, design history
```
