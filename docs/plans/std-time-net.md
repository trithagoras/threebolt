# std::time and std::net — working plan

Roadmap task 3 (`docs/ROADMAP.md` §3), agreed 2026-10-28. `std::env` is skipped for now: `net` does not need it.
Pattern as in task 1: Threebolt over thin private intrinsics, errors as `T | SomeErr`, every module with a
spec section, a changelog entry, conformance cases (incl. an errors case), doc comments, `ModuleLoader.STD_MODULES`,
the PLAN runtime-boundary table, and a VS Code extension bump.

## Session 1 — std::time ✅ (done 2026-10-28)

**Decided** (as built; see spec §16 `std::time`):
- `Duration`: struct of **signed `i64` nanoseconds** (±292 years; subtraction may go negative instead of
  panicking). `from_secs from_millis from_micros from_nanos from_secs_f64`; `as_secs as_millis as_micros
  as_nanos as_secs_f64`, `is_zero is_negative abs`; `ZERO`, `MIN`, `MAX`; `+ -` and unary `-`; `mul(n)`/`div(n)` by
  an `i64` (operator traits take `Self` on both sides, §16); `Ord`/`Eq`/`Hash`;
  `Display` in the largest fitting unit (`1.5s`, `250ms`, `3µs`, `12ns`).
- `Instant`: monotonic (`System.nanoTime`). `now()`, `elapsed()`, `since(earlier)`; `Instant ± Duration`,
  `Instant - Instant -> Duration`; `Ord`/`Eq`. **As built**, mixed arithmetic is by method: `t.add(d)`,
  `t.sub(d)`, `t.since(earlier)` (same for `Timestamp`). No `Display` (the value is meaningless alone).
- `Timestamp`: wall clock, nanoseconds since the Unix epoch. `now()`, `UNIX_EPOCH`, `since_epoch() -> Duration`,
  `elapsed()`; `± Duration`, `Timestamp - Timestamp -> Duration` (negative if the clock went back); `Ord`/`Eq`.
- `std::thread::sleep(d: Duration)` **replaces** `sleep(ms: u64)` (spec §13.4 change).
- `time::measure<R>(f: fn() -> R) -> (R, Duration)` for benchmarks.
- Intrinsics: private `monotonic_nanos() -> i64`, `wall_nanos() -> i64`; `thread::sleep` takes nanoseconds.
- Out of scope: calendars, time zones, formatting/parsing — a later `datetime` module (std or package: undecided).

## Session 2 — std::net (and io's Read/Write traits)

**Decided:**
- **`Read`/`Write` traits in `std::io`, done in this session**, implemented by `File` and `TcpStream`.
  Each trait requires a small primitive; the user-facing methods are default bodies, so `File`'s current API
  becomes trait members (same names and signatures, so existing code keeps working):
  - `Read`: `read`, `read_line`, `lines`, `read_lines`, `read_bytes()`, `read_bytes(n)`.
  - `Write`: `write`, `write_line`, `write_bytes`, `flush`.
  - To settle at the start of the session: the exact primitive. Line reading needs a read-ahead buffer, so
    probably a required `fn read_raw(mut self, buf: [u8], max: usize) -> usize | IoErr` (0 = end) plus access to
    a buffer object (a class, so a getter shares it) holding `buf`/`pos`; `File`'s text/binary mode checks and
    its seek-back-before-write must survive (a hook the defaults call, or `File` overrides). Check `LinesIter`,
    union-safety (§3.4: `Self` only as receiver) and that trait refs to `Read` work for future `StrReader`/pipes.
- Addresses (pure Threebolt, incl. parsing): `Ipv4Addr` (`[u8; 4]`), `Ipv6Addr` (`[u16; 8]`), each with
  `parse`, `to_str`, `LOCALHOST`, `UNSPECIFIED`, `is_loopback`; `type IpAddr = Ipv4Addr | Ipv6Addr`;
  `SocketAddr { ip: IpAddr, port: u16 }` parsing `127.0.0.1:8080` and `[::1]:80`.
- `net::lookup(host) -> [IpAddr] | NetErr` (DNS, `InetAddress.getAllByName`); `connect` also takes `"host:port"`.
- `TcpListener`: `bind(addr)`, `accept() -> TcpStream | NetErr`, `incoming()`, `local_addr()` (port 0 for
  tests), `close()`; `serve(handler: fn(TcpStream))` spawning a thread per connection (no async).
- `TcpStream : Read + Write`: `connect(addr)`, `connect(addr, timeout: Duration)`, `peer_addr`, `local_addr`,
  `set_read_timeout(Duration | null)`, `set_nodelay`, `shutdown`, `close`.
- `UdpSocket`: `bind`, `send_to`, `recv_from(max) -> ([u8], SocketAddr) | NetErr`, `connect`/`send`/`recv`,
  `set_read_timeout`.
- Errors: `NetErr : IoErr` with `ConnectionRefusedErr`, `ConnectionResetErr`, `TimedOutErr`, `AddrInUseErr`,
  `AddrParseErr`, so `?` mixes file and socket code.
- Runtime: sockets live in **`std::io`'s handle table** (`SocketChannel` and `FileChannel` are both byte
  channels), so `sys_read`/`sys_write`/`sys_close` work unchanged. New intrinsics only for `tcp_connect`,
  `tcp_listen`, `accept`, `udp_bind`, `send_to`, `recv_from`, address getters, options, `lookup`. Socket
  channels are interruptible, so blocked threads unwind on process end (`Abort`).
- Out of scope: TLS, HTTP (later package), non-blocking/`select`, Unix domain sockets, multicast.

**Order:** spec (net section + io traits) → Read/Write traits refactor of `File` (all `io` conformance cases
stay green) → addresses (socket-free conformance) → TCP (loopback echo on port 0 with a client thread, connection
refused, read timeout, errors case) → UDP (loopback round trip) → wrap-up (`STD_MODULES`, docs, PLAN boundary
table, ROADMAP, extension bump). If short on budget, cut UDP and `serve` first.
