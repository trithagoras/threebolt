package dev.threebolt.ast;

import dev.threebolt.diag.Span;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.List;

/**
 * Renders an AST as an s-expression of every record component except spans, e.g.
 * {@code (Binary ADD (IntLit 1 null) (Path [(PathSegment x [])]))}. Generic over the records, so
 * two trees with equal dumps are equal up to spans; used by `threebolt ast` and round-trip tests.
 */
public final class AstDump {
    private AstDump() {}

    public static String render(Object node) {
        var out = new StringBuilder();
        render(node, out);
        return out.toString();
    }

    private static void render(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case Name n -> out.append(n.text());
            case String s -> quote(s, out);
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) out.append(' ');
                    render(list.get(i), out);
                }
                out.append(']');
            }
            case Record r -> {
                out.append('(').append(r.getClass().getSimpleName());
                for (RecordComponent c : r.getClass().getRecordComponents()) {
                    if (c.getType() == Span.class) continue;
                    out.append(' ');
                    render(get(c, r), out);
                }
                out.append(')');
            }
            default -> out.append(value); // enums, booleans, numbers
        }
    }

    private static Object get(RecordComponent c, Record r) {
        try {
            return c.getAccessor().invoke(r);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        s.codePoints().forEach(cp -> {
            switch (cp) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> {
                    if (Character.isISOControl(cp)) out.append(String.format("\\u{%X}", cp));
                    else out.appendCodePoint(cp);
                }
            }
        });
        out.append('"');
    }
}
