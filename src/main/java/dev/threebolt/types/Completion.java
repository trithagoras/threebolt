package dev.threebolt.types;

import dev.threebolt.diag.Span;
import dev.threebolt.resolve.ModulePath;
import java.nio.file.Path;
import java.util.List;

/**
 * A completion or signature-help request, answered while checking (editor tooling). The editor
 * inserts {@link #PLACEHOLDER} at the cursor, so the code parses and the checker reaches the
 * name through its usual error paths; there it lists what could be written instead
 * ({@link Completer}). Signature help needs no placeholder: the innermost call around
 * {@link #offset} in {@link #file} records its overloads.
 *
 * <p>As in {@link SymbolIndex}, a site recorded with diagnostics muted (speculative typing) only
 * fills a gap; a later unmuted one replaces it.
 */
public final class Completion {
    /** The identifier inserted at the cursor. */
    public static final String PLACEHOLDER = "__3b_complete";

    public enum Kind {
        VARIABLE, FIELD, PROPERTY, METHOD, FUNCTION, STRUCT, CLASS, TRAIT, ENUM, ENUM_MEMBER, TYPE_ALIAS,
        TYPE_PARAMETER, PRIMITIVE, MODULE, CONSTANT, STATIC
    }

    /**
     * One name that could be written at the cursor. {@code detail} describes it as a hover does;
     * a lower {@code rank} sorts first (locals, then members declared on the type itself, then
     * inherited and trait members or module items, then the prelude).
     */
    public record Item(String name, Kind kind, String detail, int rank) {}

    /** What the cursor is completing. */
    public enum Site { MEMBER, STATIC_MEMBER, MODULE_ITEM, NAME, TYPE }

    /**
     * One overload of the call around the cursor. {@code params} are [start, end) offsets of each
     * parameter in {@code label}, and {@code names} their names; a UFCS call's receiver is
     * parameter 0, named {@code self}.
     */
    public record Signature(String label, List<int[]> params, List<String> names) {}

    final Path file;
    final int offset;

    // Completion: set by the first site recorded (see the class comment).
    Site site;
    String prefix = "";
    List<Item> items = List.of();
    /** For {@link Site#MODULE_ITEM}: the module path before `::`. */
    ModulePath module;
    boolean siteMuted;

    // Signature help: the innermost call around the offset.
    List<Signature> signatures = List.of();
    Span call;
    /** Where the call's arguments begin: the end of its callee. */
    int argsFrom;
    boolean callMuted;

    /** A request at {@code offset} (a code-point offset) in {@code file}. */
    public Completion(Path file, int offset) {
        this.file = file;
        this.offset = offset;
    }

    /** The kind of name being completed, or null if the checker never reached the placeholder. */
    public Site site() {
        return site;
    }

    /** What was typed before the cursor, part of the placeholder's identifier. */
    public String prefix() {
        return prefix;
    }

    public List<Item> items() {
        return items;
    }

    public ModulePath module() {
        return module;
    }

    /** The overloads of the innermost call around the offset; empty if there is none. */
    public List<Signature> signatures() {
        return signatures;
    }

    public int argsFrom() {
        return argsFrom;
    }

    /** The text before {@link #PLACEHOLDER} in {@code name}, or null if it is not the placeholder. */
    static String prefixOf(String name) {
        int i = name.indexOf(PLACEHOLDER);
        return i < 0 ? null : name.substring(0, i);
    }
}
