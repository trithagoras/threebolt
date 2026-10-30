---
title: Threads and channels
description: Blocking threads instead of async, scopes, cooperative cancellation, channels, mutexes and select.
sidebar:
  order: 9
---

There's no `async` in Threebolt. Concurrency is plain threads, and blocking is the programming
model. Threads are lightweight (M:N scheduled), so spawning thousands is fine, and one blocked on
I/O doesn't hold up the others.

```threebolt
use std::io::ClosedErr;
use std::thread;
use std::thread::CancelledErr;
use std::sync::{Channel, Mutex, Sender};

fn produce(out: Sender<i32>, n: i32) -> () | ClosedErr | CancelledErr {
    for i in 1..=n { out.send(i)?; }
    out.close();
}

fn main() -> () | Err {
    // A detached thread, joined for its result.
    let t = thread::Thread::spawn(() => 6 * 7);
    println(t.join()?);

    // Channels connect threads: unbuffered by default, so each send waits for a receive.
    let ch = Channel<i32>::new();
    let p = thread::Thread::spawn(() => produce(ch, 3));
    let mut sum = 0;
    for x? in ch { sum += x; }
    p.join()?;                           // unions flatten: one `?` covers the join and the thread
    println(f"sum {sum}");

    // Scoped threads finish before `scope` returns, so they may use the caller's locals.
    let counter = Mutex::new(0);
    thread::scope(s => {
        for _ in 0..4 {
            s.spawn(() => counter.lock((mut g) => g.value += 1));
        }
    });
    println(counter.lock(g => g.value));
}
```

```txt
42
sum 6
4
```

## Spawning and joining

`Thread::spawn(f)` starts a **detached** thread and returns a `Thread<R>`. `join()` waits for its
result, which is `R | CancelledErr`. Because unions flatten, a thread returning `() | Err` joins
as `() | Err | CancelledErr`, and a single `?` handles it.

## Scopes

`thread::scope(s => …)` waits for every thread spawned into `s` before returning, so those threads
may safely use the caller's locals. Prefer scopes; reach for detached threads when work really
should outlive its creator.

## Cancellation

Cancellation is cooperative. `t.cancel()` or `s.cancel()` sets a flag, and the thread notices at
its next **cancellation point**: any std operation that waits (`join`, `sleep`, channel
`send`/`recv`, `select`, I/O). That operation returns `CancelledErr`, and the thread unwinds with
`?` like any other error, running its `defer`s. In long computations, call `thread::check()?`.

## Sharing

- `Channel<T>` is a FIFO between threads: unbuffered by default, or `Channel<T>::new(n)`.
  `for x? in ch` receives until the channel is closed and drained.
- `Mutex<T>` hands a guard to a closure, `m.lock((mut g) => g.value += 1)`, so you can't forget to
  unlock.
- `Atomic<T>` handles integers and flags, and `Semaphore` limits concurrency.
- `select { let v = ch.recv() => …, after(d) => … }` waits on several operations at once.

Shared memory is otherwise unsynchronized, in keeping with the three-bolts ethos. Use the tools
above.
