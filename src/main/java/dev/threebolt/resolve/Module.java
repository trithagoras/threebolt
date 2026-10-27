package dev.threebolt.resolve;

import dev.threebolt.ast.CompilationUnit;
import dev.threebolt.diag.Source;

/** A loaded `.3b` file. {@code ast} is null when it had syntax errors. */
public final class Module {
    private final ModulePath path;
    private final Source source;
    private final CompilationUnit ast;
    private final ModuleScope scope = new ModuleScope();

    Module(ModulePath path, Source source, CompilationUnit ast) {
        this.path = path;
        this.source = source;
        this.ast = ast;
    }

    public ModulePath path() {
        return path;
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
