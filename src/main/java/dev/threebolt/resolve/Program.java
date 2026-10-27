package dev.threebolt.resolve;

import java.util.Collection;
import java.util.Map;

/** The entry module, every module reachable from it through `use`, and the std modules (§2.1). */
public record Program(Module entry, Map<ModulePath, Module> modules) {
    public Collection<Module> all() {
        return modules.values();
    }

    public Module module(ModulePath path) {
        return modules.get(path);
    }
}
