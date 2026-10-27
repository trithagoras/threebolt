# Conformance tests

Threebolt programs with their expected results, run by `ConformanceTest` (`./gradlew test`).

- Each `.3b` file is one case, except that a directory containing `main.3b` is a single
  multi-module case whose entry is `main.3b` (its other files are its modules).
- `<case>.err` next to the entry lists the expected diagnostics, one `file:line:col:
  error[CODE]: message` header per diagnostic (notes follow on their own lines), sorted by file
  and position. No `.err` file means the case must load without errors.
- `./gradlew test -PupdateGolden` rewrites the `.err` files from the current output. Review the
  diff: a changed expectation is a deliberate decision.

Until Phase 4 adds `threebolt run`, a case is checked only as far as the front end goes (parse,
module loading, `use` resolution). Phase 4 adds expected stdout and exit codes.
