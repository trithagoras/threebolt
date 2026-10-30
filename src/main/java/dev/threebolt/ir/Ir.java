package dev.threebolt.ir;

import dev.threebolt.ast.Expr.BinaryOp;
import dev.threebolt.diag.Span;
import dev.threebolt.types.FieldInfo;
import dev.threebolt.types.FnInfo;
import dev.threebolt.types.Prim;
import dev.threebolt.types.StaticInfo;
import dev.threebolt.types.Type;
import dev.threebolt.types.TypeInfo;
import java.util.List;

/**
 * The checked program the interpreter runs: names, overloads, dispatch kinds, implicit
 * conversions (widening, boxing) and literal values are all resolved. Types that remain are
 * evaluated at runtime against the current generic bindings (§10, reified generics).
 */
public final class Ir {
    private Ir() {}

    // ---- Places (§5.1) ----

    /** Something that can be read and written: assignment targets and in-place receivers. */
    public sealed interface Place {}

    public record LocalPlace(Local local) implements Place {}

    public record StaticPlace(StaticInfo info) implements Place {}

    /**
     * A struct/class field or tuple element. The base is a place ({@code basePlace}, so writes
     * reach value-type bases in place) or an expression ({@code baseExpr}, a reference or temporary).
     */
    public record FieldPlace(Place basePlace, Expr baseExpr, int slot, String name) implements Place {}

    /** {@code c[i]} (§7.6): {@code Index::index} to read, {@code IndexMut::set} to write. */
    public record IndexPlace(Place basePlace, Expr baseExpr, Expr index, Span span) implements Place {}

    // ---- Expressions ----

    public sealed interface Expr {}

    /** A runtime value (a literal, a const, an enum member). */
    public record Lit(Object value) implements Expr {}

    /** Reads a place; value-type aggregates are copied (§5.3). */
    public record Read(Place place) implements Expr {}

    /** The old value inside a compound assignment's combined expression. */
    public record Hole() implements Expr {}

    public enum CallKind {
        /** Static functions, non-virtual methods, `super` calls. */
        DIRECT,
        /** Most-derived override of the receiver's runtime class (§8.2). */
        VIRTUAL,
        /** A trait member, resolved through the receiver's runtime type (§8.3). */
        TRAIT,
        /** A static trait function through a type parameter: {@code T::default()}. */
        TRAIT_STATIC
    }

    /**
     * A call of a declared function. {@code args} are in parameter order (null where the default
     * applies); {@code order} lists the given argument indices in evaluation order (§7.13).
     * The receiver is {@code recvPlace} for in-place {@code mut self} calls, else {@code recv}.
     * {@code ownerArgs} binds the owner's type parameters when there is no receiver to take them
     * from; {@code staticSelf} is the type a {@code TRAIT_STATIC} call dispatches on.
     */
    public record Call(FnInfo fn, CallKind kind, Expr recv, Place recvPlace, List<Expr> args, int[] order,
                       List<Type> typeArgs, List<Type> ownerArgs, Type staticSelf, Span span) implements Expr {}

    /** Calls a {@code fn} value. */
    public record CallValue(Expr fn, List<Expr> args, Span span) implements Expr {}

    /** A named function used as a value (§7.8). */
    public record FnRef(FnInfo fn, List<Type> typeArgs, Type type) implements Expr {}

    /**
     * {@code body} is a statement (block or assignment) or {@code value} an expression; the other is
     * null. Each parameter is an irrefutable pattern binding its argument.
     */
    public record Lambda(List<Pat> params, Stmt body, Expr value, Type.Fn type) implements Expr {}

    public enum UnOp { NEG, BIT_NOT }

    /** Built-in arithmetic on a primitive. */
    public record Unary(UnOp op, Prim prim, Expr operand, Span span) implements Expr {}

    /**
     * Built-in binary operator on primitives of type {@code prim} (arithmetic, bitwise, shifts,
     * comparisons, {@code str} concatenation), or on a {@code @flags} enum ({@code flags} set).
     */
    public record Binary(BinaryOp op, Prim prim, TypeInfo flags, Expr left, Expr right, Span span) implements Expr {}

    /** {@code ~} on a {@code @flags} enum. */
    public record FlagsNot(TypeInfo flags, Expr operand) implements Expr {}

    /** {@code ==}/{@code !=} by runtime value: primitives, structs field-wise, `Eq` impls, references. */
    public record Eq(Expr left, Expr right, boolean negate, Span span) implements Expr {}

    /** {@code < <= > >=} on enums or through `Ord::cmp`. */
    public record Compare(BinaryOp op, Expr left, Expr right, Span span) implements Expr {}

    public record And(Expr left, Expr right) implements Expr {}

    public record Or(Expr left, Expr right) implements Expr {}

    public record Not(Expr operand) implements Expr {}

    /** A condition (§6.6): null is false, bool is itself, otherwise {@code truthy()}. */
    public record Truthy(Expr operand, Span span) implements Expr {}

    public record Coalesce(Expr left, Expr right) implements Expr {}

    public record Cond(Expr condition, Expr then, Expr otherwise) implements Expr {}

    public record Is(Expr operand, Type type) implements Expr {}

    /** {@code operand is pattern} (§7.3); matching binds the pattern's locals. */
    public record IsPattern(Expr operand, Pat pattern) implements Expr {}

    public record Cast(Expr operand, Type type, boolean isChecked, Span span) implements Expr {}

    /** An implicit conversion to {@code type}: numeric widening, boxing a struct for a trait (§5.5). */
    public record Coerce(Expr operand, Type type) implements Expr {}

    /** Narrowed to a struct: a boxed struct becomes a copy (§5.5). */
    public record Unbox(Expr operand) implements Expr {}

    /** {@code e?} (§7.10): returns a value whose runtime type is one of {@code fail}. */
    public record Propagate(Expr operand, List<Type> fail, Span span) implements Expr {}

    /** Parts are {@link String}s and {@link Expr}s (displayed, §16 `Display`). */
    public record FString(List<Object> parts, Span span) implements Expr {}

    public record FieldInit(int slot, Expr value) {}

    /**
     * A struct/class initializer (§4.7): spread (nullable), then explicit fields in source order,
     * then the defaults of the remaining fields.
     */
    public record New(TypeInfo decl, List<Type> typeArgs, Expr spread, List<FieldInit> fields, Span span)
            implements Expr {}

    public enum CollectionKind { LIST, SET, ARRAY }

    public record NewCollection(CollectionKind kind, Type element, List<Expr> elements) implements Expr {}

    /** {@code xs[low..high]} on a list or array (§7.6): a new list; absent bounds are null. */
    public record Slice(Expr target, Expr low, Expr high, boolean isInclusive, Span span) implements Expr {}

    public record NewMap(Type key, Type value, List<Expr> keys, List<Expr> values) implements Expr {}

    /** {@code [value; count]}. */
    public record Repeat(CollectionKind kind, Type element, Expr value, Expr count, Span span) implements Expr {}

    /** A comprehension clause: {@code iterator} as for {@link For}; {@code filter} is null without {@code if}. */
    public record CompClause(Pat pattern, Expr iterator, Expr filter) {}

    /** {@code [value for ...]} (§7.7): the clauses as nested loops, adding {@code value} innermost. */
    public record ListComp(CollectionKind kind, Type element, List<CompClause> clauses, Expr value) implements Expr {}

    /** {@code [keyValue: valueValue for ...]} (§7.7). */
    public record MapComp(Type key, Type value, List<CompClause> clauses, Expr keyValue, Expr valueValue)
            implements Expr {}

    public record Tuple(List<Expr> elements) implements Expr {}

    public record TypeOf(Type type) implements Expr {}

    public record NameOf(Type type) implements Expr {}

    /** {@code x.type()}. */
    public record TypeOfValue(Expr operand) implements Expr {}

    public record Arm(Pat pattern, Expr guard, Stmt body, Expr value) {}

    /** A value-producing match yields the chosen arm's {@code value}; a block arm must diverge. */
    public record Match(Expr scrutinee, List<Arm> arms, Span span) implements Expr {}

    public enum SelectKind { RECV, SEND, JOIN, AFTER, DEFAULT }

    /**
     * A select case (§7.14): {@code target} is the channel, thread or duration, {@code value} what a
     * send sends; {@code pattern} binds the outcome (null without one). The arm runs {@code body}, or
     * yields {@code value} of {@code result} (a block arm yields `()`).
     */
    public record SelectCase(SelectKind kind, Expr target, Expr value, Pat pattern, Stmt body, Expr result) {}

    /** {@code closed} builds the `ClosedErr` a closed channel's case binds; {@code cancelled} what a cancelled thread's select yields. */
    public record Select(List<SelectCase> cases, Expr closed, Expr cancelled, Span span) implements Expr {}

    // ---- Patterns (§11) ----

    public sealed interface Pat {}

    public record PWild() implements Pat {}

    /** Matches {@code sub} (if any), then binds; {@code unbox} copies a boxed struct (§5.5). */
    public record PBind(Local local, Pat sub, boolean unbox) implements Pat {}

    public record PLit(Object value) implements Pat {}

    public record PRange(Object low, Object high, boolean isInclusive) implements Pat {}

    public record PTuple(List<Pat> elements) implements Pat {}

    public record POr(List<Pat> alternatives) implements Pat {}

    /** {@code [head.., ..rest, tail..]}; {@code rest} is null without a binding. */
    public record PList(List<Pat> head, boolean hasRest, Local rest, List<Pat> tail, Type element) implements Pat {}

    public record PStruct(TypeInfo decl, List<FieldInfo> fields, List<Pat> patterns) implements Pat {}

    public record PIs(Type type) implements Pat {}

    /** Matches when {@code pattern} (which binds nothing) does not. */
    public record PNot(Pat pattern) implements Pat {}

    /** Compares the value with {@code value} by {@code op} (LT, LE, GT or GE) if it has the same primitive type. */
    public record PRel(BinaryOp op, Object value) implements Pat {}

    // ---- Statements ----

    public sealed interface Stmt {}

    public record Let(Pat pattern, Expr value) implements Stmt {}

    public record Assign(Place place, Expr value, Span span) implements Stmt {}

    /** {@code place op= e}: {@code combined} computes the new value from {@link Hole} (the old one). */
    public record Compound(Place place, Expr combined, Span span) implements Stmt {}

    /** {@code _ = e;} and expression statements. */
    public record Eval(Expr expr) implements Stmt {}

    public record If(Expr condition, Block then, Stmt otherwise) implements Stmt {}

    /** {@code if let x = value}: binds when non-null. */
    public record IfLet(Local local, Expr value, Block then, Stmt otherwise) implements Stmt {}

    public record While(Expr condition, Block body) implements Stmt {}

    public record WhileLet(Local local, Expr value, Block body) implements Stmt {}

    public record Loop(Block body) implements Stmt {}

    /** {@code iterable} already yields an iterator ({@code Iterable::iter} applied by the checker). */
    public record For(Pat pattern, Expr iterator, Block body) implements Stmt {}

    public record Break() implements Stmt {}

    public record Continue() implements Stmt {}

    public record Return(Expr value) implements Stmt {}

    /** {@code body} is a {@link Block} or an {@link Eval}. */
    public record Defer(Stmt body) implements Stmt {}

    public record Block(List<Stmt> stmts) implements Stmt {}
}
