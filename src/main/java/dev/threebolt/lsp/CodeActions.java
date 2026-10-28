package dev.threebolt.lsp;

import dev.threebolt.ast.Item;
import dev.threebolt.ast.Visibility;
import dev.threebolt.diag.Code;
import dev.threebolt.diag.Source;
import dev.threebolt.ir.Local;
import dev.threebolt.resolve.Module;
import dev.threebolt.resolve.ModuleLoader;
import dev.threebolt.types.SymbolIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;

/**
 * Quick fixes for the diagnostics the editor sends back: silence or fix a warning (`_` prefix,
 * drop `mut`, remove an import, `==` → `=`), import an unresolved name, or make a binding
 * mutable.
 */
final class CodeActions {
    private final Workspace workspace;

    CodeActions(Workspace workspace) {
        this.workspace = workspace;
    }

    List<CodeAction> actions(Path file, CodeActionParams params) {
        var text = workspace.text(file);
        if (text == null || !workspace.checkable(file)) return List.of();
        var source = new Source(file.getFileName().toString(), text);
        var uri = file.toUri().toString();
        var out = new ArrayList<CodeAction>();
        for (var d : params.getContext().getDiagnostics()) {
            if (!"threebolt".equals(d.getSource()) || d.getCode() == null || !d.getCode().isLeft()) continue;
            var code = code(d.getCode().getLeft());
            if (code == null) continue;
            int start = Positions.offset(source, d.getRange().getStart());
            int end = Positions.offset(source, d.getRange().getEnd());
            String word = source.text().substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
            switch (code) {
                case UNUSED_VARIABLE -> out.add(fix("Rename to `_" + word + "`", d, uri, true,
                        edit(d.getRange().getStart(), d.getRange().getStart(), "_")));
                case UNUSED_MUT -> {
                    var mut = mutBefore(source, start);
                    if (mut != null) out.add(fix("Remove `mut`", d, uri, true, edit(mut.getStart(), mut.getEnd(), "")));
                }
                case UNUSED_IMPORT -> {
                    var r = importRemoval(source, start, end, word);
                    out.add(fix("Remove unused import", d, uri, true, edit(r.getStart(), r.getEnd(), "")));
                }
                case NO_EFFECT -> {
                    int eq = word.indexOf("==");
                    if (d.getMessage().isLeft() && d.getMessage().getLeft().contains("write `=`") && eq >= 0) {
                        var at = Positions.position(source, start + word.codePointCount(0, eq));
                        var after = new Position(at.getLine(), at.getCharacter() + 2);
                        out.add(fix("Change `==` to `=`", d, uri, true, edit(at, after, "=")));
                    }
                }
                case UNRESOLVED_NAME -> {
                    for (var path : importCandidates(file, word)) {
                        out.add(fix("Import `" + path + "`", d, uri, false, edit(importAt(source), importAt(source),
                                "use " + path + ";\n")));
                    }
                }
                case IMMUTABLE_PLACE, IMMUTABLE_RECEIVER -> {
                    var at = makeMutable(file, source, start);
                    if (at != null) out.add(fix("Declare it `let mut`", d, uri, true, edit(at, at, "mut ")));
                }
                default -> {}
            }
        }
        return out;
    }

    private static Code code(String id) {
        for (var c : Code.values()) if (c.id().equals(id)) return c;
        return null;
    }

    private static CodeAction fix(String title, Diagnostic d, String uri, boolean preferred, TextEdit edit) {
        var a = new CodeAction(title);
        a.setKind(CodeActionKind.QuickFix);
        a.setDiagnostics(List.of(d));
        a.setIsPreferred(preferred);
        a.setEdit(new WorkspaceEdit(Map.of(uri, List.of(edit))));
        return a;
    }

    private static TextEdit edit(Position from, Position to, String text) {
        return new TextEdit(new Range(from, to), text);
    }

    // ---- Warnings ----

    /** The `mut ` (with the whitespace after it) just before the name at {@code offset}, or null. */
    private static Range mutBefore(Source source, int offset) {
        var cps = source.text().codePoints().toArray();
        int i = offset;
        while (i > 0 && Character.isWhitespace(cps[i - 1])) i--;
        if (i < 3 || !new String(cps, i - 3, 3).equals("mut")) return null;
        if (i > 3 && (Character.isLetterOrDigit(cps[i - 4]) || cps[i - 4] == '_')) return null;
        return new Range(Positions.position(source, i - 3), Positions.position(source, offset));
    }

    /**
     * What to delete for an unused import at {@code [start, end)}: a whole `use` item with its line
     * break, or one name of a group with the comma beside it.
     */
    private static Range importRemoval(Source source, int start, int end, String text) {
        if (text.startsWith("use")) {
            int line = source.line(start);
            int lineEnd = source.line(end);
            boolean ownLines = source.column(start) == 1
                    && source.lineText(lineEnd).codePointCount(0, source.lineText(lineEnd).length()) + 1 == source.column(end);
            if (ownLines) return new Range(new Position(line - 1, 0), new Position(lineEnd, 0));
            return new Range(Positions.position(source, start), Positions.position(source, end));
        }
        var cps = source.text().codePoints().toArray();
        int after = end;
        while (after < cps.length && Character.isWhitespace(cps[after])) after++;
        if (after < cps.length && cps[after] == ',') {
            after++;
            while (after < cps.length && cps[after] == ' ') after++;
            return new Range(Positions.position(source, start), Positions.position(source, after));
        }
        int before = start;
        while (before > 0 && Character.isWhitespace(cps[before - 1])) before--;
        if (before > 0 && cps[before - 1] == ',') before--;
        return new Range(Positions.position(source, before), Positions.position(source, end));
    }

    // ---- Mutability ----

    /** Where `mut ` goes to make the local named at {@code offset} mutable, or null. */
    private Position makeMutable(Path file, Source source, int offset) {
        var a = workspace.forQuery(file);
        var m = a == null ? null : a.module(file);
        if (m == null || !m.source().text().equals(source.text())) return null;
        var ref = a.index.at(m.source(), offset);
        if (ref == null || !(ref.target() instanceof Local l) || l.name().equals("self")) return null;
        var def = SymbolIndex.definition(l);
        if (def == null || def.source() != m.source()) return null;
        return Positions.position(source, def.start());
    }

    // ---- Imports ----

    /**
     * The paths that `use` could bring {@code name} in from: public items of the std modules and
     * of the program's other modules on disk, and modules named {@code name}.
     */
    private List<String> importCandidates(Path file, String name) {
        var root = workspace.sourceRoot(file);
        var probe = new StringBuilder();
        for (var std : new TreeSet<>(ModuleLoader.STD_MODULES)) {
            if (!std.equals("prelude")) probe.append("use std::").append(std).append("::*;\n");
        }
        for (var path : projectModules(root, file)) probe.append("use ").append(path).append("::*;\n");
        var program = workspace.load(file, probe.toString());
        var out = new TreeSet<String>();
        for (Module m : program.all()) {
            if (m.ast() == null || file.equals(m.file()) || m.path().segments().contains("builtin")) continue;
            if (m.path().toString().equals("std::prelude")) continue;
            if (m.path().last().equals(name)) out.add(m.path().toString());
            for (var d : m.scope().items(name)) {
                if (d.item().visibility() == Visibility.PUB && !(d.item() instanceof Item.Use)) {
                    out.add(m.path() + "::" + name);
                }
            }
        }
        return List.copyOf(out);
    }

    /** The module paths of the `.3b` files under {@code root}, other than {@code file}. */
    private static List<String> projectModules(Path root, Path file) {
        var out = new ArrayList<String>();
        if (root == null || !Files.isDirectory(root)) return out;
        try (var walk = Files.walk(root, 8)) {
            for (var p : walk.filter(p -> p.toString().endsWith(".3b") && Files.isRegularFile(p)).sorted().toList()) {
                if (p.equals(file) || p.equals(root.resolve("main.3b"))) continue;
                var rel = root.relativize(p).toString();
                var path = rel.substring(0, rel.length() - 3).replace(java.io.File.separatorChar, '/').replace("/", "::");
                if (path.matches("[A-Za-z_][A-Za-z0-9_]*(::[A-Za-z_][A-Za-z0-9_]*)*")) out.add(path);
            }
        } catch (IOException e) {
            // Unreadable directories offer nothing.
        }
        return out;
    }

    /** Where a new `use` goes: after the last `use` item, else after the file's leading comments. */
    private static Position importAt(Source source) {
        int line = 0;
        int lastUse = -1;
        int lines = source.line(source.length());
        boolean header = true;
        for (int i = 1; i <= lines; i++) {
            var t = source.lineText(i).strip();
            if (t.startsWith("use ")) lastUse = i;
            if (header && (t.startsWith("//") && !t.startsWith("///"))) line = i;
            else if (!t.isEmpty()) header = false;
        }
        return new Position(lastUse >= 0 ? lastUse : line, 0);
    }
}
