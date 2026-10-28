package dev.threebolt.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.threebolt.diag.Diagnostics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** `bolt new`, `bolt add`, and finding the project (`Project`). */
class ScaffoldTest {
    @TempDir
    Path dir;

    @Test
    void newPackageInAWorkspaceJoinsItsMembers() throws Exception {
        Scaffold.create(dir, "ws", Scaffold.Kind.WORKSPACE);
        var ws = dir.resolve("ws");
        var out = Scaffold.create(ws, "server", Scaffold.Kind.BIN);
        Scaffold.create(ws, "shared", Scaffold.Kind.LIB);
        assertEquals(List.of("created package 'server' in server/", "added 'server' to the members in workspace.toml"), out);
        assertEquals("[workspace]\nmembers = [\"server\", \"shared\"]\n", Files.readString(ws.resolve("workspace.toml")));
        assertTrue(Files.isRegularFile(ws.resolve("server/main.3b")));
        assertTrue(Files.notExists(ws.resolve("shared/main.3b")));

        var diagnostics = new Diagnostics();
        var project = Project.open(ws, ws, diagnostics);
        assertEquals(0, diagnostics.count());
        assertTrue(project.isWorkspace());
        assertEquals(List.of("server"), project.targets().stream().map(t -> t.bin().name()).toList());
        assertEquals(ws.resolve("shared"), project.packageOf(ws.resolve("shared/a/b.3b")).root());
    }

    @Test
    void namesAreChecked() {
        assertThrows(Scaffold.Failure.class, () -> Scaffold.create(dir, "my-app", Scaffold.Kind.BIN));
        assertThrows(Scaffold.Failure.class, () -> Scaffold.create(dir, "std", Scaffold.Kind.LIB));
    }

    @Test
    void addKeysTheDependencyByItsName() throws Exception {
        Scaffold.create(dir, "app", Scaffold.Kind.BIN);
        Scaffold.create(dir, "shared_lib", Scaffold.Kind.LIB);
        var app = dir.resolve("app");
        assertEquals(List.of("added dependency 'shared_lib' (../shared_lib)"), Scaffold.add(app, app, "../shared_lib"));
        assertTrue(Files.readString(app.resolve("package.toml")).endsWith("[dependencies]\nshared_lib = { path = \"../shared_lib\" }\n"));
        assertThrows(Scaffold.Failure.class, () -> Scaffold.add(app, app, "../shared_lib"));
        assertThrows(Scaffold.Failure.class, () -> Scaffold.add(app, app, "../nowhere"));
    }

    @Test
    void dependencyLinesGoAtTheEndOfTheirTable() {
        assertEquals("[package]\nname = \"a\"\n\n[dependencies]\nx = 1\ny = 2\n\n[[bin]]\n",
                Scaffold.addDependency("[package]\nname = \"a\"\n\n[dependencies]\nx = 1\n\n[[bin]]\n", "y = 2"));
        assertEquals("[package]\nname = \"a\"\n\n[dependencies]\ny = 2\n", Scaffold.addDependency("[package]\nname = \"a\"\n", "y = 2"));
    }

    @Test
    void bothManifestsInOneDirectoryIsAnError() throws IOException {
        Files.writeString(dir.resolve("package.toml"), "[package]\nname = \"a\"\n");
        Files.writeString(dir.resolve("workspace.toml"), "[workspace]\nmembers = []\n");
        var diagnostics = new Diagnostics();
        Project.open(dir, dir, diagnostics);
        assertEquals(1, diagnostics.count());
    }
}
