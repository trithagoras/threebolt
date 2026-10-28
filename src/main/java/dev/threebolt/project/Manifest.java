package dev.threebolt.project;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.toml.TomlParser;
import dev.threebolt.diag.Code;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Source;
import dev.threebolt.diag.Span;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A parsed `package.toml` (§2.5): the package's name, its dependencies and its bins. The loader
 * reads the first two; bins belong to the tooling. Errors are reported against {@code source}, at
 * the line of the offending key (the TOML parser gives no positions, so keys are found in the
 * text).
 */
public record Manifest(Source source, String name, List<Dependency> dependencies, List<Bin> bins) {
    public static final String FILE = "package.toml";
    public static final String WORKSPACE_FILE = "workspace.toml";

    /** {@code key = { path = "…" }}; {@code path} is relative to the manifest's directory. */
    public record Dependency(String key, String path, Span span) {}

    /** A `[[bin]]`: an executable named {@code name} whose entry file is {@code entry} (relative to the root). */
    public record Bin(String name, String entry, Span span) {}

    private static final Set<String> TABLES = Set.of("package", "dependencies", "bin");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** Whether {@code s} can be a package name or dependency key: an identifier other than `_` and `std`. */
    public static boolean validName(String s) {
        return IDENTIFIER.matcher(s).matches() && !s.equals("_") && !s.equals("std");
    }

    /** Parses {@code source}; never null. A manifest with errors has what could be read of it. */
    public static Manifest parse(Source source, Diagnostics diagnostics) {
        var config = toml(source, diagnostics);
        var reader = new Reader(source, diagnostics);
        String name = null;
        var deps = new ArrayList<Dependency>();
        var bins = new ArrayList<Bin>();
        if (config != null) {
            for (var key : config.valueMap().keySet()) {
                if (!TABLES.contains(key)) reader.error(Code.MANIFEST, null, key, "unknown table '" + key + "' in " + FILE);
            }
            if (config.get("package") instanceof UnmodifiableConfig pkg) {
                for (var key : pkg.valueMap().keySet()) {
                    if (!key.equals("name")) reader.error(Code.MANIFEST, "package", key, "unknown key '" + key + "' in [package]");
                }
                if (pkg.get("name") instanceof String s) {
                    name = s;
                    if (!validName(s)) reader.error(Code.PACKAGE_NAME, "package", "name", badName("package name", s));
                } else {
                    reader.error(Code.MANIFEST, "package", pkg.contains("name") ? "name" : null, "[package] needs a string `name`");
                }
            } else {
                reader.error(Code.MANIFEST, null, null, FILE + " needs a [package] table with a `name`");
            }
            var dependencies = config.get("dependencies");
            if (dependencies instanceof UnmodifiableConfig table) {
                for (var e : table.valueMap().entrySet()) dependency(reader, e, deps);
            } else if (dependencies != null) {
                reader.error(Code.MANIFEST, null, "dependencies", "`dependencies` must be a table");
            }
            var binList = config.get("bin");
            if (binList instanceof List<?> list) {
                int index = 0;
                for (var b : list) bin(reader, b, index++, bins);
            } else if (binList != null) {
                reader.error(Code.MANIFEST, null, "bin", "bins are written `[[bin]]`");
            }
        }
        if (name == null) name = "_";
        return new Manifest(source, name, List.copyOf(deps), List.copyOf(bins));
    }

    private static void dependency(Reader reader, Map.Entry<String, Object> e, List<Dependency> out) {
        String key = e.getKey();
        var span = reader.span("dependencies", key);
        if (!validName(key)) {
            reader.diagnostics.error(Code.PACKAGE_NAME, span, badName("dependency key", key));
            return;
        }
        if (!(e.getValue() instanceof UnmodifiableConfig dep)) {
            reader.diagnostics.error(Code.MANIFEST, span, "dependency '" + key + "' must be a table: " + key + " = { path = \"…\" }");
            return;
        }
        for (var k : dep.valueMap().keySet()) {
            if (!k.equals("path")) {
                String why = k.equals("git") || k.equals("version") ? "only path dependencies are supported" : "unknown key '" + k + "'";
                reader.diagnostics.error(Code.MANIFEST, span, "dependency '" + key + "': " + why);
                return;
            }
        }
        if (!(dep.get("path") instanceof String path)) {
            reader.diagnostics.error(Code.MANIFEST, span, "dependency '" + key + "' needs a string `path`");
            return;
        }
        out.add(new Dependency(key, path, span));
    }

    private static void bin(Reader reader, Object b, int index, List<Bin> out) {
        var span = reader.nth("bin", index);
        if (!(b instanceof UnmodifiableConfig bin)) {
            reader.diagnostics.error(Code.MANIFEST, span, "bins are written `[[bin]]`");
            return;
        }
        for (var k : bin.valueMap().keySet()) {
            if (!k.equals("name") && !k.equals("entry")) reader.diagnostics.error(Code.MANIFEST, span, "unknown key '" + k + "' in [[bin]]");
        }
        if (!(bin.get("name") instanceof String name) || !(bin.get("entry") instanceof String entry)) {
            reader.diagnostics.error(Code.MANIFEST, span, "[[bin]] needs a string `name` and `entry`");
            return;
        }
        if (!validName(name)) {
            reader.diagnostics.error(Code.PACKAGE_NAME, span, badName("bin name", name));
            return;
        }
        if (out.stream().anyMatch(o -> o.name().equals(name))) {
            reader.diagnostics.error(Code.MANIFEST, span, "there is already a bin named '" + name + "'");
            return;
        }
        out.add(new Bin(name, entry, span));
    }

    private static String badName(String what, String name) {
        return name.equals("std") ? "'std' is reserved and cannot be a " + what
                : "'" + name + "' is not a valid " + what + ": use an identifier (snake_case, no hyphens)";
    }

    /** A parsed `workspace.toml`: its member directories, relative to it. */
    public record Workspace(Source source, List<String> members, Span span) {
        public static Workspace parse(Source source, Diagnostics diagnostics) {
            var config = toml(source, diagnostics);
            var reader = new Reader(source, diagnostics);
            var members = new ArrayList<String>();
            if (config != null) {
                for (var key : config.valueMap().keySet()) {
                    if (!key.equals("workspace")) reader.error(Code.MANIFEST, null, key, "unknown table '" + key + "' in " + WORKSPACE_FILE);
                }
                if (config.get("workspace") instanceof UnmodifiableConfig ws && ws.get("members") instanceof List<?> list
                        && list.stream().allMatch(String.class::isInstance)) {
                    list.forEach(m -> members.add((String) m));
                } else {
                    reader.error(Code.MANIFEST, "workspace", "members", WORKSPACE_FILE + " needs [workspace] members = [\"…\"]");
                }
            }
            return new Workspace(source, List.copyOf(members), reader.span("workspace", "members"));
        }
    }

    private static UnmodifiableConfig toml(Source source, Diagnostics diagnostics) {
        try {
            return new TomlParser().parse(new StringReader(source.text()));
        } catch (ParsingException e) {
            diagnostics.error(Code.MANIFEST, new Span(source, 0, Math.min(1, source.length())), "invalid TOML: " + e.getMessage());
            return null;
        }
    }

    /** Finds keys in the manifest's text, to report at. */
    private record Reader(Source source, Diagnostics diagnostics) {
        void error(Code code, String table, String key, String message) {
            diagnostics.error(code, span(table, key), message);
        }

        /**
         * The span of {@code key} in {@code [table]} (null: the top level, where a key may also be a
         * table header); the table header if the key is not found, else the start of the file.
         */
        Span span(String table, String key) {
            String current = null;
            Span header = null;
            int offset = 0;
            for (String line : source.text().split("\n", -1)) {
                String t = line.strip();
                var h = headerName(t);
                if (h != null) {
                    current = h;
                    if (h.equals(table) && header == null) header = at(offset, line, h);
                    if (table == null && h.equals(key)) return at(offset, line, h);
                } else if (key != null && java.util.Objects.equals(current, table) && keyOf(t).equals(key)) {
                    return at(offset, line, key);
                }
                offset += line.codePointCount(0, line.length()) + 1;
            }
            return header != null ? header : new Span(source, 0, Math.min(1, source.length()));
        }

        /** The {@code n}th {@code [[table]]} header. */
        Span nth(String table, int n) {
            int offset = 0;
            for (String line : source.text().split("\n", -1)) {
                if (line.strip().startsWith("[[") && table.equals(headerName(line.strip())) && n-- == 0) return at(offset, line, table);
                offset += line.codePointCount(0, line.length()) + 1;
            }
            return span(table, null);
        }

        private Span at(int lineStart, String line, String word) {
            int i = line.indexOf(word);
            if (i < 0) i = 0;
            int start = lineStart + line.codePointCount(0, i);
            return new Span(source, start, start + word.codePointCount(0, word.length()));
        }

        private static String headerName(String t) {
            if (!t.startsWith("[")) return null;
            int end = t.indexOf(']');
            return end < 0 ? null : t.substring(t.startsWith("[[") ? 2 : 1, end).strip();
        }

        private static String keyOf(String t) {
            int eq = t.indexOf('=');
            if (eq < 0) return "";
            var k = t.substring(0, eq).strip();
            return k.length() >= 2 && k.startsWith("\"") && k.endsWith("\"") ? k.substring(1, k.length() - 1) : k;
        }
    }
}
