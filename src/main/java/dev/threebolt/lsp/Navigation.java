package dev.threebolt.lsp;

import dev.threebolt.diag.Span;
import dev.threebolt.ir.Local;
import dev.threebolt.resolve.Module;
import dev.threebolt.types.ConstInfo;
import dev.threebolt.types.FieldInfo;
import dev.threebolt.types.FnInfo;
import dev.threebolt.types.StaticInfo;
import dev.threebolt.types.SymbolIndex;
import dev.threebolt.types.TypeInfo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/** Go to implementation and workspace symbol search. */
final class Navigation {
    private Navigation() {}

    /**
     * What implements {@code target}: a trait's impls (base lists included), a class's subclasses,
     * a method's overrides and implementations.
     */
    static List<Span> implementations(Analysis a, Object target) {
        var out = new ArrayList<Span>();
        switch (target) {
            case TypeInfo t when t.isTrait() -> {
                for (var impl : a.ts.implsOfTrait(t)) {
                    var span = impl.headSpan != null ? impl.headSpan : impl.span;
                    if (span != null) out.add(span);
                }
            }
            case TypeInfo t when t.isClass() -> {
                for (Object o : a.index.targets()) {
                    if (o instanceof TypeInfo s && s != t && s.isSubclassOf(t)) out.add(SymbolIndex.definition(s));
                }
            }
            case FnInfo f -> {
                for (Object o : a.index.targets()) {
                    if (o instanceof FnInfo g && g != f && implementsOrOverrides(g, f)) out.add(SymbolIndex.definition(g));
                }
            }
            default -> {}
        }
        out.removeIf(java.util.Objects::isNull);
        out.sort(java.util.Comparator.comparing((Span s) -> s.source().name()).thenComparingInt(Span::start));
        return out;
    }

    private static boolean implementsOrOverrides(FnInfo g, FnInfo f) {
        int depth = 0;
        for (var h = SymbolIndex.inheritsDocsFrom(g); h != null && depth < 16; h = SymbolIndex.inheritsDocsFrom(h), depth++) {
            if (h == f) return true;
        }
        return false;
    }

    /** The declarations, outside std, whose names match {@code query} (letters in order, any case). */
    static List<WorkspaceSymbol> workspaceSymbols(Collection<Analysis> analyses, String query, Predicate<Path> isStd) {
        var out = new ArrayList<WorkspaceSymbol>();
        var seen = new HashSet<String>();
        for (var a : analyses) {
            for (Module m : a.modules()) {
                var file = a.file(m.source());
                if (m.isStd() || file == null || isStd.test(file)) continue;
                for (var r : a.index.refsIn(m.source())) {
                    if (!r.isDecl()) continue;
                    var kind = kind(r.target());
                    if (kind == null) continue;
                    var name = text(r.span());
                    if (!matches(name, query) || !seen.add(file + ":" + r.span().start())) continue;
                    var loc = new Location(file.toUri().toString(), Positions.range(r.span()));
                    var symbol = new WorkspaceSymbol(name, kind, Either.forLeft(loc));
                    var container = container(r.target());
                    if (container != null) symbol.setContainerName(container);
                    out.add(symbol);
                }
            }
        }
        return out;
    }

    private static SymbolKind kind(Object target) {
        return switch (target) {
            case TypeInfo t -> switch (t.kind()) {
                case STRUCT -> SymbolKind.Struct;
                case CLASS -> SymbolKind.Class;
                case TRAIT -> SymbolKind.Interface;
                case ENUM -> SymbolKind.Enum;
                case ALIAS, PRIMITIVE -> SymbolKind.TypeParameter;
            };
            case FnInfo f -> f.owner == null ? SymbolKind.Function : f.isGetter() ? SymbolKind.Property : SymbolKind.Method;
            case FieldInfo f -> SymbolKind.Field;
            case StaticInfo s -> SymbolKind.Variable;
            case ConstInfo c -> SymbolKind.Constant;
            case SymbolIndex.EnumMember e -> SymbolKind.EnumMember;
            case Local l -> null;
            default -> null;
        };
    }

    private static String container(Object target) {
        return switch (target) {
            case FnInfo f -> f.owner == null ? null : f.qualifiedName().substring(0, f.qualifiedName().lastIndexOf("::"));
            case FieldInfo f -> f.owner().name();
            case StaticInfo s -> s.owner == null ? null : s.owner.name();
            case SymbolIndex.EnumMember e -> e.owner().name();
            default -> null;
        };
    }

    private static boolean matches(String name, String query) {
        int i = 0;
        var n = name.toLowerCase();
        for (char c : query.toLowerCase().toCharArray()) {
            i = n.indexOf(c, i);
            if (i < 0) return false;
            i++;
        }
        return true;
    }

    private static String text(Span span) {
        var t = span.source().text();
        int from = t.offsetByCodePoints(0, span.start());
        return t.substring(from, t.offsetByCodePoints(from, span.end() - span.start()));
    }
}
