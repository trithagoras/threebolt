package dev.threebolt.interp;

import dev.threebolt.interp.Values.Int;
import dev.threebolt.interp.Values.ListVal;
import dev.threebolt.types.Prim;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The `sys_*` primitives under `std::io` and `std::net`, which are otherwise written in
 * Threebolt. They are shaped like POSIX calls so the std could move onto libc: a file or socket
 * is a numbered handle (0, 1 and 2 are the interpreter's stdin, stdout and stderr), and a call
 * returns a non-negative result or a negated error code, with a description for `sys_error`.
 */
final class Io {
    // Error codes; keep in step with the `E_*` constants in std/io.3b.
    static final int OTHER = 1, NOT_FOUND = 2, PERMISSION = 3, EXISTS = 4, IS_DIR = 5, NOT_DIR = 6, NOT_EMPTY = 7,
            BAD_HANDLE = 8, CONN_REFUSED = 9, CONN_RESET = 10, TIMED_OUT = 11, ADDR_IN_USE = 12, UNKNOWN_HOST = 13,
            CANCELLED = 14;
    /** How many bytes a file write sends between cancellation checks (§16: long writes check between chunks). */
    private static final int WRITE_CHUNK = 1 << 16;
    // `sys_open` flags; keep in step with the `O_*` constants in std/io.3b.
    private static final long READ = 1, WRITE = 2, APPEND = 4, CREATE = 8, TRUNCATE = 16, CREATE_NEW = 32;

    private final Callbacks rt;
    private final Map<Long, Handle> handles = new ConcurrentHashMap<>();
    private final AtomicLong nextHandle = new AtomicLong(3);
    private final ThreadLocal<String> lastError = ThreadLocal.withInitial(() -> "");

    /** What a handle refers to. Sockets are channels, so a thread blocked on one unwinds when interrupted. */
    private sealed interface Handle {
        java.nio.channels.Channel channel();
    }

    /** An open file. Java cannot combine READ with APPEND, so appending seeks to the end itself. */
    private record FileHandle(FileChannel channel, boolean append) implements Handle {}

    /** A TCP connection. Reads go through the socket's stream, which honours the read timeout. */
    private record TcpHandle(SocketChannel channel, InputStream in) implements Handle {
        TcpHandle(SocketChannel channel) throws IOException {
            this(channel, channel.socket().getInputStream());
        }
    }

    private record ListenerHandle(ServerSocketChannel channel) implements Handle {}

    /** A UDP socket. Receives go through its socket, which honours the read timeout. */
    private record UdpHandle(DatagramChannel channel) implements Handle {}

    Io(Callbacks rt) {
        this.rt = rt;
    }

    Object call(String name, Object[] a) {
        if (name.equals("sys_error")) return Values.Str.of(lastError.get());
        if (name.equals("sys_temp_dir")) return Values.Str.of(System.getProperty("java.io.tmpdir").replaceAll("(?<=.)[/\\\\]+$", ""));
        // Every call but the standard streams' is a cancellation point (§13.4); `print` and `readln` use those.
        if (!name.equals("sys_close") && !name.equals("sys_kind") && rt.cancelled() && !standardStream(name, a)) {
            return new Int(Prim.I64, cancelled());
        }
        long r = switch (name) {
            case "sys_open" -> open(str(a[0]), num(a[1]));
            case "sys_close" -> close(num(a[0]));
            case "sys_read" -> read(num(a[0]), (ListVal) a[1], num(a[2]));
            case "sys_write" -> write(num(a[0]), bytes((ListVal) a[1]));
            case "sys_write_str" -> write(num(a[0]), str(a[1]).getBytes(StandardCharsets.UTF_8));
            case "sys_flush" -> flush(num(a[0]));
            case "sys_seek" -> seek(num(a[0]), num(a[1]), (int) num(a[2]));
            case "sys_kind" -> kind(str(a[0]));
            case "sys_size" -> attempt(str(a[0]), () -> Files.size(path(str(a[0]))));
            case "sys_remove" -> attempt(str(a[0]), () -> {
                Files.delete(path(str(a[0])));
                return 0;
            });
            case "sys_rename" -> attempt(str(a[0]), () -> {
                Files.move(path(str(a[0])), path(str(a[1])), StandardCopyOption.REPLACE_EXISTING);
                return 0;
            });
            case "sys_mkdir" -> attempt(str(a[0]), () -> {
                Files.createDirectory(path(str(a[0])));
                return 0;
            });
            case "sys_list_dir" -> listDir(str(a[0]), (ListVal) a[1]);
            default -> throw new IllegalStateException("unknown intrinsic io." + name);
        };
        return new Int(name.equals("sys_kind") ? Prim.I32 : Prim.I64, r);
    }

    /** A call on handle 0, 1 or 2, which only `File`'s methods treat as a cancellation point. */
    private static boolean standardStream(String name, Object[] a) {
        return switch (name) {
            case "sys_read", "sys_write", "sys_write_str", "sys_flush", "sys_seek" -> num(a[0]) <= 2;
            default -> false;
        };
    }

    private long cancelled() {
        return fail(CANCELLED, "cancelled");
    }

    /**
     * {@code action} on a file: it is not interrupted by cancellation, as an interrupt would close the
     * channel (a system call in progress finishes, §13.4); on a cancelled thread it does not run.
     */
    private long onFile(IoAction action) {
        long[] r = new long[1];
        if (!rt.uninterruptibly(() -> r[0] = attempt(null, action))) return cancelled();
        return r[0];
    }

    private static String str(Object v) {
        return ((Values.Str) v).toString();
    }

    private static long num(Object v) {
        return ((Int) v).v();
    }

    private static Path path(String p) {
        return Path.of(p);
    }

    private interface IoAction {
        long run() throws IOException;
    }

    /** Runs {@code action}, turning a failure into a negated error code. */
    private long attempt(String path, IoAction action) {
        try {
            return action.run();
        } catch (IOException | InvalidPathException | UnsupportedOperationException e) {
            return fail(e, path);
        }
    }

    private long fail(int code, String message) {
        lastError.set(message);
        return -code;
    }

    private long fail(Exception e, String path) {
        // On a cancelled thread a socket operation fails because the interrupt closed it (§13.4).
        if (rt.cancelled()) return cancelled();
        String reason = reason(e);
        if (reason == null) reason = e.getClass().getSimpleName();
        return fail(code(e), path == null || reason.contains(path) ? reason : reason + ": " + path);
    }

    private static String reason(Exception e) {
        return e instanceof FileSystemException f && f.getReason() != null ? f.getReason() : e.getMessage();
    }

    /** The error code for a failure. Java reports some only through the system's reason text. */
    static int code(Exception e) {
        return switch (e) {
            case NoSuchFileException x -> NOT_FOUND;
            case AccessDeniedException x -> PERMISSION;
            case FileAlreadyExistsException x -> EXISTS;
            case DirectoryNotEmptyException x -> NOT_EMPTY;
            case NotDirectoryException x -> NOT_DIR;
            case ClosedChannelException x -> BAD_HANDLE;
            case ConnectException x -> CONN_REFUSED;
            case SocketTimeoutException x -> TIMED_OUT;
            case BindException x -> ADDR_IN_USE;
            case UnknownHostException x -> UNKNOWN_HOST;
            default -> switch (String.valueOf(reason(e))) {
                case "Is a directory" -> IS_DIR;
                case "Not a directory" -> NOT_DIR;
                case "Permission denied" -> PERMISSION;
                case "Socket closed", "Socket is closed" -> BAD_HANDLE;
                case String r when r.contains("reset") || r.contains("Broken pipe") -> CONN_RESET;
                default -> OTHER;
            };
        };
    }

    // ---- Handles ----

    private long open(String p, long flags) {
        return attempt(p, () -> {
            var path = path(p);
            if (Files.isDirectory(path)) return fail(IS_DIR, "is a directory: " + p);
            var options = new HashSet<OpenOption>();
            if ((flags & READ) != 0) options.add(StandardOpenOption.READ);
            if ((flags & WRITE) != 0) options.add(StandardOpenOption.WRITE);
            if ((flags & CREATE) != 0) options.add(StandardOpenOption.CREATE);
            if ((flags & TRUNCATE) != 0) options.add(StandardOpenOption.TRUNCATE_EXISTING);
            if ((flags & CREATE_NEW) != 0) options.add(StandardOpenOption.CREATE_NEW);
            return add(new FileHandle(FileChannel.open(path, options), (flags & APPEND) != 0));
        });
    }

    private long add(Handle handle) {
        long h = nextHandle.getAndIncrement();
        handles.put(h, handle);
        return h;
    }

    private Handle handle(long h) {
        return handles.get(h);
    }

    /** The file at {@code h}, or null if it is closed or not a file. */
    private FileHandle file(long h) {
        return handles.get(h) instanceof FileHandle f ? f : null;
    }

    private long badHandle() {
        return fail(BAD_HANDLE, "file is closed");
    }

    private long close(long h) {
        var f = handles.remove(h);
        if (f == null) return h <= 2 ? 0 : badHandle();
        return attempt(null, () -> {
            f.channel().close();
            return 0;
        });
    }

    /** Appends up to {@code max} bytes to {@code buf}; returns how many, 0 at end of file. */
    private long read(long h, ListVal buf, long max) {
        byte[] bytes = new byte[(int) Math.min(Math.max(max, 0), 1 << 20)];
        long n;
        if (h == 0) {
            rt.out().flush();
            n = attempt(null, () -> {
                synchronized (rt.stdin()) {
                    return Math.max(rt.stdin().read(bytes), 0);
                }
            });
        } else {
            var f = handle(h);
            if (f == null) return badHandle();
            if (f instanceof FileHandle file) n = onFile(() -> Math.max(file.channel.read(ByteBuffer.wrap(bytes)), 0));
            else n = attempt(null, () -> switch (f) {
                case FileHandle file -> throw new IllegalStateException();
                case TcpHandle tcp -> Math.max(tcp.in.read(bytes), 0);
                case UdpHandle udp -> receive(udp, bytes, null);
                case ListenerHandle l -> fail(OTHER, "cannot read a listener");
            });
        }
        if (n > 0) {
            synchronized (buf) {
                for (int i = 0; i < n; i++) buf.items.add(new Int(Prim.U8, bytes[i] & 0xff));
            }
        }
        return n;
    }

    private static byte[] bytes(ListVal list) {
        synchronized (list) {
            byte[] out = new byte[list.items.size()];
            for (int i = 0; i < out.length; i++) out[i] = (byte) ((Int) list.items.get(i)).v();
            return out;
        }
    }

    private long write(long h, byte[] bytes) {
        if (h == 1 || h == 2) {
            if (h == 2) rt.out().flush();
            (h == 1 ? rt.out() : rt.err()).write(bytes, 0, bytes.length);
            return bytes.length;
        }
        var f = handle(h);
        if (f == null) return badHandle();
        if (f instanceof FileHandle file) return writeFile(file, bytes);
        return attempt(null, () -> switch (f) {
            case FileHandle file -> throw new IllegalStateException();
            case TcpHandle tcp -> {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) tcp.channel.write(buffer);
                yield bytes.length;
            }
            case UdpHandle udp -> udp.channel.write(ByteBuffer.wrap(bytes));
            case ListenerHandle l -> fail(OTHER, "cannot write a listener");
        });
    }

    /** Writes {@code bytes} a chunk at a time, stopping between chunks if the thread is cancelled. */
    private long writeFile(FileHandle file, byte[] bytes) {
        synchronized (file) {
            var buffer = ByteBuffer.wrap(bytes);
            do {
                long r = onFile(() -> {
                    if (file.append) file.channel.position(file.channel.size());
                    var chunk = buffer.slice(buffer.position(), Math.min(WRITE_CHUNK, buffer.remaining()));
                    while (chunk.hasRemaining()) file.channel.write(chunk);
                    buffer.position(buffer.position() + chunk.capacity());
                    return 0;
                });
                if (r < 0) return r;
            } while (buffer.hasRemaining());
            return bytes.length;
        }
    }

    private long flush(long h) {
        if (h == 1) rt.out().flush();
        else if (h == 2) rt.err().flush();
        else if (h != 0 && handle(h) == null) return badHandle();
        return 0;
    }

    /** Moves to {@code offset} from the start (0), the current position (1) or the end (2). */
    private long seek(long h, long offset, int whence) {
        var f = file(h);
        if (f == null) {
            if (h <= 2) return fail(OTHER, "cannot seek a standard stream");
            return handle(h) == null ? badHandle() : fail(OTHER, "cannot seek a socket");
        }
        return onFile(() -> {
            synchronized (f) {
                long base = switch (whence) {
                    case 1 -> f.channel.position();
                    case 2 -> f.channel.size();
                    default -> 0;
                };
                if (base + offset < 0) return fail(OTHER, "cannot seek before the start of the file");
                f.channel.position(base + offset);
                return base + offset;
            }
        });
    }

    // ---- Sockets ----

    /** The `std::net` intrinsics. Addresses cross as text: an IP address, or `ip:port` / `[ip]:port`. */
    Object callNet(String name, Object[] a) {
        if (name.equals("sys_addr")) return Values.Str.of(address(num(a[0]), a[1] == Boolean.TRUE));
        if (rt.cancelled()) return new Int(Prim.I64, cancelled());
        long r = switch (name) {
            case "sys_tcp_connect" -> attempt(null, () -> {
                var ch = SocketChannel.open();
                try {
                    ch.socket().connect(new InetSocketAddress(ip(str(a[0])), (int) num(a[1])), (int) num(a[2]));
                    return add(new TcpHandle(ch));
                } catch (IOException e) {
                    ch.close();
                    throw e;
                }
            });
            case "sys_tcp_listen" -> attempt(null, () -> {
                var ch = ServerSocketChannel.open();
                try {
                    ch.bind(new InetSocketAddress(ip(str(a[0])), (int) num(a[1])));
                    return add(new ListenerHandle(ch));
                } catch (IOException e) {
                    ch.close();
                    throw e;
                }
            });
            case "sys_accept" -> handle(num(a[0])) instanceof ListenerHandle l
                    ? attempt(null, () -> add(new TcpHandle(l.channel.accept())))
                    : badHandle();
            case "sys_udp_bind" -> attempt(null, () -> {
                var ch = DatagramChannel.open();
                try {
                    ch.bind(new InetSocketAddress(ip(str(a[0])), (int) num(a[1])));
                    return add(new UdpHandle(ch));
                } catch (IOException e) {
                    ch.close();
                    throw e;
                }
            });
            case "sys_udp_connect" -> handle(num(a[0])) instanceof UdpHandle u
                    ? attempt(null, () -> {
                        u.channel.connect(new InetSocketAddress(ip(str(a[1])), (int) num(a[2])));
                        return 0;
                    })
                    : badHandle();
            case "sys_send_to" -> handle(num(a[0])) instanceof UdpHandle u
                    ? attempt(null, () -> {
                        var to = new InetSocketAddress(ip(str(a[2])), (int) num(a[3]));
                        return u.channel.send(ByteBuffer.wrap(bytes((ListVal) a[1])), to);
                    })
                    : badHandle();
            case "sys_recv_from" -> recvFrom(num(a[0]), (ListVal) a[1], num(a[2]), (ListVal) a[3]);
            case "sys_set_timeout" -> attempt(null, () -> {
                int ms = (int) Math.min(num(a[1]), Integer.MAX_VALUE);
                switch (handle(num(a[0]))) {
                    case TcpHandle t -> t.channel.socket().setSoTimeout(ms);
                    case UdpHandle u -> u.channel.socket().setSoTimeout(ms);
                    case null, default -> {
                        return badHandle();
                    }
                }
                return 0;
            });
            case "sys_set_nodelay" -> handle(num(a[0])) instanceof TcpHandle t
                    ? attempt(null, () -> {
                        t.channel.setOption(StandardSocketOptions.TCP_NODELAY, a[1] == Boolean.TRUE);
                        return 0;
                    })
                    : badHandle();
            case "sys_shutdown" -> handle(num(a[0])) instanceof TcpHandle t
                    ? attempt(null, () -> {
                        long how = num(a[1]);
                        if (how != 1) t.channel.shutdownInput();
                        if (how != 0) t.channel.shutdownOutput();
                        return 0;
                    })
                    : badHandle();
            case "sys_lookup" -> attempt(null, () -> {
                var found = InetAddress.getAllByName(str(a[0]));
                var out = (ListVal) a[1];
                synchronized (out) {
                    for (var addr : found) out.items.add(Values.Str.of(text(addr)));
                }
                return found.length;
            });
            default -> throw new IllegalStateException("unknown intrinsic net." + name);
        };
        return new Int(Prim.I64, r);
    }

    /** The address of an IP address's text, without a lookup. */
    private static InetAddress ip(String text) throws UnknownHostException {
        return InetAddress.ofLiteral(text);
    }

    /** An address as `std::net` parses it: without an IPv6 scope. */
    private static String text(InetAddress addr) {
        String s = addr.getHostAddress();
        int scope = s.indexOf('%');
        return scope < 0 ? s : s.substring(0, scope);
    }

    private static String text(java.net.SocketAddress sa) {
        if (!(sa instanceof InetSocketAddress isa) || isa.getAddress() == null) return "";
        var ip = isa.getAddress();
        return (ip instanceof Inet6Address ? "[" + text(ip) + "]" : text(ip)) + ":" + isa.getPort();
    }

    /** The local (or peer) address of a socket, or "" if it has none. */
    private String address(long h, boolean peer) {
        try {
            return switch (handle(h)) {
                case TcpHandle t -> text(peer ? t.channel.getRemoteAddress() : t.channel.getLocalAddress());
                case ListenerHandle l -> peer ? "" : text(l.channel.getLocalAddress());
                case UdpHandle u -> text(peer ? u.channel.getRemoteAddress() : u.channel.getLocalAddress());
                case null, default -> "";
            };
        } catch (IOException e) {
            return "";
        }
    }

    /** Receives one datagram into {@code bytes} (cut to its length); returns its length. */
    private static long receive(UdpHandle udp, byte[] bytes, ListVal from) throws IOException {
        var packet = new DatagramPacket(bytes, bytes.length);
        udp.channel.socket().receive(packet);
        if (from != null) {
            synchronized (from) {
                from.items.add(Values.Str.of(text(packet.getSocketAddress())));
            }
        }
        return packet.getLength();
    }

    /** Appends the next datagram (up to {@code max} bytes) to {@code buf} and its sender to {@code from}. */
    private long recvFrom(long h, ListVal buf, long max, ListVal from) {
        if (!(handle(h) instanceof UdpHandle u)) return badHandle();
        byte[] bytes = new byte[(int) Math.min(Math.max(max, 0), 65535)];
        long n = attempt(null, () -> receive(u, bytes, from));
        if (n > 0) {
            synchronized (buf) {
                for (int i = 0; i < n; i++) buf.items.add(new Int(Prim.U8, bytes[i] & 0xff));
            }
        }
        return n;
    }

    // ---- Paths ----

    /** 0: nothing there, 1: a file, 2: a directory, 3: something else. Follows symbolic links. */
    private long kind(String p) {
        try {
            var path = path(p);
            if (Files.isRegularFile(path)) return 1;
            if (Files.isDirectory(path)) return 2;
            return Files.exists(path) ? 3 : 0;
        } catch (InvalidPathException e) {
            return 0;
        }
    }

    /** Appends the names in directory {@code p}, sorted, to {@code out}; returns how many. */
    private long listDir(String p, ListVal out) {
        return attempt(p, () -> {
            try (var entries = Files.list(path(p))) {
                var names = entries.map(e -> e.getFileName().toString()).sorted().toList();
                synchronized (out) {
                    for (String n : names) out.items.add(Values.Str.of(n));
                }
                return names.size();
            }
        });
    }
}
