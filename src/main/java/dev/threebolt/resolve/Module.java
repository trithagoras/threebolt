package dev.threebolt.resolve;

import dev.threebolt.ast.CompilationUnit;
import dev.threebolt.diag.Source;
import java.nio.file.Path;

/** A loaded `.3b` file. {@code ast} is null when it had syntax errors. */
public final class Module {
    private final ModulePath path;
    private final Path file;
    private final Source source;
    private final CompilationUnit ast;
    private final ModuleScope scope = new ModuleScope();

    Module(ModulePath path, Path file, Source source, CompilationUnit ast) {
        this.path = path;
        this.file = file;
        this.source = source;
        this.ast = ast;
    }

    public ModulePath path() {
        return path;
    }

    /** The absolute file it was read from; null for std modules (resources). */
    public Path file() {
        return file;
    }

    public Source source() {
        return source;
    }

    public CompilationUnit ast() {
        return ast;
    }

    public ModuleScope scope() {
        return scope;
    }

    public boolean isStd() {
        return path.isStd();
    }

    @Override
    public String toString() {
        return path.toString();
    }
}
