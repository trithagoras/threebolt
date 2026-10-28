package dev.threebolt.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The error codes std::io's and std::net's `sys_*` intrinsics report for the system's failures. */
class IoTest {
    @TempDir
    Path dir;

    private static int code(org.junit.jupiter.api.function.Executable action) {
        return Io.code(assertThrows(IOException.class, action));
    }

    @Test
    void mapsWhatTheSystemReports() throws IOException {
        var file = Files.writeString(dir.resolve("f.txt"), "x");
        Files.writeString(Files.createDirectory(dir.resolve("full")).resolve("g.txt"), "y");

        assertEquals(Io.NOT_FOUND, code(() -> Files.size(dir.resolve("missing"))));
        assertEquals(Io.EXISTS, code(() -> Files.createDirectory(dir.resolve("full"))));
        assertEquals(Io.NOT_EMPTY, code(() -> Files.delete(dir.resolve("full"))));
        assertEquals(Io.NOT_DIR, code(() -> Files.createDirectory(file.resolve("sub"))));
        assertEquals(Io.NOT_DIR, code(() -> Files.list(file).close()));
        try (var ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            assertEquals(Io.IS_DIR, code(() -> ch.read(ByteBuffer.allocate(1))));
        }
        var closed = FileChannel.open(file);
        closed.close();
        assertEquals(Io.BAD_HANDLE, code(() -> closed.read(ByteBuffer.allocate(1))));
    }

    @Test
    void mapsReasonsAndFallsBackToOther() {
        assertEquals(Io.PERMISSION, Io.code(new FileSystemException("p", null, "Permission denied")));
        assertEquals(Io.OTHER, Io.code(new FileSystemException("p", null, "Disk quota exceeded")));
        assertEquals(Io.OTHER, Io.code(new IOException()));
    }

    @Test
    void mapsSocketFailures() throws IOException {
        var loopback = InetAddress.getLoopbackAddress();
        try (var listener = ServerSocketChannel.open().bind(new InetSocketAddress(loopback, 0))) {
            var taken = listener.getLocalAddress();
            assertEquals(Io.ADDR_IN_USE, code(() -> ServerSocketChannel.open().bind(taken).close()));
            try (var client = SocketChannel.open(taken); var server = listener.accept()) {
                client.socket().setSoTimeout(10);
                assertEquals(Io.TIMED_OUT, code(() -> client.socket().getInputStream().read()));
            }
        }
        int free;
        try (var probe = new ServerSocket(0, 1, loopback)) {
            free = probe.getLocalPort();
        }
        assertEquals(Io.CONN_REFUSED, code(() -> SocketChannel.open(new InetSocketAddress(loopback, free)).close()));
        assertEquals(Io.CONN_RESET, Io.code(new SocketException("Connection reset by peer")));
        assertEquals(Io.CONN_RESET, Io.code(new IOException("Broken pipe")));
        assertEquals(Io.BAD_HANDLE, Io.code(new SocketException("Socket closed")));
        assertEquals(Io.UNKNOWN_HOST, Io.code(new UnknownHostException("x.invalid")));
    }
}
