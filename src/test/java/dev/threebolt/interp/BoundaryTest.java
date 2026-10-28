package dev.threebolt.interp;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The intrinsics reach the evaluator only through {@link Callbacks}: no field, parameter or result mentions it, the IR or frames. */
class BoundaryTest {
    private static final List<Class<?>> INTRINSICS = List.of(Natives.class, Threads.class, Ffi.class, Io.class);

    private static boolean forbidden(Class<?> c) {
        return c == Interpreter.class || c == Interpreter.Frame.class || c == Values.Env.class
                || c.getName().startsWith("dev.threebolt.ir.") || c.getName().startsWith("dev.threebolt.ast.");
    }

    @Test
    void intrinsicsSeeOnlyCallbacks() {
        for (var cls : INTRINSICS) {
            var types = new ArrayList<Class<?>>();
            for (Field f : cls.getDeclaredFields()) types.add(f.getType());
            for (Method m : cls.getDeclaredMethods()) {
                types.add(m.getReturnType());
                for (Parameter p : m.getParameters()) types.add(p.getType());
            }
            for (var t : types) assertFalse(forbidden(t), cls.getSimpleName() + " uses " + t.getName());
        }
    }
}
