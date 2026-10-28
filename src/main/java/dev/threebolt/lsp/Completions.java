package dev.threebolt.lsp;

import dev.threebolt.ast.Item;
import dev.threebolt.ast.Visibility;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Source;
import dev.threebolt.parse.AstParser;
import dev.threebolt.parser.ThreeboltLexer;
import dev.threebolt.resolve.ModuleLoader;
import dev.threebolt.resolve.ModulePath;
import dev.threebolt.resolve.Symbol;
import dev.threebolt.types.Completion;
import dev.threebolt.types.Prim;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Tuple;

/**
 * Completion and signature help. The text being edited rarely parses (`p.` alone does not), so
 * {@link Completion#PLACEHOLDER} goes in at the cursor, plus whatever closes the statement, and
 * the patched program is checked; the checker lists what fits where it meets the placeholder.
 * When no patched text parses, the file's module-level names, the prelude and keywords are
 * offered instead. `use` lines need no types: modules come from the files under the source root.
 */
final class Completions {
    private static final List<String> KEYWORDS = keywords();

    private final Workspace workspace;

    Completions(Workspace workspace) {
        this.workspace = workspace;
    }

    /** What the cursor sits in: the text around it and the tokens before it. */
    private record Cursor(Path file, String text, int offset, String before, String after, List<Token> tokens) {}

    private Cursor cursor(Path file, Position position) {
        if (!workspace.checkable(file)) return null;
        var text = workspace.text(file);
        if (text == null) return null;
        int offset = Positions.offset(new Source(file.getFileName().toString(), text), position);
        int split = text.offsetByCodePoints(0, offset);
        var before = text.substring(0, split);
        return new Cursor(file, text, offset, before, text.substring(split), tokens(before));
    }

    // ---- Completion ----

    List<CompletionItem> complete(Path file, Position position) throws Exception {
        var at = cursor(file, position);
        if (at == null || inCommentOrString(at)) return List.of();
        // `:` triggers completion only as the second half of `::`.
        if (at.before.endsWith(":") && !at.before.endsWith("::")) return List.of();
        var use = useLine(at);
        if (use != null) return use;
        for (var text : patched(at, true)) {
            if (!parses(file, text)) continue;
            var completion = new Completion(file, at.offset);
            workspace.check(file, text, completion);
            if (completion.site() == null) return List.of();
            return items(at, completion);
        }
        return fallback(at);
    }

    private List<CompletionItem> items(Cursor at, Completion completion) {
        var out = new ArrayList<CompletionItem>();
        var seen = new LinkedHashSet<String>();
        String prefix = completion.prefix();
        for (var i : completion.items()) {
            if (matches(i.name(), prefix) && seen.add(i.name())) {
                var item = item(i.name(), kind(i.kind()), i.detail(), i.rank());
                var doc = Docs.markup(i.target());
                if (doc != null) item.setDocumentation(doc);
                out.add(item);
            }
        }
        if (completion.site() == Completion.Site.MODULE_ITEM) {
            for (var m : moduleFiles(at.file, completion.module())) {
                if (matches(m, prefix) && seen.add(m)) out.add(item(m, CompletionItemKind.Module, null, 2));
            }
        }
        if (completion.site() == Completion.Site.NAME || completion.site() == Completion.Site.TYPE) {
            // Modules named by a fully-qualified path (§2.2) need no `use`.
            for (var m : moduleFiles(at.file, null)) {
                if (matches(m, prefix) && seen.add(m)) out.add(item(m, CompletionItemKind.Module, null, 2));
            }
        }
        if (completion.site() == Completion.Site.NAME) {
            for (var k : KEYWORDS) {
                if (matches(k, prefix) && seen.add(k)) out.add(item(k, CompletionItemKind.Keyword, null, 4));
            }
        }
        return out;
    }

    /**
     * The text with the placeholder (when {@code placeholder}) at the cursor, followed by each of:
     * nothing, `;`, the brackets left open in the current statement and `;`, the same with
     * ` = 0;` (a `let` with a type) or ` {}` (a function signature). Braces still open at the end
     * of the file are closed there.
     */
    private static List<String> patched(Cursor at, boolean placeholder) {
        var open = new ArrayDeque<Integer>();
        for (var t : at.tokens) {
            switch (t.getType()) {
                case ThreeboltLexer.LPAREN, ThreeboltLexer.LBRACKET, ThreeboltLexer.LBRACE -> open.push(t.getType());
                case ThreeboltLexer.RPAREN, ThreeboltLexer.RBRACKET, ThreeboltLexer.RBRACE -> {
                    if (!open.isEmpty()) open.pop();
                }
                default -> {}
            }
        }
        var inner = new StringBuilder();
        for (int type : open) {
            if (type == ThreeboltLexer.LBRACE) break;
            inner.append(type == ThreeboltLexer.LPAREN ? ")" : "]");
        }
        int braces = 0;
        for (var t : tokens(at.before + at.after)) {
            if (t.getType() == ThreeboltLexer.LBRACE) braces++;
            if (t.getType() == ThreeboltLexer.RBRACE) braces--;
        }
        var end = "}".repeat(Math.max(0, braces));
        var out = new LinkedHashSet<String>();
        for (var suffix : List.of("", ";", inner + ";", inner + " = 0;", inner + " {}")) {
            out.add(at.before + (placeholder ? Completion.PLACEHOLDER : "") + suffix + at.after + end);
        }
        return List.copyOf(out);
    }

    private static boolean parses(Path file, String text) {
        return AstParser.compilationUnit(new Source(file.getFileName().toString(), text), new Diagnostics()) != null;
    }

    /** No patched text parses: the module-level names the file last had, the prelude and keywords. */
    private List<CompletionItem> fallback(Cursor at) {
        String prefix = wordBefore(at.before);
        var out = new LinkedHashMap<String, CompletionItem>();
        var a = workspace.forQuery(at.file);
        var m = a == null ? null : a.module(at.file);
        if (m != null) {
            m.scope().items().forEach((name, decls) -> add(out, prefix, name, kind(decls.getFirst()), 1));
            m.scope().imports().forEach((name, i) -> {
                if (!i.targets().isEmpty()) add(out, prefix, name, kind(i.targets().getFirst()), 1);
            });
            m.scope().prelude().forEach((name, targets) -> add(out, prefix, name, kind(targets.getFirst()), 3));
        }
        for (Prim p : Prim.values()) {
            if (Prim.named(p.text()) == p) add(out, prefix, p.text(), CompletionItemKind.Keyword, 3);
        }
        for (var k : KEYWORDS) add(out, prefix, k, CompletionItemKind.Keyword, 4);
        return List.copyOf(out.values());
    }

    private static void add(Map<String, CompletionItem> out, String prefix, String name, CompletionItemKind kind, int rank) {
        if (matches(name, prefix)) out.putIfAbsent(name, item(name, kind, null, rank));
    }

    // ---- `use` lines ----

    /**
     * Completes a module path or item in a `use` item ({@code use a::b::|}, {@code use a::{x, |}}),
     * or returns null when the cursor is not in one.
     */
    private List<CompletionItem> useLine(Cursor at) {
        var toks = at.tokens;
        int use = -1;
        for (int i = toks.size() - 1; i >= 0; i--) {
            int type = toks.get(i).getType();
            if (type == ThreeboltLexer.USE) {
                use = i;
                break;
            }
            if (type != ThreeboltLexer.IDENTIFIER && type != ThreeboltLexer.COLON_COLON && type != ThreeboltLexer.LBRACE
                    && type != ThreeboltLexer.COMMA && type != ThreeboltLexer.AS) {
                return null;
            }
        }
        if (use < 0) return null;
        var rest = toks.subList(use + 1, toks.size());
        var last = rest.isEmpty() ? null : rest.getLast();
        boolean partial = last != null && last.getType() == ThreeboltLexer.IDENTIFIER && !at.before.isEmpty()
                && isIdentChar(at.before.charAt(at.before.length() - 1));
        String prefix = partial ? last.getText() : "";
        if (partial) rest = rest.subList(0, rest.size() - 1);
        if (!rest.isEmpty() && rest.getLast().getType() == ThreeboltLexer.AS) return List.of();
        var segments = new ArrayList<String>();
        boolean group = false;
        for (var t : rest) {
            if (t.getType() == ThreeboltLexer.LBRACE) group = true;
            else if (t.getType() == ThreeboltLexer.IDENTIFIER && !group) segments.add(t.getText());
        }
        if (!group && !rest.isEmpty() && rest.getLast().getType() != ThreeboltLexer.COLON_COLON) return List.of();
        var out = new LinkedHashMap<String, CompletionItem>();
        if (!segments.isEmpty()) {
            // Load the program with just this `use` in the file: the loader then reads the module.
            var path = new ModulePath(segments);
            var text = "use " + path + "::" + Completion.PLACEHOLDER + ";\n";
            var program = workspace.load(at.file, text);
            var m = program.module(path);
            if (m != null && m.ast() != null) {
                boolean own = at.file.equals(m.file());
                m.scope().items().forEach((name, decls) -> {
                    if (own || decls.stream().anyMatch(d -> d.item().visibility() == Visibility.PUB)) {
                        add(out, prefix, name, kind(decls.getFirst()), 1);
                    }
                });
            }
        }
        for (var name : moduleFiles(at.file, segments.isEmpty() ? null : new ModulePath(segments))) {
            add(out, prefix, name, CompletionItemKind.Module, 2);
        }
        return List.copyOf(out.values());
    }

    /**
     * The modules directly under {@code parent} (null: the source root) on disk, or in std; the
     * root also has `std`.
     */
    private List<String> moduleFiles(Path file, ModulePath parent) {
        var out = new ArrayList<String>();
        if (parent == null) out.add("std");
        if (parent != null && parent.isStd()) {
            if (parent.segments().size() == 1) out.addAll(ModuleLoader.STD_MODULES.stream().sorted().toList());
            return out;
        }
        var dir = workspace.sourceRoot(file);
        if (parent != null) for (var s : parent.segments()) dir = dir.resolve(s);
        if (!Files.isDirectory(dir)) return out;
        try (var entries = Files.list(dir)) {
            for (var p : entries.sorted().toList()) {
                var name = p.getFileName().toString();
                if (name.endsWith(".3b") && Files.isRegularFile(p)) name = name.substring(0, name.length() - 3);
                else if (!Files.isDirectory(p)) continue;
                if (isIdentifier(name) && !out.contains(name) && !p.equals(file)) out.add(name);
            }
        } catch (IOException e) {
            // An unreadable directory has no modules to offer.
        }
        return out;
    }

    // ---- Signature help ----

    SignatureHelp signatureHelp(Path file, Position position) throws Exception {
        var at = cursor(file, position);
        if (at == null || inCommentOrString(at)) return null;
        var candidates = new ArrayList<String>(patched(at, false));
        candidates.addAll(patched(at, true));
        for (var text : candidates) {
            if (!parses(file, text)) continue;
            var completion = new Completion(file, at.offset);
            workspace.check(file, text, completion);
            if (completion.signatures().isEmpty()) return null;
            return help(at, completion);
        }
        return null;
    }

    private static SignatureHelp help(Cursor at, Completion completion) {
        // The argument the cursor is in: commas at depth 0 after the call's `(`, or `name:`.
        int index = 0;
        String named = null;
        int depth = -1;
        var argStart = new ArrayList<Token>();
        for (var t : at.tokens) {
            if (t.getStartIndex() < completion.argsFrom()) continue;
            switch (t.getType()) {
                case ThreeboltLexer.LPAREN, ThreeboltLexer.LBRACKET, ThreeboltLexer.LBRACE -> {
                    depth++;
                    if (depth == 0) argStart.clear();
                    continue;
                }
                case ThreeboltLexer.RPAREN, ThreeboltLexer.RBRACKET, ThreeboltLexer.RBRACE -> depth--;
                case ThreeboltLexer.COMMA -> {
                    if (depth == 0) {
                        index++;
                        argStart.clear();
                        continue;
                    }
                }
                default -> {}
            }
            if (depth == 0) argStart.add(t);
        }
        if (argStart.size() >= 2 && argStart.get(0).getType() == ThreeboltLexer.IDENTIFIER
                && argStart.get(1).getType() == ThreeboltLexer.COLON) {
            named = argStart.get(0).getText();
        }
        var sigs = new ArrayList<SignatureInformation>();
        int active = -1;
        for (var s : completion.signatures()) {
            var params = new ArrayList<ParameterInformation>();
            for (var p : s.params()) {
                params.add(new ParameterInformation());
                // Java string offsets are UTF-16 units, as LSP counts them.
                params.getLast().setLabel(Tuple.two(p[0], p[1]));
            }
            int param = named != null ? s.names().indexOf(named) : index;
            var info = new SignatureInformation(s.label());
            var doc = Docs.markup(s.fn());
            if (doc != null) info.setDocumentation(doc);
            info.setParameters(params);
            info.setActiveParameter(param >= 0 && param < params.size() ? param : null);
            if (active < 0 && info.getActiveParameter() != null) active = sigs.size();
            sigs.add(info);
        }
        var help = new SignatureHelp(sigs, Math.max(active, 0), null);
        if (!sigs.isEmpty()) help.setActiveParameter(sigs.get(Math.max(active, 0)).getActiveParameter());
        return help;
    }

    // ---- Tokens ----

    private static List<Token> tokens(String text) {
        var lexer = new ThreeboltLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        var out = new ArrayList<Token>();
        for (var t : lexer.getAllTokens()) if (t.getChannel() == Token.DEFAULT_CHANNEL) out.add(t);
        return out;
    }

    /** Whether the cursor is inside a comment or a string (not an f-string's `{...}`). */
    private static boolean inCommentOrString(Cursor at) {
        if (!at.tokens.isEmpty()) {
            switch (at.tokens.getLast().getType()) {
                case ThreeboltLexer.UNTERMINATED_COMMENT, ThreeboltLexer.INVALID_STRING, ThreeboltLexer.INVALID_CHAR_LITERAL,
                     ThreeboltLexer.FSTRING_START, ThreeboltLexer.FSTRING_TEXT -> {
                    return true;
                }
                default -> {}
            }
        }
        // A line comment: `//` after the last token on the cursor's line.
        int lineStart = at.before.lastIndexOf('\n') + 1;
        int from = lineStart;
        if (!at.tokens.isEmpty()) {
            var last = at.tokens.getLast();
            int end = at.before.offsetByCodePoints(0, last.getStopIndex() + 1);
            from = Math.max(from, end);
        }
        return at.before.indexOf("//", from) >= 0;
    }

    private static String wordBefore(String before) {
        int i = before.length();
        while (i > 0 && isIdentChar(before.charAt(i - 1))) i--;
        return before.substring(i);
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isIdentifier(String s) {
        var t = tokens(s);
        return t.size() == 1 && t.getFirst().getType() == ThreeboltLexer.IDENTIFIER && t.getFirst().getText().equals(s);
    }

    private static boolean matches(String name, String prefix) {
        return name.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /** The keywords, from the lexer's vocabulary. */
    private static List<String> keywords() {
        var out = new ArrayList<String>();
        var v = ThreeboltLexer.VOCABULARY;
        for (int i = 0; i <= v.getMaxTokenType(); i++) {
            var lit = v.getLiteralName(i);
            if (lit != null && lit.matches("'[a-z_]+'")) out.add(lit.substring(1, lit.length() - 1));
        }
        return List.copyOf(out);
    }

    // ---- Items ----

    private static CompletionItem item(String name, CompletionItemKind kind, String detail, int rank) {
        var i = new CompletionItem(name);
        i.setKind(kind);
        if (detail != null) i.setDetail(detail);
        i.setSortText(rank + name);
        return i;
    }

    private static CompletionItemKind kind(Completion.Kind k) {
        return switch (k) {
            case VARIABLE, STATIC -> CompletionItemKind.Variable;
            case FIELD -> CompletionItemKind.Field;
            case PROPERTY -> CompletionItemKind.Property;
            case METHOD -> CompletionItemKind.Method;
            case FUNCTION -> CompletionItemKind.Function;
            case STRUCT -> CompletionItemKind.Struct;
            case CLASS, TYPE_ALIAS -> CompletionItemKind.Class;
            case TRAIT -> CompletionItemKind.Interface;
            case ENUM -> CompletionItemKind.Enum;
            case ENUM_MEMBER -> CompletionItemKind.EnumMember;
            case TYPE_PARAMETER -> CompletionItemKind.TypeParameter;
            case PRIMITIVE -> CompletionItemKind.Keyword;
            case MODULE -> CompletionItemKind.Module;
            case CONSTANT -> CompletionItemKind.Constant;
        };
    }

    private static CompletionItemKind kind(Symbol s) {
        return switch (s) {
            case Symbol.ModuleRef r -> CompletionItemKind.Module;
            case Symbol.Decl d -> switch (d.item()) {
                case Item.Fn f -> CompletionItemKind.Function;
                case Item.Struct x -> CompletionItemKind.Struct;
                case Item.Class x -> CompletionItemKind.Class;
                case Item.Trait x -> CompletionItemKind.Interface;
                case Item.Enum x -> CompletionItemKind.Enum;
                case Item.TypeAlias x -> CompletionItemKind.Class;
                case Item.Const x -> CompletionItemKind.Constant;
                case Item.Static x -> CompletionItemKind.Variable;
                case Item.Use x -> CompletionItemKind.Module;
                case Item.Impl x -> CompletionItemKind.Class;
                case Item.Extern x -> CompletionItemKind.Function;
            };
        };
    }
}
