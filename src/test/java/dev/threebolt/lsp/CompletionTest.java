package dev.threebolt.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Completion and signature help, with the cursor marked `‸` in each source. */
class CompletionTest {
    private static final String SHAPES = """
            pub struct Point {
                pub let x: i32;
                pub let y: i32;
                let secret: i32 = 0;
                pub static let ORIGIN: Point = { x: 0, y: 0 };
                pub fn new(x: i32, y: i32) -> Point => { x, y };
                /// Distance along x.
                pub fn dist(self, other: Point) -> i32 => (self.x - other.x).abs();
                pub get fn norm(self) -> i32 => self.x * self.x + self.y * self.y;
                fn hidden(self) -> i32 => self.secret;
            }

            pub trait Describe {
                /// A short description.
                fn describe(self) -> str;
                fn shout(self) -> str => self.describe() + "!";
            }

            impl Describe for Point { fn describe(self) -> str => "point"; }

            pub class Animal {
                pub let name: str;
                pub fn new(name: str) -> Self => { name };
                pub virtual fn speak(self) -> str => "...";
            }

            pub class Dog : Animal {
                pub fn new(name: str) -> Self => { ..Animal::new(name) };
                pub override fn speak(self) -> str => "woof";
                pub fn fetch(self) -> str => "ball";
                let tag: str = "";
            }

            pub enum Color { Red, Green, Blue }

            pub fn scale(p: Point, by: i32) -> Point => { x: p.x * by, y: p.y * by };
            pub fn scale(p: Point, by: i32, extra: i32) -> Point => { x: p.x * by + extra, y: p.y * by };
            pub fn label(p: Point, prefix: str = "p", suffix: str = "") -> str => prefix + suffix;
            fn private_helper() -> i32 => 1;
            """;

    @TempDir Path dir;
    private Server server;
    private final List<String> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
    private Path main;

    @BeforeEach
    void start() throws Exception {
        dir = dir.toRealPath();
        Files.writeString(dir.resolve("shapes.3b"), SHAPES);
        Files.createDirectory(dir.resolve("geo"));
        Files.writeString(dir.resolve("geo").resolve("units.3b"), "pub fn metres() -> i32 => 1;\n");
        main = dir.resolve("main.3b");
        server = new Server();
        server.connect(new Client());
        var init = new InitializeParams();
        init.setWorkspaceFolders(List.of(new WorkspaceFolder(dir.toUri().toString(), "test")));
        server.initialize(init).get();
    }

    @AfterEach
    void noServerErrors() {
        assertEquals(List.of(), logged);
    }

    // ---- Documentation ----

    @Test
    void itemsAndSignaturesCarryDocComments() throws Exception {
        var items = complete("""
                use shapes::Point;
                fn main() {
                    let p = Point::new(1, 2);
                    p.‸
                }
                """);
        assertEquals("Distance along x.", find(items, "dist").getDocumentation().getRight().getValue());
        // Point's `describe` (in an `impl`) has no doc of its own: the trait member's.
        assertEquals("A short description.", find(items, "describe").getDocumentation().getRight().getValue());
        assertNull(find(items, "x").getDocumentation());
        var help = signatureHelp("""
                use shapes::Point;
                fn main() {
                    let p = Point::new(1, 2);
                    p.dist(‸
                }
                """);
        assertEquals("Distance along x.", help.getSignatures().getFirst().getDocumentation().getRight().getValue());
    }

    // ---- After `.` ----

    @Test
    void membersAfterDot() throws Exception {
        var items = complete("""
                use shapes::Point;
                fn main() {
                    let p = Point::new(1, 2);
                    p.‸
                }
                """);
        var names = names(items);
        assertTrue(names.containsAll(List.of("x", "y", "dist", "norm", "describe", "shout")), names.toString());
        assertTrue(names.contains("secret"), "struct fields are always public (§2.3)");
        assertFalse(names.contains("hidden"), "private method");
        assertFalse(names.contains("new"), "static function");
        assertEquals(CompletionItemKind.Field, find(items, "x").getKind());
        assertEquals(CompletionItemKind.Property, find(items, "norm").getKind());
        assertEquals(CompletionItemKind.Method, find(items, "dist").getKind());
        assertEquals("fn Point::dist(self, other: Point) -> i32", find(items, "dist").getDetail());
        // Members declared on the type sort before trait members.
        assertTrue(find(items, "dist").getSortText().compareTo(find(items, "describe").getSortText()) < 0);
    }

    @Test
    void membersInASelectCase() throws Exception {
        var names = names(complete("""
                use std::sync::Channel;
                fn main() -> () | Err {
                    let ch = Channel<i32>::new(1);
                    select { let x = ch.re‸ => x, }?;
                }
                """));
        assertTrue(names.contains("recv"), names.toString());
    }

    @Test
    void operatorMethodsSortLast() throws Exception {
        var items = complete("""
                fn main() {
                    let n = 5;
                    n.‸
                }
                """);
        // `+` calls `add`; nobody writes `n.add(1)`.
        assertTrue(find(items, "wrapping_add").getSortText().compareTo(find(items, "add").getSortText()) < 0);
        assertTrue(find(items, "to_str").getSortText().compareTo(find(items, "eq").getSortText()) < 0);
    }

    @Test
    void privateMembersAreVisibleInsideTheType() throws Exception {
        var names = names(complete("""
                struct Box {
                    let secret: i32;
                    fn peek(self) -> i32 => self.‸;
                }
                fn main() {}
                """));
        assertTrue(names.containsAll(List.of("secret", "peek")), names.toString());
    }

    @Test
    void membersWithATypedPrefix() throws Exception {
        var names = names(complete("""
                use shapes::Point;
                fn main() {
                    let p = Point::new(1, 2);
                    let d = p.di‸
                }
                """));
        assertEquals(List.of("dist"), names);
    }

    @Test
    void inheritedMembers() throws Exception {
        var items = complete("""
                use shapes::Dog;
                fn main() {
                    let d = Dog::new("rex");
                    println(d.‸);
                }
                """);
        var names = names(items);
        assertTrue(names.containsAll(List.of("name", "speak", "fetch")), names.toString());
        assertFalse(names.contains("tag"), "private class field");
        assertTrue(find(items, "fetch").getSortText().compareTo(find(items, "name").getSortText()) < 0);
    }

    @Test
    void narrowedUnion() throws Exception {
        var names = names(complete("""
                use shapes::Point;
                fn pick(v: Point | str) {
                    if v is Point {
                        v.‸
                    }
                }
                fn main() {}
                """));
        assertTrue(names.containsAll(List.of("x", "dist")), names.toString());
    }

    @Test
    void unionNarrowedByIsNot() throws Exception {
        var names = names(complete("""
                use shapes::Point;
                fn pick(v: Point | str) {
                    if v is not str {
                        v.‸
                    }
                }
                fn main() {}
                """));
        assertTrue(names.containsAll(List.of("x", "dist")), names.toString());
        var types = names(complete("""
                use shapes::Point;
                fn pick(v: Point | str) -> bool => v is not Poi‸;
                fn main() {}
                """));
        assertTrue(types.contains("Point"), types.toString());
    }

    @Test
    void comprehensionBindingsBeforeTheirClause() throws Exception {
        // The element comes first in the source but is checked after the clauses that bind it.
        var names = names(complete("""
                use shapes::Point;
                fn pick(vs: [Point | str]) -> [i32] {
                    return [v.‸ for v in vs if v is Point];
                }
                fn main() {}
                """));
        assertTrue(names.containsAll(List.of("x", "dist")), names.toString());
        var locals = names(complete("""
                fn f(xs: [i32]) -> [i32] => [ite‸ for item in xs];
                fn main() {}
                """));
        assertTrue(locals.contains("item"), locals.toString());
    }

    @Test
    void genericReceiverIsInstantiated() throws Exception {
        var items = complete("""
                fn main() {
                    let xs = [1, 2, 3];
                    xs.‸
                }
                """);
        assertTrue(names(items).containsAll(List.of("push", "len")), names(items).toString());
        assertTrue(find(items, "push").getDetail().contains("i32"), find(items, "push").getDetail());
    }

    // ---- After `Type::` and `module::` ----

    @Test
    void enumMembers() throws Exception {
        var items = complete("""
                use shapes::Color;
                fn main() {
                    let c = Color::‸
                }
                """);
        assertEquals(List.of("Blue", "Green", "Red"), names(items));
        assertEquals(CompletionItemKind.EnumMember, find(items, "Red").getKind());
    }

    @Test
    void staticsAfterType() throws Exception {
        var names = names(complete("""
                use shapes::Point;
                fn main() {
                    let p = Point::‸
                }
                """));
        assertTrue(names.containsAll(List.of("ORIGIN", "new")), names.toString());
        assertFalse(names.contains("dist"), "instance method");
        assertFalse(names.contains("x"), "field");
    }

    @Test
    void primitiveConstants() throws Exception {
        var names = names(complete("""
                fn main() {
                    let m = i32::M‸
                }
                """));
        assertTrue(names.containsAll(List.of("MAX", "MIN")), names.toString());
        assertFalse(names.contains("NAN"));
    }

    @Test
    void moduleItemsArePublicOnly() throws Exception {
        var items = complete("""
                fn main() {
                    let p = shapes::‸
                }
                """);
        var names = names(items);
        assertTrue(names.containsAll(List.of("Point", "Color", "scale", "label")), names.toString());
        assertFalse(names.contains("private_helper"));
        assertEquals("fn scale(p: Point, by: i32) -> Point  (+1 overload)", find(items, "scale").getDetail());
    }

    @Test
    void submodules() throws Exception {
        var names = names(complete("""
                fn main() {
                    let n = geo::‸
                }
                """));
        assertEquals(List.of("units"), names);
        names = names(complete("""
                fn main() {
                    let n = std::ma‸
                }
                """));
        assertEquals(List.of("math"), names);
    }

    // ---- Bare names ----

    @Test
    void localsInScopeOnly() throws Exception {
        var items = complete("""
                fn helper(count: i32) -> i32 {
                    let total = count;
                    {
                        let inner = 1;
                    }
                    let t = to‸
                    let later = 2;
                    return t;
                }
                fn main() {}
                """);
        assertEquals(List.of("total"), names(items).stream().filter(n -> !n.equals("todo")).toList());
        var names = names(complete("""
                fn helper(count: i32) -> i32 {
                    let total = count;
                    {
                        let inner = 1;
                    }
                    return ‸
                    let later = 2;
                }
                fn main() {}
                """));
        assertTrue(names.containsAll(List.of("total", "count", "helper", "main", "println", "i32", "return")), names.toString());
        assertFalse(names.contains("inner"));
        assertFalse(names.contains("later"));
        assertTrue(names.indexOf("total") < names.indexOf("helper"), "locals first");
    }

    @Test
    void selfImportsAndTypeParams() throws Exception {
        var names = names(complete("""
                use shapes::{Point, scale};
                struct Wrap<T> {
                    let value: T;
                    fn get(self) -> T {
                        let w = ‸
                    }
                }
                fn main() {}
                """));
        assertTrue(names.containsAll(List.of("self", "Point", "scale", "T", "Wrap")), names.toString());
    }

    @Test
    void typePositionsOfferTypesOnly() throws Exception {
        var names = names(complete("""
                use shapes::{Point, scale};
                fn main() {
                    let local = 1;
                    let p: ‸
                }
                """));
        assertTrue(names.containsAll(List.of("Point", "i32", "str", "List", "shapes")), names.toString());
        assertFalse(names.contains("scale"));
        assertFalse(names.contains("local"));
        assertFalse(names.contains("println"));
        names = names(complete("""
                fn main() {
                    let p: shapes::‸
                }
                """));
        assertTrue(names.containsAll(List.of("Point", "Color")), names.toString());
        assertFalse(names.contains("scale"));
    }

    @Test
    void inAFunctionSignature() throws Exception {
        var names = names(complete("""
                use shapes::Point;
                fn area(p: Po‸
                fn main() {}
                """));
        assertTrue(names.contains("Point"), names.toString());
    }

    // ---- Patching ----

    @Test
    void statementMissingItsSemicolon() throws Exception {
        var names = names(complete("""
                use shapes::Point;
                fn main() {
                    let p = Point::new(1, 2);
                    let n = p.‸
                    println(n);
                }
                """));
        assertTrue(names.contains("dist"), names.toString());
    }

    @Test
    void insideAnUnclosedCall() throws Exception {
        var names = names(complete("""
                use shapes::Point;
                fn main() {
                    let p = Point::new(1, 2);
                    println(p.‸
                }
                """));
        assertTrue(names.contains("dist"), names.toString());
    }

    @Test
    void fallbackWhenNothingParses() throws Exception {
        open("use shapes::Point;\nfn helper() -> i32 => 1;\nfn main() {}\n");
        var names = names(complete("""
                use shapes::Point;
                fn helper() -> i32 => 1;
                fn main() {}
                pub he‸
                """));
        assertTrue(names.contains("helper"), names.toString());
        assertFalse(names.contains("Point") && names.contains("dist"));
    }

    @Test
    void nothingInCommentsOrStrings() throws Exception {
        assertEquals(List.of(), complete("fn main() {\n    // p.‸\n}\n"));
        assertEquals(List.of(), complete("fn main() {\n    let s = \"p.‸\";\n}\n"));
    }

    // ---- `use` lines ----

    @Test
    void useLines() throws Exception {
        var names = names(complete("use ‸\nfn main() {}\n"));
        assertTrue(names.containsAll(List.of("shapes", "geo", "std")), names.toString());
        assertFalse(names.contains("main"));
        names = names(complete("use shapes::‸\nfn main() {}\n"));
        assertTrue(names.containsAll(List.of("Point", "scale")), names.toString());
        assertFalse(names.contains("private_helper"));
        names = names(complete("use shapes::{Point, Co‸};\nfn main() {}\n"));
        assertEquals(List.of("Color"), names);
        names = names(complete("use std::‸\nfn main() {}\n"));
        assertTrue(names.containsAll(List.of("math", "collections")), names.toString());
        names = names(complete("use std::math::sq‸\nfn main() {}\n"));
        assertEquals(List.of("sqrt"), names);
    }

    // ---- Signature help ----

    @Test
    void signatureHelpListsOverloads() throws Exception {
        var help = signatureHelp("""
                use shapes::{Point, scale};
                fn main() {
                    let p = scale(Point::new(1, 2), ‸
                }
                """);
        assertEquals(2, help.getSignatures().size());
        assertEquals("fn scale(p: Point, by: i32) -> Point", help.getSignatures().getFirst().getLabel());
        assertEquals(1, help.getActiveParameter());
        assertEquals("by: i32", label(help, 0, 1));
    }

    @Test
    void signatureHelpByName() throws Exception {
        var help = signatureHelp("""
                use shapes::{Point, label};
                fn main() {
                    let s = label(Point::new(1, 2), suffix: ‸);
                }
                """);
        assertEquals(1, help.getSignatures().size());
        assertEquals(2, help.getActiveParameter());
        assertEquals("suffix: str = \"\"", label(help, 0, 2));
    }

    @Test
    void signatureHelpInANestedCall() throws Exception {
        var help = signatureHelp("""
                use shapes::{Point, scale};
                fn main() {
                    let p = scale(Point::new(1, ‸), 2);
                }
                """);
        assertEquals("fn Point::new(x: i32, y: i32) -> Point", help.getSignatures().getFirst().getLabel());
        assertEquals(1, help.getActiveParameter());
        help = signatureHelp("""
                use shapes::{Point, scale};
                fn main() {
                    let p = scale(Point::new(1, 2), ‸);
                }
                """);
        assertTrue(help.getSignatures().getFirst().getLabel().startsWith("fn scale"));
    }

    @Test
    void signatureHelpOnAGenericReceiver() throws Exception {
        var help = signatureHelp("""
                fn main() {
                    let mut xs = ["a"];
                    xs.push(‸
                }
                """);
        var label = help.getSignatures().getFirst().getLabel();
        assertTrue(label.contains("str") && !label.contains(": T"), label);
        assertEquals(0, help.getActiveParameter());
    }

    @Test
    void noSignatureHelpOutsideACall() throws Exception {
        assertNull(signatureHelp("fn main() {\n    let x = 1 + ‸;\n}\n"));
    }

    // ---- Helpers ----

    private List<CompletionItem> complete(String marked) throws Exception {
        int i = marked.indexOf('‸');
        var text = marked.substring(0, i) + marked.substring(i + 1);
        open(text);
        var items = server.completion(new CompletionParams(new TextDocumentIdentifier(uri(main)), position(text, i))).get();
        return items.getLeft().stream().sorted(java.util.Comparator.comparing(CompletionItem::getSortText)).toList();
    }

    private SignatureHelp signatureHelp(String marked) throws Exception {
        int i = marked.indexOf('‸');
        var text = marked.substring(0, i) + marked.substring(i + 1);
        open(text);
        var params = new SignatureHelpParams(new TextDocumentIdentifier(uri(main)), position(text, i));
        return server.signatureHelp(params).get();
    }

    private static String label(SignatureHelp help, int sig, int param) {
        var s = help.getSignatures().get(sig);
        var range = s.getParameters().get(param).getLabel().getRight();
        return s.getLabel().substring(range.getFirst(), range.getSecond());
    }

    private void open(String text) throws Exception {
        Files.writeString(main, text);
        server.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri(main), "threebolt", 1, text)));
        server.workspace().awaitIdle();
    }

    private static List<String> names(List<CompletionItem> items) {
        return items.stream().map(CompletionItem::getLabel).toList();
    }

    private static CompletionItem find(List<CompletionItem> items, String name) {
        var item = items.stream().filter(i -> i.getLabel().equals(name)).findFirst().orElse(null);
        assertNotNull(item, name);
        return item;
    }

    private static Position position(String text, int index) {
        var before = text.substring(0, index);
        int line = (int) before.chars().filter(c -> c == '\n').count();
        return new Position(line, index - before.lastIndexOf('\n') - 1);
    }

    private static String uri(Path file) {
        return file.toUri().toString();
    }

    private final class Client implements LanguageClient {
        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {}

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
