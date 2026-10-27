package dev.threebolt.ast;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.function.Consumer;

/** Visits every AST node under a root (pre-order), generically over the records. */
public final class AstWalk {
    private AstWalk() {}

    public static void walk(Object node, Consumer<Object> visitor) {
        switch (node) {
            case null -> {}
            case List<?> list -> list.forEach(n -> walk(n, visitor));
            case Node n -> {
                visitor.accept(n);
                for (RecordComponent c : n.getClass().getRecordComponents()) {
                    if (c.getType().isPrimitive() || c.getType() == String.class) continue;
                    walk(get(c, (Record) n), visitor);
                }
            }
            default -> {}
        }
    }

    private static Object get(RecordComponent c, Record r) {
        try {
            return c.getAccessor().invoke(r);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }
}
