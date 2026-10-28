package dev.threebolt.lsp;

import dev.threebolt.ir.Local;
import dev.threebolt.resolve.Module;
import dev.threebolt.types.Type;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Inferred types after bindings written without one: `let x = 1` shows `x: i32`, as do
 * pattern bindings and lambda parameters. A name followed by `:` already has its type.
 */
final class InlayHints {
    private InlayHints() {}

    static List<InlayHint> of(Analysis a, Module m, Range range) {
        var out = new ArrayList<InlayHint>();
        var text = m.source().text().codePoints().toArray();
        for (var r : a.index.refsIn(m.source())) {
            if (!r.isDecl() || !(r.target() instanceof Local l) || l.name().equals("self")) continue;
            // A type the checker could not work out (it reported why) is no hint.
            if (l.type == null || l.type == Type.UNBOUND || l.type.toString().contains(Type.ERROR.toString())) continue;
            int end = r.span().end();
            int i = end;
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++;
            if (i < text.length && text[i] == ':' && (i + 1 >= text.length || text[i + 1] != ':')) continue;
            var at = Positions.position(m.source(), end);
            if (!within(at, range)) continue;
            var hint = new InlayHint(at, Either.forLeft(": " + l.type));
            hint.setKind(InlayHintKind.Type);
            out.add(hint);
        }
        return out;
    }

    private static boolean within(org.eclipse.lsp4j.Position p, Range r) {
        if (r == null) return true;
        return compare(p, r.getStart()) >= 0 && compare(p, r.getEnd()) <= 0;
    }

    private static int compare(org.eclipse.lsp4j.Position a, org.eclipse.lsp4j.Position b) {
        return a.getLine() != b.getLine() ? Integer.compare(a.getLine(), b.getLine())
                : Integer.compare(a.getCharacter(), b.getCharacter());
    }
}
