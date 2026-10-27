package dev.threebolt.lsp;

import dev.threebolt.ast.CompilationUnit;
import dev.threebolt.ast.Item;
import dev.threebolt.ast.Member;
import dev.threebolt.ast.Name;
import dev.threebolt.diag.Span;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.SymbolKind;

/** A module's items and their members, for the editor's outline (document symbols). */
final class Outline {
    private Outline() {}

    static List<DocumentSymbol> of(CompilationUnit unit) {
        var out = new ArrayList<DocumentSymbol>();
        for (Item item : unit.items()) {
            var s = item(item);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static DocumentSymbol item(Item item) {
        return switch (item) {
            case Item.Fn f -> symbol(f.name(), SymbolKind.Function, f.span(), List.of());
            case Item.Struct s -> symbol(s.name(), SymbolKind.Struct, s.span(), members(s.members()));
            case Item.Class c -> symbol(c.name(), SymbolKind.Class, c.span(), members(c.members()));
            case Item.Trait t -> symbol(t.name(), SymbolKind.Interface, t.span(), members(t.members()));
            case Item.Enum e -> symbol(e.name(), SymbolKind.Enum, e.span(), e.members().stream()
                    .map(m -> symbol(m.name(), SymbolKind.EnumMember, m.span(), List.of())).toList());
            case Item.TypeAlias a -> symbol(a.name(), SymbolKind.TypeParameter, a.span(), List.of());
            case Item.Const c -> symbol(c.name(), SymbolKind.Constant, c.span(), List.of());
            case Item.Static s -> symbol(s.name(), SymbolKind.Variable, s.span(), List.of());
            case Item.Impl i -> {
                String target = text(i.target().span());
                String name = i.trait() == null ? "impl " + target : "impl " + text(i.trait().span()) + " for " + target;
                yield new DocumentSymbol(name, SymbolKind.Namespace, Positions.range(i.span()),
                        Positions.range(i.trait() != null ? i.trait().span() : i.target().span()), null, members(i.members()));
            }
            case Item.Extern x -> new DocumentSymbol("extern \"" + x.abi() + "\"", SymbolKind.Namespace,
                    Positions.range(x.span()), Positions.range(x.span()), null,
                    x.fns().stream().map(f -> symbol(f.name(), SymbolKind.Function, f.span(), List.of())).toList());
            case Item.Use u -> null;
        };
    }

    private static List<DocumentSymbol> members(List<Member> members) {
        return members.stream().map(m -> switch (m) {
            case Member.Field f -> symbol(f.name(), SymbolKind.Field, f.span(), List.of());
            case Item.Fn f -> symbol(f.name(), f.isGetter() ? SymbolKind.Property : SymbolKind.Method, f.span(), List.of());
            case Item.Static s -> symbol(s.name(), SymbolKind.Variable, s.span(), List.of());
        }).toList();
    }

    private static DocumentSymbol symbol(Name name, SymbolKind kind, Span span, List<DocumentSymbol> children) {
        return new DocumentSymbol(name.text(), kind, Positions.range(span), Positions.range(name.span()), null,
                children.isEmpty() ? null : children);
    }

    private static String text(Span span) {
        var t = span.source().text();
        int from = t.offsetByCodePoints(0, span.start());
        return t.substring(from, t.offsetByCodePoints(from, span.end() - span.start())).replaceAll("\\s+", " ");
    }
}
