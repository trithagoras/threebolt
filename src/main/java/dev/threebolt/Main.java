package dev.threebolt;

import dev.threebolt.ast.AstDump;
import dev.threebolt.diag.Code;
import dev.threebolt.diag.Diagnostic;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Source;
import dev.threebolt.lsp.LspMain;
import dev.threebolt.parse.AstParser;
import dev.threebolt.parse.AstPrinter;
import dev.threebolt.parse.LexErrors;
import dev.threebolt.parse.Parsing;
import dev.threebolt.parse.TokenDump;
import dev.threebolt.parse.TreeDump;
import dev.threebolt.parser.ThreeboltLexer;
import dev.threebolt.project.Project;
import dev.threebolt.project.Scaffold;
import dev.threebolt.resolve.ModuleLoader;
import dev.threebolt.types.Checker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.antlr.v4.runtime.CharStreams;

/**
 * The `bolt` command line. `run` and `check` use the package or workspace in the current
 * directory (or `--project <dir>`), never one above it; without either, a `.3b` file runs as a
 * script rooted at its own directory.
 */
public final class Main {
    private Main() {}

    private static final String USAGE = """
            usage: bolt [--project <dir>] <command> ...
              new <name> [--lib | --workspace]   create a package (with a hello-world bin, or none), or a workspace
              add <path>                         add the package at <path> as a dependency, keyed by its name
              run [bin | file.3b] [-- args...]   check and run a bin of the project, or a file
              check [bin | file.3b]              check a bin or file; without one, every bin (a library: every module)
              debug tokens|parse|ast|print <file.3b>
                                                 dump the tokens, parse tree or AST, or print the AST as source
              lsp                                serve the Language Server Protocol on stdin/stdout (for editors)""";

    /** A command-line mistake: printed with the usage, exit 2. */
    private static final class UsageError extends Exception {
        UsageError(String message) {
            super(message);
        }
    }

    /** A problem that stops the command: printed, exit 1. */
    private static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    public static void main(String[] argv) throws Exception {
        int status;
        try {
            status = run(new ArrayList<>(List.of(argv)));
        } catch (UsageError e) {
            if (!e.getMessage().isEmpty()) System.err.println("error: " + e.getMessage());
            System.err.println(USAGE);
            status = 2;
        } catch (Failure | Scaffold.Failure e) {
            System.err.println("error: " + e.getMessage());
            status = 1;
        }
        System.exit(status);
    }

    private static int run(List<String> args) throws Exception {
        // Program arguments follow `--`; options come before it.
        int dashes = args.indexOf("--");
        var programArgs = dashes < 0 ? List.<String>of() : List.copyOf(args.subList(dashes + 1, args.size()));
        var words = new ArrayList<>(dashes < 0 ? args : args.subList(0, dashes));
        Path cwd = Path.of("").toAbsolutePath();
        Path projectDir = null;
        int p = words.indexOf("--project");
        if (p >= 0) {
            if (p + 1 >= words.size()) throw new UsageError("--project needs a directory");
            projectDir = cwd.resolve(words.get(p + 1)).normalize();
            words.subList(p, p + 2).clear();
        }
        if (words.isEmpty()) throw new UsageError("");
        String command = words.removeFirst();
        if (dashes >= 0 && !command.equals("run")) throw new UsageError("only `run` takes program arguments after `--`");
        return switch (command) {
            case "lsp" -> {
                none(words);
                LspMain.run();
                yield 0;
            }
            case "new" -> scaffold(cwd, words);
            case "add" -> {
                if (words.size() != 1) throw new UsageError("`add` takes the path of a package");
                print(Scaffold.add(projectDir != null ? projectDir : cwd, cwd, words.getFirst()));
                yield 0;
            }
            case "run" -> {
                if (words.size() > 1) throw new UsageError("program arguments go after `--`: bolt run " + words.getFirst() + " -- " + String.join(" ", words.subList(1, words.size())));
                yield runOrCheck(cwd, projectDir, words.isEmpty() ? null : words.getFirst(), programArgs, true);
            }
            case "check" -> {
                if (words.size() > 1) throw new UsageError("`check` takes at most one bin or file");
                yield runOrCheck(cwd, projectDir, words.isEmpty() ? null : words.getFirst(), List.of(), false);
            }
            case "debug" -> {
                if (words.size() != 2) throw new UsageError("`debug` takes tokens, parse, ast or print, and a file");
                Path file = Path.of(words.get(1));
                yield switch (words.getFirst()) {
                    case "tokens" -> dumpTokens(file);
                    case "parse" -> dumpTree(file);
                    case "ast" -> dumpAst(file, false);
                    case "print" -> dumpAst(file, true);
                    default -> throw new UsageError("unknown debug command '" + words.getFirst() + "'");
                };
            }
            default -> throw new UsageError("unknown command '" + command + "'");
        };
    }

    private static void none(List<String> words) throws UsageError {
        if (!words.isEmpty()) throw new UsageError("unexpected '" + words.getFirst() + "'");
    }

    private static int scaffold(Path cwd, List<String> words) throws Exception {
        var kind = Scaffold.Kind.BIN;
        if (words.remove("--lib")) kind = Scaffold.Kind.LIB;
        if (words.remove("--workspace")) {
            if (kind != Scaffold.Kind.BIN) throw new UsageError("--lib and --workspace exclude each other");
            kind = Scaffold.Kind.WORKSPACE;
        }
        if (words.size() != 1 || words.getFirst().startsWith("-")) throw new UsageError("`new` takes a name");
        print(Scaffold.create(cwd, words.getFirst(), kind));
        return 0;
    }

    private static void print(List<String> lines) {
        lines.forEach(System.out::println);
    }

    /** `bolt run` / `bolt check`: picks the program(s) for {@code target} (a bin, a `.3b` file, or null). */
    private static int runOrCheck(Path cwd, Path projectDir, String target, List<String> args, boolean run) throws Failure, IOException {
        var manifestDiagnostics = new Diagnostics();
        var project = Project.open(projectDir != null ? projectDir : cwd, cwd, manifestDiagnostics);
        if (manifestDiagnostics.hasErrors()) return report(manifestDiagnostics);
        if (project == null && projectDir != null) throw new Failure("there is no package.toml or workspace.toml in '" + cwd.relativize(projectDir) + "'");

        if (target != null && target.endsWith(".3b")) {
            Path file = cwd.resolve(target).normalize();
            if (!Files.isRegularFile(file)) throw new Failure("there is no file '" + target + "'");
            // A file outside every package of the project runs as a script.
            var pkg = project == null ? null : project.packageOf(file);
            return program(cwd, file, pkg == null ? null : pkg.root(), args, run, project == null);
        }
        if (project == null) {
            throw new Failure(target == null ? "there is no package.toml here: name a .3b file, or pass --project <dir>"
                    : "there is no package.toml here to find bin '" + target + "' in; pass --project <dir>");
        }
        var targets = project.targets();
        if (target != null) {
            var t = targets.stream().filter(x -> x.bin().name().equals(target)).findFirst()
                    .orElseThrow(() -> new Failure("there is no bin named '" + target + "'" + bins(targets)));
            return program(cwd, t.entry(), t.pkg().root(), args, run, false);
        }
        if (run) {
            if (targets.size() != 1) {
                throw new Failure(targets.isEmpty() ? "there is no [[bin]] to run" : "there are several bins; name one" + bins(targets));
            }
            var t = targets.getFirst();
            return program(cwd, t.entry(), t.pkg().root(), args, true, false);
        }
        return checkAll(project);
    }

    private static String bins(List<Project.Target> targets) {
        return targets.isEmpty() ? "" : " (bins: " + String.join(", ", targets.stream().map(t -> t.bin().name()).toList()) + ")";
    }

    /** Checks (and with {@code run}, runs) the program of {@code entry} in the package at {@code root} (null: none). */
    private static int program(Path cwd, Path entry, Path root, List<String> args, boolean run, boolean hint) throws Failure {
        if (!Files.isRegularFile(entry)) throw new Failure("there is no entry file '" + cwd.relativize(entry) + "'");
        var diagnostics = new Diagnostics();
        var program = Driver.check(entry, root, diagnostics);
        // `run` prints diagnostics only when there are errors; `check` prints warnings too.
        if (program == null || !run) {
            int status = report(diagnostics);
            if (program == null && hint) hint(cwd, diagnostics);
            return status;
        }
        return Driver.run(program, args, System.in, System.out, System.err);
    }

    /** `bolt check` of a whole project: each bin's program, and each package without bins as a library. */
    private static int checkAll(Project project) {
        var all = new LinkedHashMap<String, Diagnostic>();
        boolean errors = false;
        for (var pkg : project.packages()) {
            var entries = pkg.manifest().bins().stream().map(b -> pkg.root().resolve(b.entry()).normalize()).toList();
            var lists = new ArrayList<Diagnostics>();
            if (entries.isEmpty()) {
                var d = new Diagnostics();
                new Checker(new ModuleLoader(d).loadLibrary(pkg.root()), d).check();
                lists.add(d);
            }
            for (var entry : entries) {
                var d = new Diagnostics();
                if (Files.isRegularFile(entry)) Driver.check(entry, pkg.root(), d);
                else System.err.println("error: there is no entry file '" + project.dir().relativize(entry) + "'");
                errors |= !Files.isRegularFile(entry);
                lists.add(d);
            }
            for (var d : lists) {
                errors |= d.hasErrors();
                // Programs of one package share modules: report each diagnostic once.
                for (var x : d.sorted()) all.putIfAbsent(x.render(), x);
            }
        }
        all.keySet().forEach(System.err::print);
        return errors ? 1 : 0;
    }

    /** After a failed import in a script, names a manifest above the current directory (it never changes behaviour). */
    private static void hint(Path cwd, Diagnostics diagnostics) {
        if (diagnostics.sorted().stream().noneMatch(d -> d.code() == Code.MODULE_NOT_FOUND)) return;
        var above = Project.above(cwd);
        if (above == null) return;
        var rel = cwd.relativize(above).toString();
        System.err.println("hint: there is a project in '" + rel + "'; run from there or pass `--project " + rel + "`");
    }

    private static int dumpTokens(Path file) throws IOException {
        var tokens = new ThreeboltLexer(CharStreams.fromPath(file)).getAllTokens();
        System.out.print(TokenDump.render(tokens));
        var errors = LexErrors.find(tokens);
        for (var e : errors) {
            System.err.println(file + ":" + e.line() + ":" + e.column() + ": error: " + e.message());
        }
        return errors.isEmpty() ? 0 : 1;
    }

    private static int dumpTree(Path file) throws IOException {
        var result = Parsing.compilationUnit(Files.readString(file));
        System.out.println(TreeDump.render(result.tree()));
        for (var e : result.errors()) {
            System.err.println(file + ":" + e.line() + ":" + e.column() + ": error: " + e.message());
        }
        return result.ok() ? 0 : 1;
    }

    private static int dumpAst(Path file, boolean asSource) throws IOException {
        var diagnostics = new Diagnostics();
        var unit = AstParser.compilationUnit(new Source(file.toString(), Files.readString(file)), diagnostics);
        if (unit != null) System.out.print(asSource ? AstPrinter.print(unit) : AstDump.render(unit) + "\n");
        return report(diagnostics);
    }

    private static int report(Diagnostics diagnostics) {
        for (Diagnostic d : diagnostics.sorted()) System.err.print(d.render());
        return diagnostics.hasErrors() ? 1 : 0;
    }
}
