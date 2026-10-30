# Conformance tests

Threebolt programs with their expected results, run by `ConformanceTest` (`./gradlew test`).

- Each `.3b` file is one case, except that a directory containing `main.3b` is a single
  multi-module case whose entry is `main.3b` (its other files are its modules).
- A directory containing a `package.toml` is a package (spec §2.5). With a `[[bin]]` (or else a
  `main.3b`) it is the application package of one case, entered by its first bin; without either it is a
  library, loaded only as some case's dependency. A multi-package case is a directory holding the
  application package and its dependencies side by side, referred to by relative paths:

  ```
  packages/basic/
    app/package.toml        [[bin]] entry = "cli/run.3b"; [dependencies] shared = { path = "../shared" }
    app/cli/run.3b          the entry; run.err / run.out sit beside it
    shared/package.toml     no [[bin]]: a library
    shared/model/user.3b
  ```

  Diagnostics name files relative to the application package's root (`../shared/model/user.3b`).
- `<case>.err` next to the entry lists the expected diagnostics (loader and checker), one `file:line:col:
  error[CODE]: message` (or `warning[CODE]`) header per diagnostic (notes follow on their own lines),
  sorted by file and position. No `.err` file means the case must load without errors or warnings.
  A case with errors lists only its errors: its warnings are not compared.
- `./gradlew test -PupdateGolden` rewrites the `.err` files from the current output. Review the
  diff: a changed expectation is a deliberate decision.

A case without errors (warnings are fine) is then run (its `main`, as `bolt run` would):

- `<case>.out` is the expected stdout and `<case>.stderr` the expected stderr (panic messages and
  traces, `main`'s error message); a missing file means empty.
- `//! exit: N` on a line of its own sets the expected exit code (default 0; panics are 101).
- `//! args: a b c` passes arguments to `main(args: [str])`.
- `<case>.in`, if present, is the program's stdin (otherwise stdin is empty).

`./gradlew test -PupdateGolden` also rewrites `.out` and `.stderr` (not the `//!` directives).

`tour/` holds one program per chapter of the docs site's tour (`site/src/content/docs/tour/`), each
shown verbatim on its page with its `.out`. Changing one means updating the page too.
