package dev.threebolt.lsp;

import dev.threebolt.diag.Source;
import dev.threebolt.parse.DocComments;
import dev.threebolt.types.FnInfo;
import dev.threebolt.types.SymbolIndex;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;

/**
 * Doc comments (`///`) of what names refer to, as Markdown. An undocumented override or trait
 * implementation shows the documentation of what it overrides or implements.
 */
final class Docs {
    /** Each source's doc comments, lexed once; sources of finished analyses drop out. */
    private static final Map<Source, DocComments> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private Docs() {}

    /** The documentation of {@code target} (a {@link SymbolIndex.Ref} target), or null. */
    static String of(Object target) {
        Object t = target;
        for (int depth = 0; t != null && depth < 16; depth++) {
            var decl = SymbolIndex.declaration(t);
            if (decl == null) return null;
            var doc = CACHE.computeIfAbsent(decl.source(), DocComments::of).at(decl.start());
            if (doc != null) return doc;
            t = t instanceof FnInfo f ? SymbolIndex.inheritsDocsFrom(f) : null;
        }
        return null;
    }

    /** {@link #of} as LSP Markdown, or null. */
    static MarkupContent markup(Object target) {
        var doc = target == null ? null : of(target);
        return doc == null ? null : new MarkupContent(MarkupKind.MARKDOWN, doc);
    }

    /** A hover: the declaration as code, then its documentation. */
    static String hover(String signature, Object target) {
        var text = "```threebolt\n" + signature + "\n```";
        var doc = of(target);
        return doc == null ? text : text + "\n\n---\n\n" + doc;
    }
}
