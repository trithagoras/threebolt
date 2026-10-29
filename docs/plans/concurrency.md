# Concurrency — threads, scopes, cancellation (proposal)

**Status:** agreed 2026-10-30 and folded into the spec (§1, §6.4, §7.14, §13.4, §16; changelog
"2026-10-30 — concurrency"). **The spec is normative**; this file keeps the reasoning, the implementation
notes and the rejected alternatives. Not yet implemented in 0.1 (`docs/ROADMAP.md`). Worked example:
`examples/chatroom/`.

## Decisions so far

- **Threads, not async.** Every Threebolt thread is cheap (a JVM virtual thread). Blocking is the
  programming model: `join` is "await", and no function has a colour. The blocking std (`io`, `net`,
  `time`) stays; there is no second, async std.
- **Scopes wait; they do not cancel.** Leaving `thread::scope` waits for every child (Rust's
  `std::thread::scope`, Kotlin's `coroutineScope`, Trio's nurseries). Cancelling is explicit:
  `s.cancel()`, usually as `defer s.cancel();`.
- **An error in the body does not cancel either.** A `?` that leaves the body still waits;
  `defer s.cancel();` cancels on every exit (defers are block-scoped, §6.5, so it runs before the wait).
- **Scopes ignore their children's results.** A child's `Err` reaches you only through `join` or a
  channel.
- **Cancellation is cooperative, sticky and fails fast.** Once a thread is cancelled it stays
  cancelled, and every cancellation point returns `CancelledErr` on entry, even an operation that could
  have finished without waiting.
- **Everything that waits is a cancellation point**, except `Mutex::lock`. That includes `sleep` and
  `join()`. The discard rule (§6.1) is not relaxed for `CancelledErr`: write `?` or `_ =`.
- **`CancelledErr : Err`, in `std::thread`**, not an `IoErr`. That's the convention in C#, Kotlin,
  Python and Go: cancellation is a request from another thread, which I/O operations report but don't own.
- **No timeout on a scope.** Deadlines are expressed as cancellation (`s.cancel()`) plus per-call
  timeouts and `select`'s `after`. A child that never reaches a cancellation point hangs its scope, and
  that is the child's bug.
- **Iterating a channel yields `T | CancelledErr`** (once, then ends), as `lines()` yields `IoErr`.
  New loop form `for p? in e` propagates it (§7.1).
- **`Sender<T>`/`Receiver<T>` traits**; `Channel<T>` implements both. Not on sockets (byte streams have
  `Read`/`Write`).
- **`Channel::new()`** with no capacity is unbuffered (rendezvous).
- **`select` is a contextual keyword** (like `get`), not a library function (§7.2). Cases: channel
  `recv`/`send`, thread `join`, `after(d)`, `_`. Every case binds its own outcome.
- **No thread-locals or context values in 1.0** (§11).

## 1. Threads

- A thread runs a function concurrently with its creator. Threads are lightweight: a program may run
  hundreds of thousands of them, and a thread that blocks does not hold up the others. Implementations
  SHOULD map threads to M:N scheduled user-mode threads (JVM: virtual threads).
- `Thread::spawn<R>(f: fn() -> R) -> Thread<R>` starts a **detached** thread. No scope owns it,
  cancelling its creator does not cancel it, and it may outlive its creator. It still stops when the process
  ends (§13.4: `main` returning stops all threads; their defers do not run).
- `Thread<R>`:
  - `join(self) -> R | CancelledErr` waits for the result (a copy for value types; repeatable).
  - `join(self, timeout: Duration) -> R | TimedOutErr | CancelledErr` (a non-positive `timeout` checks
    once).
  - `cancel(self)` requests cancellation of that thread (§3). Repeating it, or calling it on a finished
    thread, does nothing.
  - getter `finished -> bool`.
- `thread::sleep(d: Duration) -> () | CancelledErr`.
- A panic on any thread still ends the process (§13.1). Panics never cross threads as values.

## 2. Scopes

```
pub fn scope<R>(f: fn(Scope) -> R) -> R
pub class Scope {
    pub fn spawn<T>(self, f: fn() -> T) -> Thread<T>;
    pub fn cancel(self);
    pub cancelled -> bool;          // getter
}
```

- `thread::scope(f)` calls `f` on the current thread (the scope's **owner**) with a new `Scope`.
  When `f` returns by any path (fall-through, `return`, `?`), `scope` **waits until every child has
  finished**, then returns `f`'s result. Children spawned while it waits (by other children) are waited
  for too. The wait itself is not a cancellation point: a cancelled owner still waits, and propagation
  (§3.1) has already cancelled the children.
- `s.spawn(f)` starts a child thread owned by `s` and returns its handle. Every thread may spawn into a
  scope it can reach, not only its owner. Spawning into a scope whose wait has completed panics
  (`scope has ended`).
- **The guarantee:** no child outlives its scope. So children may safely use the owner's locals.
- Results: the scope does not collect, inspect or propagate its children's results. Use `join` on the
  handle, or a channel.
- `s.cancel()` cancels every current child of `s`, and every child spawned into `s` afterwards starts
  cancelled. A scope stays cancelled. It does **not** cancel the owner.
- Idioms:
  - Fork-join: spawn, then `join` the handles you need, or just let the scope wait.
  - "These children live exactly as long as this block": `defer s.cancel();` as the body's first line.
  - First result wins: `recv` the first answer, then `s.cancel()` (or the defer).

## 3. Cancellation

### 3.1 The flag

- Every thread has a **cancelled** flag, initially false. Once set, it is never cleared.
- These set it:
  - `t.cancel()` for that thread;
  - `s.cancel()` for every child of `s` (current and future);
  - **propagation:** when a thread is cancelled, every scope it currently owns, at any depth of
    nesting, is cancelled (§2), and so on transitively down the tree. Detached threads it spawned are
    not affected.
- Cancellation never preempts. A thread notices only at a **cancellation point** (§3.3) or when it asks:
  - `thread::cancelled() -> bool` reads the current thread's flag;
  - `thread::check() -> () | CancelledErr` returns `CancelledErr` if it is set (`thread::check()?;`).
- `CancelledErr : Err` (in `std::thread`; message `cancelled`).

### 3.2 Unwinding is ordinary control flow

`CancelledErr` is an ordinary error value. A thread "unwinds" by `?` returning it through its own
frames, running `defer`s as usual, until its entry function returns. It does not travel to the parent:
the parent, or whoever cancelled the thread, already knows, and reads the result with `join` if it cares.
Code that ignores the error keeps running, with the flag still set, so its next cancellation point fails
again.

### 3.3 Cancellation points

**Rule:** every operation that waits on another thread or on the outside world is a cancellation
point, and has `CancelledErr` in its result type. The one exception is `Mutex::lock`, which waits only
for another thread's critical section; adding the error to its `R` would infect every locked access.

On a cancelled thread, a cancellation point returns `CancelledErr` **on entry**, without trying the
operation. A thread already blocked in one when it is cancelled is woken where the platform allows (table).

| Operation | Checked on entry | Woken while blocked |
|---|---|---|
| `thread::sleep`, `join`, `join(timeout)`, `check` | yes | yes |
| `Channel` `send`, `recv`, `recv(timeout)`, iteration; `select` | yes | yes |
| `Semaphore::with` (waiting for a permit) | yes | yes; once `f` is running, it runs to completion |
| `TcpListener::accept`, `incoming`, `serve`; `TcpStream::connect`, reads, writes; `UdpSocket` send/receive | yes | yes, and **the socket is closed** (the JVM's behaviour; a connection is unusable after an interrupted transfer anyway) |
| File operations | yes, and between the chunks of a large read or write (`read()`, `read_lines()`, `lines()` per line) | no: a single system call in progress finishes (best effort on network filesystems) |
| `stdin()` as a `File` | yes | no (known gap: a thread blocked reading stdin can't be woken) |
| `Mutex::lock`, `try_send`, `close`, the prelude console functions (`print*`, `readln`: they panic on I/O errors and return no error, so adding `CancelledErr` would tax every script) | — not cancellation points — | |

Iterating a `Channel` yields `T | CancelledErr`: the error is yielded once and ends the loop, as
`lines()` does with `IoErr`. `for x? in ch` propagates it (§7.1).

### 3.4 Not provided

- No shielding (running cleanup that blocks on a cancelled thread). A `defer` that needs to block on a
  cancellation point fails with `CancelledErr`. Do the work on a detached thread if it must happen.
- No cancellation reasons or causes. No un-cancelling.

## 4. `std::sync` additions

**`Sender<T>`** (trait, a pure contract): `send(self, v: T) -> () | ClosedErr | CancelledErr`,
`try_send(self, v: T) -> bool`, `close(self)`.

**`Receiver<T>`** (trait, a pure contract; requires `Iterable<T | CancelledErr>`):
`recv(self) -> T | ClosedErr | CancelledErr`, `recv(self, timeout: Duration) -> T | ClosedErr | TimedOutErr | CancelledErr`.

Only senders close (as in Go): a function given a `Sender<Job>` can only send and close; one given a
`Receiver<Job>` can only receive.

**`Channel<T>`** (a class implementing `Sender<T>` and `Receiver<T>`): a bounded FIFO queue between threads.
- `Channel<T>::new()` is unbuffered: each `send` waits for a `recv`. `Channel<T>::new(capacity: usize)`
  is buffered (`new(0)` ≡ `new()`).
- `send` waits while the channel is full; `ClosedErr` if it is closed (including while waiting).
- `try_send`: `false` when full or closed; never waits.
- `recv` waits while empty; once closed, it still returns the remaining items, then `ClosedErr`.
- `recv(timeout)`: a non-positive `timeout` checks once (so `recv(Duration::ZERO)` is a non-blocking poll).
- `close`: idempotent; wakes every waiting sender (`ClosedErr`) and receiver.
- getters `len`, `capacity`, `closed`.
- Iterating yields items until the channel is closed and drained, or yields `CancelledErr` once (§3.3).
- `ClosedErr` (`std::io`'s, message `channel is closed`).

**`Semaphore`** (a class): `Semaphore::new(permits: usize)`; `with<R>(self, f: fn() -> R) -> R | CancelledErr`
takes a permit (waiting if none is free), runs `f`, and gives the permit back however `f` exits.

## 5. Changes to existing modules

- `std::io` and `std::net`: every operation listed in §3.3 adds `| CancelledErr` to its result
  (`read_line() -> str | null | IoErr | CancelledErr`, …). Functions doing I/O with `?` declare
  `T | IoErr | CancelledErr` (or `| Err`).
- `TimedOutErr` moves to `std::time` as `TimedOutErr : Err` (re-exported from `std::net`): joins, channels and
  sockets all return it, and, as with `CancelledErr`, a timeout isn't an I/O failure (C#'s and Java's
  `TimeoutException` aren't I/O errors). *Breaking:* a socket timeout no longer matches `is NetErr`.
- `TcpListener::serve(mut self, handler: fn(TcpStream), max_connections: usize | null = null) -> () | IoErr | CancelledErr`
  - Handlers run as children of a scope inside `serve`. With `max_connections`, `serve` stops accepting
    while that many are open (the kernel backlog holds the rest).
  - **Graceful stop:** another thread closes the listener. `accept` fails with `ClosedErr`, `serve` waits for
    the open handlers to finish, then returns `()`.
  - **Abrupt stop:** cancel the thread running `serve`. `accept` returns `CancelledErr`, propagation
    cancels the handlers (their blocked reads fail and close their sockets), `serve` waits for them, then
    returns `CancelledErr`.
  - Each handler's stream is closed when the handler returns (unchanged).

## 6. Memory model additions (§13.4)

Happens-before edges: everything a thread did before `spawn`/`s.spawn` → the child's start; a thread's
completion → the `join` that returns its result, and → the return of the `scope` that owned it; a `send` → the
`recv` (or `select` arm) that receives that item; `close` → a `recv` that returns `ClosedErr` because of it;
releasing a `Mutex` or `Semaphore` permit → the next acquisition. Everything else is as today: sequentially
consistent `Atomic`s, and racy reads that never corrupt memory.

## 7. Language changes

### 7.1 `for p? in e` (spec §6.4)

`for p? in e { body }` ≡ `for tmp in e { let p = tmp?; body }`. `?` follows §7.10 unchanged: the element
type's `Err` members propagate (or `null`, if it has no `Err` member), and `p` binds what remains; it must
then be irrefutable, as now. Also `for mut p? in e` and `for (k, v)? in e`. It applies to every iterator
that yields its error once and ends: `lines()`, `incoming()`, channels.

This is Threebolt's `?` (propagate), not Swift's `case let x?` (match non-nil and skip the rest).
Filtering nulls is an iterator method, not syntax.

### 7.2 `select` (spec §7.14)

```rust
loop {
    select {
        let j = jobs.recv() => handle(j?)?,          // j: Job | ClosedErr
        let r = out.send(line) => r?,                // r: () | ClosedErr
        let res = worker.join() => report(res),
        after(Duration::from_secs(5)) => println("idle"),
    }?;
}
```

Why a keyword: a library `sync::select(cases: [Case<R>])` needs lambda arms, and Threebolt lambdas can't
`break`, `continue`, `return` or `?` out to the enclosing loop and function, so every outcome had to be
wrapped in an enum and matched a second time. `for` already rests on a std trait (`Iterable`); `select`
resting on `Receiver`/`Sender` is the same move.

Why `select`: Go, Kotlin, `tokio::select!`, `crossbeam::select!`, POSIX and Python all use it for this.
`poll` suggests a readiness check or a non-blocking attempt; `alt` (Newsqueak, Alef, Limbo) is obscure.

Why contextual (like `get`): calls were never the issue, since any keyword is allowed after `.`; but a
reserved word would stop libraries from *declaring* `fn select`. Not recognised in `if`/`while`
conditions, `match` scrutinees or `for` iterables, where `select {` could also be an identifier
followed by a block.

Why only channels, `join` and `after`: `select` promises that exactly one case happens and the others
don't. That needs runtime-owned operations that can be offered and withdrawn atomically. Sockets give only
readiness (another thread may take the connection first), a line read can't be undone halfway, and NIO
selectors need non-blocking sockets. Go draws the same line. To select on anything else, run it on a
thread that sends into a channel.

Send to a closed channel: the case is ready at once and binds `ClosedErr`, symmetric with `recv` (Go
panics; yielding `ClosedErr` from the whole `select` hides which channel; never-ready can hang).

Implementation: lowered to a runtime intrinsic over Threebolt-owned channels (a lock plus wait queues),
not a wrapped `java.util.concurrent` queue, which can't wait on several queues at once.

## 8. The chatroom under these rules

Sketches; `examples/chatroom/` is the maintained version (it also wraps each bot's result in an `Outcome`
struct, since a bot's connect timeout is a `TimedOutErr` too and must not read as the swarm's deadline).

`bots::swarm`. The deadline is the `recv` timeout, and `defer s.cancel()` abandons whoever is left:

```rust
return thread::scope((s) => {
    defer s.cancel();                    // stragglers are abandoned when we leave
    for i in 0..n {
        s.spawn(() => { _ = outcomes.send(bot(i, addr, dialling)); });   // a cancelled bot's send fails fast
    }
    let mut report = Report {};
    for _ in 0..n {
        match outcomes.recv(until.since(Instant::now())) {   // past the deadline: checks once
            is TimedOutErr => { break; }
            c is Chatted => { report.chatted += 1; report.heard += c.heard as i64; }
            _ => report.failed += 1,
        }
    }
    report.abandoned = n - report.chatted - report.failed;
    return report;
});                                      // waits for the cancelled bots to unwind
```

In `bot`, the pacing sleep propagates: `thread::sleep(Duration::from_millis(random::randint(5, 50)))?;`, and
the explicit `thread::check()?` becomes unnecessary. The read loop is `for line? in conn.lines() { heard += 1; }`.

`Hub::run`: the broadcaster lives as long as `serve`. Closing the inbox lets it finish fanning out what's
queued (graceful); cancelling the server thread stops it at once instead (propagation):

```rust
return thread::scope((s) => {
    s.spawn(() => self.broadcast());
    defer self.inbox.close();
    return listener.serve((conn) => { _ = self.session(conn); }, max_connections: max_connections);
});

fn broadcast(self) -> () | CancelledErr {
    for said? in self.inbox {
        ...
    }
}
```

`Hub::session`'s writer ends when its outbox closes: `defer outbox.close();` as the first line of the
session scope's body. `pow::solve`: `defer s.cancel();` before `return found.recv();`. In `main`, stopping the
server by cancellation makes its result `CancelledErr`, so match it before `IoErr`:

```rust
match server.join(Duration::from_secs(2)) {
    is TimedOutErr => println("server ignored the shutdown; leaving it behind"),
    is CancelledErr => println("server stopped"),
    e is Err => println(f"server failed: {e}"),
    _ => println("server stopped"),
}
```

## 9. Implementation notes (0.1, Java)

- `Threads.startThread` creates virtual threads (`Thread.ofVirtual()`); scopes are a Threebolt-side
  owner/children list plus a counter or latch, not `StructuredTaskScope`, whose rules (join required
  before close, owner-only fork, preview API) don't match §2.
- Cancellation flag: a per-thread `AtomicBoolean` in the interpreter's thread state, with the
  scope tree for propagation. Waking a blocked thread uses `Thread.interrupt()`. Java clears the interrupt
  status when it throws `InterruptedException`, so every cancellation point checks our flag rather than
  the interrupt status. `Mutex::lock` (`RawLock.acquire`) retries after an interrupt unless the process is
  terminating, then restores the interrupt status. Process termination (`Interpreter.Abort`) also uses
  interrupts today; the two are told apart by checking `isTerminating` first.
- Sockets: an interrupted virtual thread blocked in `java.net.Socket` I/O gets a `SocketException`
  and the socket is closed. §3.3 states this behaviour rather than fighting it.
- Files: keep them on stream APIs that ignore interrupts (`FileChannel` closes itself when its thread is
  interrupted); check the flag on entry and between chunks in the Threebolt-side loops.
- Channels and `select`: a Threebolt runtime structure (lock + wait queues), so `select` can wait on several.
- **Stack depth (resolved).** Virtual thread stacks live on the heap but are capped by `-Xss`: ~38K Java
  frames by default, ~44M with `-Xss1g` (as the old 1 GiB platform threads). The launcher passes `-Xss1g`;
  a JVM started without it falls back to large platform threads.
- **Risk — pinning.** An FFM downcall pins its carrier thread for the call's duration. A C call that
  blocks for a long time occupies a carrier; many of them at once starve the scheduler. Document under §14.
- The JVM bytecode backend changes none of this: virtual threads are the JVM's.

## 10. Open questions

None. Settled 2026-10-30: `TimedOutErr` moves to `std::time` as a plain `Err`; `select` as above.

## 11. Rejected

- **async/await** — non-negotiable for 1.0; also function colouring and a second std.
- **Cancel children when a scope exits** (the first chatroom draft): unconventional, and it silently
  kills work that was meant to finish. `defer s.cancel();` gives it back where wanted.
- **Cancel children when the body returns an `Err`** (Trio/Kotlin-style): with errors as values it means
  inspecting the result's runtime type, and `defer s.cancel()` is explicit.
- **Errgroup-style child failure handling** (one child's `Err` cancels its siblings): can be a library
  helper on top of scopes later.
- **A timeout on a scope's wait**: it would have to either abandon children (breaking §2's guarantee) or
  panic.
- **Preemptive or asynchronous cancellation**: defers wouldn't run and locks would be left held.
- **`sleep` returning early without an error**: on a cancelled thread it returns immediately every time,
  so a polling loop becomes a busy-spin.
- **Letting `CancelledErr` be discarded silently** (an exemption from §6.1): the discard rule is valuable
  because it has no exceptions.
- **`CancelledErr : IoErr`**: unconventional; cancellation isn't an I/O failure.
- **Library `sync::select`**: lambda arms can't `break`, `return` or `?` (§7.2).
- **Thread-locals, scoped values, implicit context parameters**: per-connection state belongs in the object
  that runs the connection, passed explicitly. Cancellation and deadlines, the usual payload of Go's `ctx`, are
  already per-thread. Scoped values (Java's `ScopedValue`) remain the shape to choose if a logging or tracing
  library later needs to pass values through code it doesn't own.
