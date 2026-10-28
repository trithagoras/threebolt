package dev.threebolt.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives {@link Server} in-process over a small two-module program. */
class ServerTest {
    private static final String MAIN = """
            use shapes::Point;

            fn main() {
                let p = Point::origin();
                let mut total = 0;
                total += p.x;
                println(f"{total} {shapes::norm(p)}");
            }
            """;

    private static final String SHAPES = """
            pub struct Point {
                pub let x: i32;
                pub let y: i32;
                pub fn origin() -> Point => { x: 0, y: 0 };
            }

            pub fn norm(p: Point) -> i32 => p.x * p.x + p.y * p.y;
            """;

    @TempDir Path dir;
    private Server server;
    private final Map<String, List<Diagnostic>> diagnostics = new ConcurrentHashMap<>();
    private final List<String> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
    private Path main;
    private Path shapes;

    @BeforeEach
    void start() throws Exception {
        dir = dir.toRealPath();
        main = Files.writeString(dir.resolve("main.3b"), MAIN);
        shapes = Files.writeString(dir.resolve("shapes.3b"), SHAPES);
        server = new Server();
        server.connect(new Client());
        var init = new InitializeParams();
        init.setWorkspaceFolders(List.of(new WorkspaceFolder(dir.toUri().toString(), "test")));
        server.initialize(init).get();
        open(main, MAIN);
    }

    @Test
    void publishesDiagnosticsAsYouType() throws Exception {
        assertEquals(List.of(), diagnostics.get(uri(main)));
        assertEquals(List.of(), diagnostics.get(uri(shapes)));
        change(main, MAIN.replace("total += p.x;", "total += p.z;"));
        var ds = diagnostics.get(uri(main));
        assertEquals(1, ds.size(), ds.toString());
        assertEquals("E0202", ds.getFirst().getCode().getLeft(), ds.toString());
        assertEquals(new Position(5, 15), ds.getFirst().getRange().getStart());
        change(main, MAIN);
        assertEquals(List.of(), diagnostics.get(uri(main)));
    }

    @Test
    void errorsInAnImportedModuleGoToThatFile() throws Exception {
        open(shapes, SHAPES.replace("p.y * p.y", "p.y * q"));
        assertEquals(1, diagnostics.get(uri(shapes)).size());
        assertEquals(List.of(), diagnostics.get(uri(main)));
    }

    @Test
    void hoverShowsTypesAndSignatures() throws Exception {
        assertEquals("let mut total: i32", hover(main, "total +="));
        assertEquals("let Point.x: i32", hover(main, "x;"));
        assertEquals("fn Point::origin() -> Point", hover(main, "origin"));
        assertEquals("fn norm(p: Point) -> i32", hover(main, "norm"));
        assertEquals("struct Point", hover(main, "Point::"));
        assertEquals("mod shapes", hover(main, "shapes::norm"));
        assertEquals("fn println<T: Display>(x: T)\n\n---\n\nWrites `x.to_str()` and a newline to stdout.", hover(main, "println"));
    }

    @Test
    void goesToDefinitionAcrossModulesAndIntoStd() throws Exception {
        var loc = definition(main, "norm");
        assertEquals(uri(shapes), loc.getUri());
        assertEquals(new Position(6, 7), loc.getRange().getStart());
        loc = definition(main, "x;");
        assertEquals(new Position(1, 12), loc.getRange().getStart());
        loc = definition(main, "total +=");
        assertEquals(uri(main), loc.getUri());
        assertEquals(new Position(4, 12), loc.getRange().getStart());
        loc = definition(main, "println");
        assertTrue(loc.getUri().endsWith("/std/io.3b"), loc.getUri());
        assertTrue(Files.readString(Path.of(java.net.URI.create(loc.getUri()))).contains("pub fn println"));
    }

    @Test
    void stillAnswersWhileTheFileHasSyntaxErrors() throws Exception {
        change(main, MAIN.replace("let mut total = 0;", "let mut total = ;"));
        assertEquals("E0001", diagnostics.get(uri(main)).getFirst().getCode().getLeft());
        assertEquals("fn norm(p: Point) -> i32", hover(main, "norm"));
    }

    @Test
    void findsReferencesAndHighlights() throws Exception {
        open(shapes, SHAPES);
        var refs = server.references(new ReferenceParams(doc(shapes), at(SHAPES, "x: i32"), new ReferenceContext(true))).get();
        // The declaration, `x: 0`, two in `norm`, one in main.
        assertEquals(5, refs.size(), refs.toString());
        refs = server.references(new ReferenceParams(doc(shapes), at(SHAPES, "x: i32"), new ReferenceContext(false))).get();
        assertEquals(4, refs.size(), refs.toString());
        var highlights = server.documentHighlight(new DocumentHighlightParams(doc(main), at(MAIN, "total"))).get();
        assertEquals(3, highlights.size(), highlights.toString());
    }

    @Test
    void renamesAcrossFiles() throws Exception {
        var edit = server.rename(new RenameParams(doc(main), at(MAIN, "norm"), "length")).get();
        var changes = edit.getChanges();
        assertEquals(1, changes.get(uri(main)).size());
        assertEquals(1, changes.get(uri(shapes)).size());
        assertEquals("length", changes.get(uri(shapes)).getFirst().getNewText());
        var x = server.rename(new RenameParams(doc(main), at(MAIN, "x;"), "px")).get().getChanges();
        // `p.x` in main; the declaration, `x: 0` and two in `norm` in shapes.
        assertEquals(5, x.values().stream().mapToInt(List::size).sum());
    }

    @Test
    void renameEditsUsePathsButKeepsAliases() throws Exception {
        var sub = Files.createDirectory(dir.resolve("alias"));
        var lib = Files.writeString(sub.resolve("lib.3b"), "pub fn area() -> i32 => 1;\n");
        var text = "use lib::area;\nuse lib::{area as size};\nfn main() { println(area() + size()); }\n";
        var entry = Files.writeString(sub.resolve("main.3b"), text);
        open(entry, text);
        assertEquals(List.of(), diagnostics.get(uri(entry)));
        assertEquals("fn area() -> i32", hover(entry, "size()"));
        var changes = server.rename(new RenameParams(doc(entry), at(text, "area()"), "surface")).get().getChanges();
        assertEquals(1, changes.get(uri(lib)).size());
        // `use lib::area`, `{area as size}` and `area()`; not `size()`.
        var edits = changes.get(uri(entry)).stream().map(e -> e.getRange().getStart()).toList();
        assertEquals(List.of(new Position(0, 9), new Position(1, 10), new Position(2, 20)), edits);
    }

    @Test
    void refusesBadRenames() {
        assertRenameFails(main, MAIN, "println", "say");
        assertRenameFails(main, MAIN, "norm", "fn");
        assertRenameFails(main, MAIN, "norm", "two words");
    }

    @Test
    void refusesToRenameAShorthandField() throws Exception {
        var text = "struct P { let a: i32; }\nfn main() { let a = 1; let p: P = { a }; println(p.a); }\n";
        var file = Files.writeString(dir.resolve("short.3b"), text);
        open(file, text);
        assertEquals(List.of(), diagnostics.get(uri(file)));
        assertRenameFails(file, text, "a = 1", "b");
    }

    @Test
    void outlinesItemsAndMembers() throws Exception {
        open(shapes, SHAPES);
        var symbols = server.documentSymbol(new DocumentSymbolParams(doc(shapes))).get();
        var names = new ArrayList<String>();
        for (var e : symbols) {
            DocumentSymbol s = e.getRight();
            names.add(s.getName());
            if (s.getChildren() != null) s.getChildren().forEach(c -> names.add(s.getName() + "." + c.getName()));
        }
        assertEquals(List.of("Point", "Point.x", "Point.y", "Point.origin", "norm"), names);
    }

    @Test
    void hoverShowsDocComments() throws Exception {
        var text = """
                /// A shape.
                /// Has an **area**.
                trait Shape {
                    /// The area, in square units.
                    fn area(self) -> f64;
                }

                // Not a doc comment.
                /// A square.
                @c
                struct Square : Shape {
                    /// Length of a side.
                    pub let side: f64;
                    fn area(self) -> f64 => self.side * self.side;
                }

                enum Dir {
                    /// Up the screen.
                    Up,
                    Down,
                }

                //// Four slashes: an ordinary comment.
                fn plain() {}

                fn main() {
                    let s: Shape = Square { side: 2.0 };
                    println(s.area());
                    println(Square { side: 1.0 }.side);
                    println(Dir::Up as i32 + Dir::Down as i32);
                    plain();
                    println("ab".upper() + f"{5.abs()}");
                }
                """;
        var file = Files.writeString(dir.resolve("docs.3b"), text);
        open(file, text);
        assertEquals("trait Shape\n\n---\n\nA shape.\nHas an **area**.", hover(file, "Shape = "));
        assertEquals("struct Square : Shape\n\n---\n\nA square.", hover(file, "Square { side: 2"));
        assertEquals("let Square.side: f64\n\n---\n\nLength of a side.", hover(file, "side);"));
        // Square::area has no doc of its own: it shows the trait member's.
        assertEquals("fn Square::area(self) -> f64\n\n---\n\nThe area, in square units.", hover(file, "area(self) -> f64 =>"));
        assertEquals("fn Shape::area(self) -> f64\n\n---\n\nThe area, in square units.", hover(file, "area());"));
        assertEquals("Dir::Up\n\n---\n\nUp the screen.", hover(file, "Up as"));
        assertEquals("Dir::Down", hover(file, "Down as"));
        assertEquals("fn plain()", hover(file, "plain();"));
        // Primitive members are documented in std.
        assertEquals("fn str::upper(self) -> str\n\n---\n\nThe string in upper case.", hover(file, "upper"));
        assertEquals("fn i32::abs(self) -> i32\n\n---\n\nThe absolute value; `MIN.abs()` overflows and panics (§13.2).",
                hover(file, "abs()"));
    }

    @Test
    void checksAFileThatNoMainUsesOnItsOwn() throws Exception {
        var text = "fn helper() -> i32 => 1 + true;\n";
        var file = Files.writeString(dir.resolve("loose.3b"), text);
        open(file, text);
        var errors = diagnostics.get(uri(file)).stream().filter(d -> d.getSeverity() == DiagnosticSeverity.Error).toList();
        assertEquals(1, errors.size());
        assertNull(hover(file, "1 +"));
    }

    @AfterEach
    void noServerErrors() {
        assertEquals(List.of(), logged);
    }

    // ---- Helpers ----

    private void assertRenameFails(Path file, String text, String needle, String newName) {
        var e = assertThrows(ExecutionException.class,
                () -> server.rename(new RenameParams(doc(file), at(text, needle), newName)).get());
        assertNotNull(e.getCause().getMessage());
    }

    private String hover(Path file, String needle) throws Exception {
        var text = Files.readString(file);
        var h = server.hover(new HoverParams(doc(file), at(text, needle))).get();
        if (h == null) return null;
        return h.getContents().getRight().getValue().replace("```threebolt\n", "").replace("\n```", "");
    }

    private Location definition(Path file, String needle) throws Exception {
        var text = Files.readString(file);
        var locs = server.definition(new DefinitionParams(doc(file), at(text, needle))).get().getLeft();
        assertEquals(1, locs.size(), "definition of " + needle);
        return locs.getFirst();
    }

    private void open(Path file, String text) throws Exception {
        server.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri(file), "threebolt", 1, text)));
        server.workspace().awaitIdle();
    }

    private void change(Path file, String text) throws Exception {
        server.didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(uri(file), 2),
                List.of(new TextDocumentContentChangeEvent(text))));
        server.workspace().awaitIdle();
    }

    /** The position of the first occurrence of {@code needle}. */
    private static Position at(String text, String needle) {
        int i = text.indexOf(needle);
        assertTrue(i >= 0, needle);
        var before = text.substring(0, i);
        int line = (int) before.chars().filter(c -> c == '\n').count();
        return new Position(line, i - before.lastIndexOf('\n') - 1);
    }

    private static TextDocumentIdentifier doc(Path file) {
        return new TextDocumentIdentifier(uri(file));
    }

    private static String uri(Path file) {
        return file.toUri().toString();
    }

    private final class Client implements LanguageClient {
        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            diagnostics.put(params.getUri(), params.getDiagnostics());
        }

        @Override
        public void logMessage(MessageParams message) {
            logged.add(message.getMessage());
        }

        @Override
        public void telemetryEvent(Object object) {}

        @Override
        public void showMessage(MessageParams messageParams) {}

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
