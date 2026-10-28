package dev.threebolt.lsp;

import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.PrintStream;
import org.eclipse.lsp4j.launch.LSPLauncher;

/** {@code bolt lsp}: serves the Language Server Protocol over stdin and stdout. */
public final class LspMain {
    private LspMain() {}

    public static void run() throws Exception {
        // Stdout carries the protocol; anything else printed goes to stderr.
        var protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
        System.setOut(System.err);
        var server = new Server();
        var launcher = LSPLauncher.createServerLauncher(server, System.in, protocol);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get();
    }
}
