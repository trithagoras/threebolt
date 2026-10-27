package dev.threebolt.diag;

/**
 * Diagnostic codes. Conformance tests match on these, so a code's number never changes meaning;
 * retired codes are not reused. Grouped by front-end stage: E00xx syntax, E01xx modules and names.
 */
public enum Code {
    SYNTAX("E0001"),
    INVALID_LITERAL("E0002"),
    INVALID_TUPLE_INDEX("E0003"),
    UNKNOWN_ATTRIBUTE("E0004"),

    MODULE_NOT_FOUND("E0100"),
    ITEM_NOT_FOUND("E0101"),
    PRIVATE_ITEM("E0102"),
    AMBIGUOUS_IMPORT("E0103"),
    DUPLICATE_NAME("E0104"),
    PRIMITIVE_NAME("E0105"),
    PUB_USE("E0106");

    private final String id;

    Code(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
