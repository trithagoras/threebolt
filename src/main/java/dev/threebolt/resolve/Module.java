package dev.threebolt.resolve;

import dev.threebolt.ast.CompilationUnit;
import dev.threebolt.ast.Visibility;
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

    /** Whether {@code other} is in this module's package (§2.5): std, or the program's own code. */
    public boolean samePackage(Module other) {
        return isStd() == other.isStd();
    }

    /** Whether an item of this module with visibility {@code v} is visible from {@code from} (§2.3). */
    public boolean exposes(Visibility v, Module from) {
        return switch (v) {
            case PUB -> true;
            case INTERNAL -> samePackage(from);
            case PRIVATE, PROT -> this == from;
        };
    }

    @Override
    public String toString() {
        return path.toString();
    }
}
