package dev.threebolt.interp;

import dev.threebolt.interp.Values.Int;
import dev.threebolt.interp.Values.ListVal;
import dev.threebolt.types.Prim;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
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
 * The `sys_*` primitives under `std::io`, which is otherwise written in Threebolt. They are shaped
 * like POSIX calls so the std could move onto libc: a file is a numbered handle (0, 1 and 2 are
 * the interpreter's stdin, stdout and stderr), and a call returns a non-negative result or a
 * negated error code, with a description for `sys_error`.
 */
final class Io {
    // Error codes; keep in step with the `E_*` constants in std/io.3b.
    static final int OTHER = 1, NOT_FOUND = 2, PERMISSION = 3, EXISTS = 4, IS_DIR = 5, NOT_DIR = 6, NOT_EMPTY = 7,
            BAD_HANDLE = 8;
    // `sys_open` flags; keep in step with the `O_*` constants in std/io.3b.
    private static final long READ = 1, WRITE = 2, APPEND = 4, CREATE = 8, TRUNCATE = 16, CREATE_NEW = 32;

    private final Interpreter in;
    private final Map<Long, Handle> handles = new ConcurrentHashMap<>();
    private final AtomicLong nextHandle = new AtomicLong(3);
    private final ThreadLocal<String> lastError = ThreadLocal.withInitial(() -> "");

    /** An open file. Java cannot combine READ with APPEND, so appending seeks to the end itself. */
    private record Handle(FileChannel channel, boolean append) {}

    Io(Interpreter in) {
        this.in = in;
    }

    Object call(String name, Object[] a) {
        if (name.equals("sys_error")) return lastError.get();
        if (name.equals("sys_temp_dir")) return System.getProperty("java.io.tmpdir").replaceAll("(?<=.)[/\\\\]+$", "");
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

    private static String str(Object v) {
        return (String) v;
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
            default -> switch (String.valueOf(reason(e))) {
                case "Is a directory" -> IS_DIR;
                case "Not a directory" -> NOT_DIR;
                case "Permission denied" -> PERMISSION;
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
            long h = nextHandle.getAndIncrement();
            handles.put(h, new Handle(FileChannel.open(path, options), (flags & APPEND) != 0));
            return h;
        });
    }

    private Handle handle(long h) {
        return handles.get(h);
    }

    private long badHandle() {
        return fail(BAD_HANDLE, "file is closed");
    }

    private long close(long h) {
        var f = handles.remove(h);
        if (f == null) return h <= 2 ? 0 : badHandle();
        return attempt(null, () -> {
            f.channel.close();
            return 0;
        });
    }

    /** Appends up to {@code max} bytes to {@code buf}; returns how many, 0 at end of file. */
    private long read(long h, ListVal buf, long max) {
        byte[] bytes = new byte[(int) Math.min(Math.max(max, 0), 1 << 20)];
        long n;
        if (h == 0) {
            in.out.flush();
            n = attempt(null, () -> {
                synchronized (in.stdin) {
                    return Math.max(in.stdin.read(bytes), 0);
                }
            });
        } else {
            var f = handle(h);
            if (f == null) return badHandle();
            n = attempt(null, () -> Math.max(f.channel.read(ByteBuffer.wrap(bytes)), 0));
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
            if (h == 2) in.out.flush();
            (h == 1 ? in.out : in.err).write(bytes, 0, bytes.length);
            return bytes.length;
        }
        var f = handle(h);
        if (f == null) return badHandle();
        return attempt(null, () -> {
            synchronized (f) {
                if (f.append) f.channel.position(f.channel.size());
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) f.channel.write(buffer);
                return bytes.length;
            }
        });
    }

    private long flush(long h) {
        if (h == 1) in.out.flush();
        else if (h == 2) in.err.flush();
        else if (h != 0 && handle(h) == null) return badHandle();
        return 0;
    }

    /** Moves to {@code offset} from the start (0), the current position (1) or the end (2). */
    private long seek(long h, long offset, int whence) {
        var f = handle(h);
        if (f == null) return h <= 2 ? fail(OTHER, "cannot seek a standard stream") : badHandle();
        return attempt(null, () -> {
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
                    out.items.addAll(names);
                }
                return names.size();
            }
        });
    }
}
