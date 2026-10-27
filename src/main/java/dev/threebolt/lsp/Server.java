package dev.threebolt.lsp;

import dev.threebolt.diag.Diagnostic;
import dev.threebolt.diag.Span;
import dev.threebolt.ir.Local;
import dev.threebolt.parser.ThreeboltLexer;
import dev.threebolt.types.FnInfo;
import dev.threebolt.types.SymbolIndex;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.antlr.v4.runtime.CharStreams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DiagnosticRelatedInformation;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightKind;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameOptions;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * The Threebolt language server: diagnostics as you type, outline, hover, go to definition,
 * references, highlights and rename, all from {@link Analysis} (the checker plus its
 * {@link SymbolIndex}).
 */
public final class Server implements LanguageServer, LanguageClientAware, TextDocumentService, WorkspaceService {
    private LanguageClient client;
    private final Workspace workspace = new Workspace(this::publish, this::log);

    Workspace workspace() {
        return workspace;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }

    // ---- Lifecycle ----

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        if (params.getWorkspaceFolders() != null) {
            params.getWorkspaceFolders().forEach(f -> workspace.addRoot(path(f.getUri())));
        }
        var caps = new ServerCapabilities();
        caps.setTextDocumentSync(TextDocumentSyncKind.Full);
        caps.setHoverProvider(true);
        caps.setDefinitionProvider(true);
        caps.setReferencesProvider(true);
        caps.setDocumentHighlightProvider(true);
        caps.setDocumentSymbolProvider(true);
        caps.setRenameProvider(new RenameOptions(true));
        return CompletableFuture.completedFuture(new InitializeResult(caps, new ServerInfo("threebolt", "0.1")));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        workspace.shutdown();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return this;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return this;
    }

    // ---- Documents ----

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        workspace.opened(path(params.getTextDocument().getUri()), params.getTextDocument().getText());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        var changes = params.getContentChanges();
        if (changes.isEmpty()) return;
        workspace.changed(path(params.getTextDocument().getUri()), changes.getLast().getText());
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        workspace.closed(path(params.getTextDocument().getUri()));
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        workspace.saved(path(params.getTextDocument().getUri()));
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {}

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {}

    private void publish(Analysis a, Path file, List<Diagnostic> diagnostics) {
        var out = new ArrayList<org.eclipse.lsp4j.Diagnostic>();
        for (var d : diagnostics) {
            var lsp = new org.eclipse.lsp4j.Diagnostic(Positions.range(d.span()), d.message(), DiagnosticSeverity.Error,
                    "threebolt", d.code().id());
            var related = new ArrayList<DiagnosticRelatedInformation>();
            for (var n : d.notes()) {
                var loc = location(a, n.span());
                if (loc != null) related.add(new DiagnosticRelatedInformation(loc, n.message()));
            }
            if (!related.isEmpty()) lsp.setRelatedInformation(related);
            out.add(lsp);
        }
        client.publishDiagnostics(new PublishDiagnosticsParams(file.toUri().toString(), out));
    }

    private void log(String message) {
        if (client != null) client.logMessage(new MessageParams(MessageType.Error, message));
    }

    // ---- Queries ----

    /** The analysis and the name at a position, or null. */
    private record At(Analysis analysis, SymbolIndex.Ref ref) {}

    private At at(TextDocumentPositionParams params) {
        var file = path(params.getTextDocument().getUri());
        var a = workspace.forQuery(file);
        if (a == null) return null;
        var m = a.module(file);
        if (m == null) return null;
        var ref = a.index.at(m.source(), Positions.offset(m.source(), params.getPosition()));
        return ref == null ? null : new At(a, ref);
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFuture.supplyAsync(() -> {
            var at = at(params);
            if (at == null) return null;
            var text = "```threebolt\n" + SymbolIndex.describe(at.ref()) + "\n```";
            return new Hover(new MarkupContent(MarkupKind.MARKDOWN, text), Positions.range(at.ref().span()));
        });
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            var at = at(params);
            var def = at == null ? null : SymbolIndex.definition(at.ref().target());
            var loc = def == null ? null : location(at.analysis(), def);
            return Either.forLeft(loc == null ? List.of() : List.of(loc));
        });
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return CompletableFuture.supplyAsync(() -> {
            var at = at(params);
            if (at == null) return List.of();
            boolean withDecl = params.getContext() == null || params.getContext().isIncludeDeclaration();
            var out = new ArrayList<Location>();
            for (var r : occurrences(at)) {
                if (!withDecl && r.isDecl()) continue;
                var loc = location(at.analysis(), r.span());
                if (loc != null) out.add(loc);
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(DocumentHighlightParams params) {
        return CompletableFuture.supplyAsync(() -> {
            var at = at(params);
            if (at == null) return List.of();
            var source = at.ref().span().source();
            return occurrences(at).stream()
                    .filter(r -> r.span().source() == source)
                    .map(r -> new DocumentHighlight(Positions.range(r.span()),
                            r.isDecl() ? DocumentHighlightKind.Write : DocumentHighlightKind.Read))
                    .toList();
        });
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        return CompletableFuture.supplyAsync(() -> {
            var file = path(params.getTextDocument().getUri());
            var a = workspace.forQuery(file);
            var m = a == null ? null : a.module(file);
            if (m == null || m.ast() == null) return List.of();
            return Outline.of(m.ast()).stream().map(Either::<SymbolInformation, DocumentSymbol>forRight).toList();
        });
    }

    @Override
    public CompletableFuture<Either3<org.eclipse.lsp4j.Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> prepareRename(
            PrepareRenameParams params) {
        return CompletableFuture.supplyAsync(() -> {
            var at = at(params);
            String why = at == null ? "nothing to rename here" : renameBlocker(at);
            if (why != null) throw new ResponseErrorException(new ResponseError(ResponseErrorCode.InvalidRequest, why, null));
            return Either3.forFirst(Positions.range(at.ref().span()));
        });
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        return CompletableFuture.supplyAsync(() -> {
            var at = at(params);
            String why = at == null ? "nothing to rename here" : renameBlocker(at);
            if (why == null && !isIdentifier(params.getNewName())) why = "'" + params.getNewName() + "' is not an identifier";
            if (why != null) throw new ResponseErrorException(new ResponseError(ResponseErrorCode.InvalidRequest, why, null));
            Map<String, List<TextEdit>> changes = new HashMap<>();
            String name = text(SymbolIndex.definition(at.ref().target()));
            for (var r : occurrences(at)) {
                if (!text(r.span()).equals(name)) continue;  // through an import alias, which stays
                var file = at.analysis().file(r.span().source());
                changes.computeIfAbsent(file.toUri().toString(), k -> new ArrayList<>())
                        .add(new TextEdit(Positions.range(r.span()), params.getNewName()));
            }
            return new WorkspaceEdit(changes);
        });
    }

    /** Why the name at {@code at} cannot be renamed, or null if it can. */
    private String renameBlocker(At at) {
        var def = SymbolIndex.definition(at.ref().target());
        if (def == null) return "'" + text(at.ref().span()) + "' is built in";
        if (at.ref().target() instanceof Local l && l.name().equals("self")) return "`self` cannot be renamed";
        var file = at.analysis().file(def.source());
        if (file == null || workspace.std.contains(file)) return "'" + text(at.ref().span()) + "' is declared in std";
        for (var r : occurrences(at)) {
            if (workspace.std.contains(at.analysis().file(r.span().source()))) {
                return "'" + text(at.ref().span()) + "' is also used in std";
            }
            if (at.analysis().index.isShared(r)) return "it is used in a shorthand `{ " + text(r.span()) + " }`; write it out first";
        }
        return null;
    }

    /**
     * Every occurrence of the name's target. A method's overrides and overridden methods count as
     * the same name, since renaming one alone would break the override.
     */
    private static List<SymbolIndex.Ref> occurrences(At at) {
        if (!(at.ref().target() instanceof FnInfo f)) return at.analysis().index.refsTo(at.ref().target());
        var root = f.root();
        var out = new ArrayList<SymbolIndex.Ref>();
        for (Object t : at.analysis().index.targets()) {
            if (t instanceof FnInfo g && g.root() == root) out.addAll(at.analysis().index.refsTo(g));
        }
        return out;
    }

    private static boolean isIdentifier(String s) {
        var tokens = new ThreeboltLexer(CharStreams.fromString(s)).getAllTokens();
        return tokens.size() == 1 && tokens.getFirst().getType() == ThreeboltLexer.IDENTIFIER
                && tokens.getFirst().getText().equals(s);
    }

    private static String text(Span span) {
        var t = span.source().text();
        int from = t.offsetByCodePoints(0, span.start());
        return t.substring(from, t.offsetByCodePoints(from, span.end() - span.start()));
    }

    private static Location location(Analysis a, Span span) {
        var file = a.file(span.source());
        return file == null ? null : new Location(file.toUri().toString(), Positions.range(span));
    }

    private static Path path(String uri) {
        return Path.of(URI.create(uri)).toAbsolutePath().normalize();
    }
}
