package dev.threebolt.lsp;

import dev.threebolt.resolve.Module;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Copies of the std modules on disk, so the editor can open them (go to definition). They are
 * written from the loaded source, appended platform aliases included, so spans line up.
 */
final class StdFiles {
    private final Path dir;

    StdFiles() {
        try {
            dir = Files.createTempDirectory("threebolt-std").toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    boolean contains(Path file) {
        return file.startsWith(dir);
    }

    /** The copy of std module {@code m}, written on first use. */
    synchronized Path file(Module m) {
        var file = dir.resolve(m.source().name());
        if (!Files.exists(file)) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, m.source().text());
                file.toFile().setReadOnly();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return file;
    }
}
