package dev.threebolt.project;

import com.electronwill.nightconfig.toml.TomlParser;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Source;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * `bolt new` and `bolt add`: they write manifests by editing the text, so a user's layout and
 * comments survive. Each returns the lines to print; a problem is a {@link Failure}.
 */
public final class Scaffold {
    private Scaffold() {}

    /** A reason the command did nothing. */
    public static final class Failure extends Exception {
        public Failure(String message) {
            super(message);
        }
    }

    public enum Kind { BIN, LIB, WORKSPACE }

    private static final Pattern MEMBERS = Pattern.compile("members\\s*=\\s*\\[([^\\]]*)\\]");

    /**
     * Creates {@code dir/name}: a package with a hello-world bin, a library package, or a
     * workspace. Run in a workspace root ({@code dir} holds `workspace.toml`), adds the package to
     * its members.
     */
    public static List<String> create(Path dir, String name, Kind kind) throws Failure, IOException {
        if (!Manifest.validName(name)) {
            throw new Failure(name.equals("std") ? "'std' is reserved and cannot be a package name"
                    : "'" + name + "' is not a valid package name: use an identifier (snake_case, no hyphens)");
        }
        var target = dir.resolve(name);
        if (Files.exists(target)) throw new Failure("'" + name + "' already exists");
        var ws = dir.resolve(Manifest.WORKSPACE_FILE);
        boolean inWorkspace = kind != Kind.WORKSPACE && Files.isRegularFile(ws);
        String wsText = inWorkspace ? addMember(Files.readString(ws), name) : null;
        var out = new ArrayList<String>();
        Files.createDirectories(target);
        switch (kind) {
            case WORKSPACE -> {
                Files.writeString(target.resolve(Manifest.WORKSPACE_FILE), "[workspace]\nmembers = []\n");
                out.add("created workspace '" + name + "' in " + name + "/");
            }
            case LIB -> {
                Files.writeString(target.resolve(Manifest.FILE), "[package]\nname = \"" + name + "\"\n\n[dependencies]\n");
                out.add("created library package '" + name + "' in " + name + "/");
            }
            case BIN -> {
                Files.writeString(target.resolve(Manifest.FILE), """
                        [package]
                        name = "%s"

                        [[bin]]
                        name = "%s"
                        entry = "main.3b"

                        [dependencies]
                        """.formatted(name, name));
                Files.writeString(target.resolve("main.3b"), "fn main() {\n    println(\"Hello, world!\");\n}\n");
                out.add("created package '" + name + "' in " + name + "/");
            }
        }
        if (wsText != null) {
            Files.writeString(ws, wsText);
            out.add("added '" + name + "' to the members in " + Manifest.WORKSPACE_FILE);
        }
        return out;
    }

    /** {@code text} (a `workspace.toml`) with {@code member} appended to `members`. */
    static String addMember(String text, String member) throws Failure {
        var members = members(text);
        if (members.contains(member)) return text;
        Matcher m = MEMBERS.matcher(text);
        if (!m.find()) throw new Failure(Manifest.WORKSPACE_FILE + " has no `members = [...]` to add '" + member + "' to");
        var items = new ArrayList<String>();
        for (var s : members) items.add(quote(s));
        items.add(quote(member));
        return text.substring(0, m.start()) + "members = [" + String.join(", ", items) + "]" + text.substring(m.end());
    }

    private static List<String> members(String text) throws Failure {
        try {
            var config = new TomlParser().parse(new StringReader(text));
            var list = config.get("workspace.members");
            var out = new ArrayList<String>();
            if (list instanceof List<?> l) for (var o : l) if (o instanceof String s) out.add(s);
            return out;
        } catch (RuntimeException e) {
            throw new Failure(Manifest.WORKSPACE_FILE + " is not valid TOML: " + e.getMessage());
        }
    }

    /**
     * Adds the package at {@code path} (relative to {@code cwd}) as a path dependency of the
     * package in {@code projectDir}, keyed by its package name.
     */
    public static List<String> add(Path projectDir, Path cwd, String path) throws Failure, IOException {
        var manifestFile = projectDir.resolve(Manifest.FILE);
        if (!Files.isRegularFile(manifestFile)) {
            throw new Failure(Files.isRegularFile(projectDir.resolve(Manifest.WORKSPACE_FILE))
                    ? "`bolt add` adds to a package: run it in a member's directory" : "there is no " + Manifest.FILE + " here");
        }
        var dep = cwd.resolve(path).toAbsolutePath().normalize();
        var depFile = dep.resolve(Manifest.FILE);
        if (!Files.isRegularFile(depFile)) throw new Failure("there is no " + Manifest.FILE + " in '" + path + "'");
        if (dep.equals(projectDir.toAbsolutePath().normalize())) throw new Failure("a package cannot depend on itself");
        var diagnostics = new Diagnostics();
        var depManifest = Manifest.parse(new Source(path + "/" + Manifest.FILE, Files.readString(depFile)), diagnostics);
        if (diagnostics.hasErrors()) throw new Failure("'" + path + "/" + Manifest.FILE + "' has errors; run `bolt check` there");
        String text = Files.readString(manifestFile);
        var manifest = Manifest.parse(new Source(Manifest.FILE, text), new Diagnostics());
        String key = depManifest.name();
        if (manifest.dependencies().stream().anyMatch(d -> d.key().equals(key))) {
            throw new Failure("'" + key + "' is already a dependency");
        }
        String rel = projectDir.toAbsolutePath().normalize().relativize(dep).toString().replace('\\', '/');
        Files.writeString(manifestFile, addDependency(text, key + " = { path = " + quote(rel) + " }"));
        return List.of("added dependency '" + key + "' (" + rel + ")");
    }

    /** {@code text} with {@code line} at the end of its `[dependencies]` table, which is added if missing. */
    static String addDependency(String text, String line) {
        var lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
        int header = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).strip().equals("[dependencies]")) header = i;
        if (header < 0) {
            if (!lines.isEmpty() && !lines.getLast().isBlank()) lines.add("");
            lines.add("[dependencies]");
            lines.add(line);
        } else {
            int end = header + 1;
            while (end < lines.size() && !lines.get(end).strip().startsWith("[")) end++;
            while (end > header + 1 && lines.get(end - 1).isBlank()) end--;
            lines.add(end, line);
        }
        return String.join("\n", lines) + "\n";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
