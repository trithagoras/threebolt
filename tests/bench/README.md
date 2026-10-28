# Benchmarks

Programs that time the interpreter, for comparing before and after a change (the runtime boundary
work, a new backend). Not part of `./gradlew test`. Each prints a checksum, so a change that breaks a
benchmark's result shows up as a different output, not just a different time.

```sh
./gradlew bench                    # every benchmark
./gradlew bench -Pbench=strings    # one
```

Times include JVM startup (and checking std); compare runs on the same machine.

## Baseline

Tree-walking interpreter before the runtime-boundary work (2026-10-28, `32f7628`, Apple Silicon laptop):

| benchmark   | time    | output |
|-------------|---------|--------|
| arith       | 1550 ms | `148933 16688` |
| calls       | 1743 ms | `832040 666166500000 999994` |
| collections |  950 ms | `100 10007 1501058220 2000 5551` |
| strings     | 1955 ms | `19999 44472000` |
| threads     |  870 ms | `800000 40002400000` |

## After the runtime boundary (roadmap task 1)

Same machine, 2026-10-28, after std moved into Threebolt (outputs unchanged):

| benchmark   | time     | vs baseline |
|-------------|----------|-------------|
| arith       |  1540 ms | same |
| calls       |  1757 ms | same |
| collections |   985 ms | same |
| strings     | 30835 ms | ~16× slower: `split`/`find`/`trim` scan character by character in Threebolt |
| threads     |  1658 ms | ~1.9× slower: `Mutex::lock` is Threebolt |
