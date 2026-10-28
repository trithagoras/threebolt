package dev.threebolt.resolve;

import dev.threebolt.ast.CompilationUnit;
import dev.threebolt.ast.Visibility;
import dev.threebolt.diag.Source;
import java.nio.file.Path;

/** A loaded `.3b` file. {@code ast} is null when it had syntax errors. */
public final class Module {
    private final Package pkg;
    private final ModulePath local;
    private final ModulePath path;
    private final Path file;
    private final Source source;
    private final CompilationUnit ast;
    private final ModuleScope scope = new ModuleScope();

    Module(Package pkg, ModulePath local, Path file, Source source, CompilationUnit ast) {
        this.pkg = pkg;
        this.local = local;
        this.path = pkg.qualified(local);
        this.file = file;
        this.source = source;
        this.ast = ast;
    }

    /**
     * Its name for messages: its path within its package, prefixed with the package's name unless
     * that is the application package (`std::io`, `shared::model`, `game::entity`).
     */
    public ModulePath path() {
        return path;
    }

    /** Its path relative to its package's source root (§2.1). */
    public ModulePath local() {
        return local;
    }

    public Package pkg() {
        return pkg;
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
        return pkg.isStd();
    }

    /** Whether {@code other} is in this module's package (§2.5). */
    public boolean samePackage(Module other) {
        return pkg == other.pkg;
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
