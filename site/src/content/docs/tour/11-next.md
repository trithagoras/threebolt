---
title: Where next
description: The spec, the examples, and the standard library.
sidebar:
  order: 11
---

That's the tour. From here:

- **The spec**: [`docs/spec/threebolt-1.0.md`](https://github.com/trithagoras/threebolt/blob/main/docs/spec/threebolt-1.0.md)
  is the normative reference for every rule this tour skipped.
- **Examples**: `examples/` has complete programs: a TCP chatroom, hangman, a todo app, an
  entity-component system.
- **The standard library** is written in Threebolt itself, in `src/main/resources/std/`.
  Reading it is a good way to see idiomatic code.
- **Editor support**: `editors/vscode/` is a VS Code extension with highlighting, diagnostics,
  completion and signature help, backed by `bolt lsp`.

Every program in this tour is a test in `tests/conformance/tour/`, so if one stops working, the
build says so.
