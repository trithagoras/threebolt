---
title: Hello, Threebolt
description: Install the bolt CLI, run a first program, and meet main, println and f-strings.
sidebar:
  order: 1
---

Threebolt is a statically typed, garbage-collected language. The syntax will look familiar
if you know Rust, but the semantics are closer to C#: there's no ownership, no borrowing and no
lifetimes. Values are either copied (structs) or shared by reference (classes), and the garbage
collector cleans up.

## Getting `bolt`

Threebolt currently runs on a tree-walking interpreter written in Java. You need a JDK 25 or newer:

```sh
git clone https://github.com/trithagoras/threebolt
cd threebolt
./gradlew installDist
export PATH="$PWD/build/install/bolt/bin:$PATH"
```

## A first program

Save this as `hello.3b`:

```threebolt
/// The entry point: every program starts at `main`.
fn main(args: [str]) {
    let name = "world";
    println(f"Hello, {name}!");   // f-strings interpolate any expression
    println(f"{args.len} arguments, 1 + 2 = {1 + 2}");
    /* block comments /* nest */ too */
}
```

```sh
$ bolt run hello.3b
Hello, world!
0 arguments, 1 + 2 = 3
```

A few things to notice:

- **`main`** takes either no parameters or `args: [str]`, which holds the command-line arguments
  without the program name. `[str]` is a list of strings.
- **`println`** is in the *prelude*, the set of names that every module can use without an import.
- **F-strings** (`f"…{expr}…"`) interpolate any expression whose type implements `Display`.
  Use `{{` and `}}` for literal braces.
- **`args.len`** has no parentheses. `len` is a *getter*, a property-like member, and calling it
  like a method is a compile error. This is one of the first places where Rust habits will trip
  you up.
- Comments are `//`, nestable `/* */`, and `///` for doc comments.

## Projects

For anything bigger than one file, let `bolt` create a package:

```sh
$ bolt new greeter
$ cd greeter
$ bolt run
```

A package is a directory with a `package.toml`. Each `.3b` file inside it is a module, named by its
path: `game/entity.3b` is `game::entity`. We'll come back to modules later in the tour.

`bolt check` type-checks without running, and `bolt lsp` powers the VS Code extension in
`editors/vscode/`.
