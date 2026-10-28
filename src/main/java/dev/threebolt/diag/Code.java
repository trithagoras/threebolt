package dev.threebolt.diag;

/**
 * Diagnostic codes. Conformance tests match on these, so a code's number never changes meaning;
 * retired codes are not reused. Grouped by stage: E00xx syntax, E01xx modules and names, E02xx
 * types and expressions, E03xx mutability, E04xx declarations. W0xxx are warnings: they do not stop
 * a program from running.
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
    PUB_USE("E0106"),
    UNRESOLVED_NAME("E0107"),
    WRONG_KIND("E0108"),
    PRIVATE_MEMBER("E0109"),
    INVALID_MAIN("E0110"),

    TYPE_MISMATCH("E0200"),
    LITERAL_OUT_OF_RANGE("E0201"),
    NO_SUCH_MEMBER("E0202"),
    BAD_ARGUMENTS("E0203"),
    NO_MATCHING_OVERLOAD("E0204"),
    AMBIGUOUS_CALL("E0205"),
    NOT_CALLABLE("E0206"),
    BAD_OPERAND("E0207"),
    BAD_CONDITION("E0208"),
    CANNOT_INFER("E0209"),
    MISSING_RETURN("E0210"),
    UNUSED_ERROR("E0211"),
    BAD_PROPAGATION("E0212"),
    NON_EXHAUSTIVE("E0213"),
    UNION_MEMBER_ACCESS("E0214"),
    IMPOSSIBLE_CAST("E0215"),
    UNSATISFIED_BOUND("E0216"),
    MISSING_FIELD("E0217"),
    BAD_FIELD("E0218"),
    INITIALIZER_NOT_ALLOWED("E0219"),
    NO_TARGET_TYPE("E0220"),
    NOT_DISPLAY("E0221"),
    OUTSIDE_LOOP("E0222"),
    BAD_DEFER("E0223"),
    REFUTABLE_PATTERN("E0224"),
    NOT_CONSTANT("E0225"),
    BAD_PATTERN("E0226"),
    UNSAFE_REQUIRED("E0227"),
    BAD_SUPER("E0228"),
    BAD_LAMBDA("E0229"),
    WRONG_TYPE_ARGUMENTS("E0230"),
    UNBOUND_BINDING("E0231"),
    NOT_ABI_TYPE("E0232"),
    BAD_CALLBACK("E0233"),

    IMMUTABLE_PLACE("E0300"),
    IMMUTABLE_RECEIVER("E0301"),
    NOT_A_PLACE("E0302"),

    BAD_OVERRIDE("E0400"),
    HIDDEN_METHOD("E0401"),
    MISSING_ABSTRACT("E0402"),
    MISSING_TRAIT_MEMBER("E0403"),
    DUPLICATE_IMPL("E0404"),
    BAD_BASE("E0405"),
    CONFLICTING_OVERLOADS("E0406"),
    BAD_MODIFIER("E0407"),
    BAD_ENUM("E0408"),
    BAD_GETTER("E0409"),
    BAD_IMPL("E0410"),
    CYCLE("E0411"),

    UNUSED_VARIABLE("W0001"),
    UNUSED_MUT("W0002"),
    UNUSED_IMPORT("W0003"),
    UNREACHABLE("W0004"),
    UNUSED_ITEM("W0005"),
    NO_EFFECT("W0006");

    private final String id;

    Code(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean isWarning() {
        return id.startsWith("W");
    }
}
