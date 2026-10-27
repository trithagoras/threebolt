package dev.threebolt.parse;

import dev.threebolt.parser.ThreeboltParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** The spec's ```rs code blocks, and which entry rule each one parses with. */
final class SpecSnippets {
    private SpecSnippets() {}

    private static final Pattern CODE_BLOCK = Pattern.compile("(?s)```rs\\n(.*?)```");
    // Spec snippets elide bodies as `{ ... }`.
    private static final Pattern ELIDED_BODY = Pattern.compile("\\{ \\.\\.\\. }");

    /** Snippets are whole modules, member lists (§4.8), statement lists, or one expression per line (§7.8). */
    enum Kind { MODULE, MEMBERS, STMTS, EXPR_LINES }

    static List<String> all() throws IOException {
        String spec = Files.readString(Path.of("docs/spec/threebolt-1.0.md"));
        var snippets = new ArrayList<String>();
        CODE_BLOCK.matcher(spec).results().forEach(m -> snippets.add(ELIDED_BODY.matcher(m.group(1)).replaceAll("{}")));
        return snippets;
    }

    /** The first kind the snippet parses as, or null. */
    static Kind kind(String snippet) {
        if (Parsing.compilationUnit(snippet).ok()) return Kind.MODULE;
        if (Parsing.parse(snippet, ThreeboltParser::memberList).ok()) return Kind.MEMBERS;
        if (Parsing.parse(snippet, ThreeboltParser::stmtList).ok()) return Kind.STMTS;
        if (exprLines(snippet).stream().allMatch(line -> Parsing.parse(line, ThreeboltParser::exprOnly).ok())) {
            return Kind.EXPR_LINES;
        }
        return null;
    }

    /** The non-empty lines of a snippet with `//` comments removed. */
    static List<String> exprLines(String snippet) {
        return snippet.lines()
                .map(line -> line.replaceFirst("//.*", "").strip())
                .filter(line -> !line.isEmpty())
                .toList();
    }

    static String firstLine(String snippet) {
        return snippet.lines().findFirst().orElse("");
    }
}
