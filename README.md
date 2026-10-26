# Threebolt

A hobbyist programming language: statically typed, garbage-collected, Rust-like syntax, C#-like semantics. Why "three bolts"? Four bolts is the safe way to hold something down. Three gets the job done, and it's more fun.

```rs
fn main(args: [Str]) -> () | Err {
    let name = match args {
        [n, ..] => n,
        [] => "world",
    };
    println(f"Hello, {name}!");
}
```

**Status:** 0.1 in progress (tree-walking interpreter). See the spec in [`docs/spec/threebolt-1.0.md`](docs/spec/threebolt-1.0.md), the plan in [`docs/PLAN.md`](docs/PLAN.md), and a sample program in [`examples/arena`](examples/arena).

## Building

Requires JDK 25+.

```sh
./gradlew build
./gradlew run --args="tokens examples/arena/main.3b"
```
