package dev.threebolt.lsp;

import dev.threebolt.diag.Source;
import dev.threebolt.ir.Local;
import dev.threebolt.resolve.Module;
import dev.threebolt.types.ConstInfo;
import dev.threebolt.types.FieldInfo;
import dev.threebolt.types.FnInfo;
import dev.threebolt.types.StaticInfo;
import dev.threebolt.types.SymbolIndex;
import dev.threebolt.types.TypeInfo;
import dev.threebolt.types.TypeParamInfo;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.SemanticTokenModifiers;
import org.eclipse.lsp4j.SemanticTokenTypes;
import org.eclipse.lsp4j.SemanticTokensLegend;

/**
 * Semantic highlighting: every name the checker resolved, by what it names (keywords, literals
 * and comments stay with the TextMate grammar). Mutable locals and statics carry the `mutable`
 * modifier, which the extension underlines.
 */
final class SemanticTokens {
    static final List<String> TYPES = List.of(
            SemanticTokenTypes.Namespace, SemanticTokenTypes.Struct, SemanticTokenTypes.Class,
            SemanticTokenTypes.Interface, SemanticTokenTypes.Enum, SemanticTokenTypes.Type,
            SemanticTokenTypes.TypeParameter, SemanticTokenTypes.Parameter, SemanticTokenTypes.Variable,
            SemanticTokenTypes.Property, SemanticTokenTypes.EnumMember, SemanticTokenTypes.Function,
            SemanticTokenTypes.Method);
    static final List<String> MODIFIERS = List.of(
            SemanticTokenModifiers.Declaration, SemanticTokenModifiers.Readonly, SemanticTokenModifiers.Static,
            SemanticTokenModifiers.DefaultLibrary, "mutable");

    static final SemanticTokensLegend LEGEND = new SemanticTokensLegend(TYPES, MODIFIERS);

    private SemanticTokens() {}

    /** The tokens of {@code m}, encoded as LSP wants (relative positions, five ints each). */
    static List<Integer> of(Analysis a, Module m) {
        var params = new java.util.HashSet<Local>();
        for (Object t : a.index.targets()) {
            if (t instanceof FnInfo f) params.addAll(f.paramLocals);
        }
        var std = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Source, Boolean>());
        for (Module mod : a.modules()) if (mod.isStd()) std.add(mod.source());
        var out = new ArrayList<Integer>();
        int line = 0;
        int col = 0;
        for (var r : a.index.refsIn(m.source())) {
            var span = r.span();
            if (span.end() <= span.start() || span.source() != m.source()) continue;
            int type = type(r.target(), params);
            if (type < 0) continue;
            var range = Positions.range(span);
            if (range.getStart().getLine() != range.getEnd().getLine()) continue;
            int mods = modifiers(r, std);
            int l = range.getStart().getLine();
            int c = range.getStart().getCharacter();
            out.add(l - line);
            out.add(l == line ? c - col : c);
            out.add(range.getEnd().getCharacter() - c);
            out.add(type);
            out.add(mods);
            line = l;
            col = c;
        }
        return out;
    }

    private static int type(Object target, java.util.Set<Local> params) {
        String t = switch (target) {
            case Module m -> SemanticTokenTypes.Namespace;
            case TypeInfo ti -> switch (ti.kind()) {
                case STRUCT -> SemanticTokenTypes.Struct;
                case CLASS -> SemanticTokenTypes.Class;
                case TRAIT -> SemanticTokenTypes.Interface;
                case ENUM -> SemanticTokenTypes.Enum;
                case ALIAS, PRIMITIVE -> SemanticTokenTypes.Type;
            };
            case TypeParamInfo p -> SemanticTokenTypes.TypeParameter;
            case Local l -> l.name().equals("self") ? null
                    : params.contains(l) ? SemanticTokenTypes.Parameter : SemanticTokenTypes.Variable;
            case FieldInfo f -> SemanticTokenTypes.Property;
            case StaticInfo s -> SemanticTokenTypes.Variable;
            case ConstInfo c -> SemanticTokenTypes.Variable;
            case SymbolIndex.EnumMember e -> SemanticTokenTypes.EnumMember;
            case FnInfo f -> f.isGetter() ? SemanticTokenTypes.Property
                    : f.owner == null ? SemanticTokenTypes.Function : SemanticTokenTypes.Method;
            default -> null;
        };
        return t == null ? -1 : TYPES.indexOf(t);
    }

    private static int modifiers(SymbolIndex.Ref r, java.util.Set<Source> std) {
        int mods = 0;
        if (r.isDecl()) mods |= bit(SemanticTokenModifiers.Declaration);
        switch (r.target()) {
            case Local l when l.isMutable() -> mods |= bit("mutable");
            case StaticInfo s -> mods |= bit(SemanticTokenModifiers.Static)
                    | bit(s.isMutable() ? "mutable" : SemanticTokenModifiers.Readonly);
            case ConstInfo c -> mods |= bit(SemanticTokenModifiers.Readonly);
            case FnInfo f when f.isStatic() && f.owner != null -> mods |= bit(SemanticTokenModifiers.Static);
            default -> {}
        }
        var decl = SymbolIndex.definition(r.target());
        if (decl == null ? r.target() instanceof TypeInfo : std.contains(decl.source())) {
            mods |= bit(SemanticTokenModifiers.DefaultLibrary);
        }
        return mods;
    }

    private static int bit(String modifier) {
        return 1 << MODIFIERS.indexOf(modifier);
    }
}
