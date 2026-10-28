package dev.threebolt.types;

import dev.threebolt.ast.Body;
import dev.threebolt.ast.Expr;
import dev.threebolt.ast.AstWalk;
import dev.threebolt.ast.Pattern;
import dev.threebolt.ast.Stmt;
import dev.threebolt.ast.TypeExpr;
import dev.threebolt.diag.Code;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Span;
import dev.threebolt.interp.Values;
import dev.threebolt.ir.Ir;
import dev.threebolt.ir.Local;
import dev.threebolt.resolve.Module;
import dev.threebolt.resolve.Program;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Type-checks every body in the program and lowers it to {@link Ir}: statements, flow-sensitive
 * narrowing (§9), places and mutability (§5). Expressions, calls and patterns live in
 * {@link Exprs}, {@link Calls} and {@link Patterns}.
 */
public final class Checker {
    /** A checked expression and its static type. */
    public record Typed(Ir.Expr ir, Type type) {}

    /** A place (§5.1): where it is, its type, and whether it may be written. */
    record PlaceInfo(Ir.Place place, Type type, boolean isMutable, String immutableWhy, Local local) {}

    /** Lexical scope of locals. */
    static final class Scope {
        final Scope parent;
        final Map<String, Local> locals = new HashMap<>();

        Scope(Scope parent) {
            this.parent = parent;
        }

        Local find(String name) {
            for (Scope s = this; s != null; s = s.parent) {
                var l = s.locals.get(name);
                if (l != null) return l;
            }
            return null;
        }
    }

    /** The body being checked: a function, lambda, field default, static or const initializer. */
    static final class Ctx {
        final Module module;
        final TypeResolver.Scope typeScope;
        /** The type whose private members are visible here. */
        final TypeInfo enclosing;
        final FnInfo fn;
        /** Lambda nesting depth; 0 in named functions. */
        final int depth;
        /** The declared return type; null while inferring a lambda's. */
        Type returnType;
        final List<Type> inferredReturns = new ArrayList<>();
        int unsafeDepth;
        boolean inDefer;
        int loops;
        boolean loopBroke;
        Scope scope;
        Map<Local, Type> narrowed = new HashMap<>();
        boolean reachable = true;
        Local self;

        Ctx(Module module, TypeResolver.Scope typeScope, TypeInfo enclosing, FnInfo fn, int depth) {
            this.module = module;
            this.typeScope = typeScope;
            this.enclosing = enclosing;
            this.fn = fn;
            this.depth = depth;
        }
    }

    final Program program;
    final Diagnostics diag;
    final SymbolIndex index;
    final Declarer decls;
    final TypeSystem ts;
    final TypeResolver types;
    final Names names;
    final Exprs exprs;
    final Calls calls;
    final Patterns patterns;
    final ConstEval consts;
    final Lints lints;
    /** Answers the index's completion request, if any; else null. */
    Completer completer;
    /** Lambda depth at which each local was declared (captured-mutable locals are not narrowed). */
    final Map<Local, Integer> localDepth = new HashMap<>();
    Ctx ctx;

    public Checker(Program program, Diagnostics diag) {
        this(program, diag, SymbolIndex.disabled());
    }

    /** Also records what every name refers to in {@code index}. */
    public Checker(Program program, Diagnostics diag, SymbolIndex index) {
        this.program = program;
        this.diag = diag;
        this.index = index;
        index.attach(diag);
        this.decls = new Declarer(program, diag, index);
        this.ts = decls.ts;
        this.types = decls.types;
        this.names = decls.names;
        this.exprs = new Exprs(this);
        this.calls = new Calls(this);
        this.patterns = new Patterns(this);
        this.consts = new ConstEval(this);
        this.lints = new Lints(this);
        index.lints = lints;
        names.lints = lints;
        types.constSize = consts::arraySize;
        if (index.completion != null) {
            completer = new Completer(this, index.completion);
            names.completer = completer;
            types.completer = completer;
        }
    }

    /** Checks the whole program; the result is runnable when there were no errors. */
    public CheckedProgram check() {
        decls.run();
        index.declarations(program, decls);
        for (TypeInfo t : decls.allTypes) {
            if (t.isEnum()) consts.enumValues(t);
        }
        for (ConstInfo c : decls.consts) consts.value(c);
        for (TypeInfo t : decls.allTypes) fieldDefaults(t);
        for (StaticInfo s : decls.statics) staticInit(s);
        for (FnInfo f : new ArrayList<>(decls.allFns)) function(f);
        FnInfo main = null;
        if (program.entry() != null && program.entry().ast() != null) {
            for (var d : program.entry().scope().items("main")) {
                if (names.info(d.item()) instanceof FnInfo f) main = f;
            }
        }
        lints.finish(program, decls);
        return new CheckedProgram(ts, main, decls.statics);
    }

    // ---- Contexts ----

    Ctx moduleCtx(Module m) {
        return new Ctx(m, TypeResolver.Scope.of(m), null, null, 0);
    }

    Ctx typeCtx(TypeInfo t) {
        return new Ctx(t.module, decls.scopeOf(t), t, null, 0);
    }

    private <T> T in(Ctx c, java.util.function.Supplier<T> work) {
        var saved = ctx;
        ctx = c;
        try {
            return work.get();
        } finally {
            ctx = saved;
        }
    }

    private void fieldDefaults(TypeInfo t) {
        for (FieldInfo f : t.fields) {
            if (f.defaultValue == null) continue;
            var c = typeCtx(t);
            c.scope = new Scope(null);
            f.defaultIr = in(c, () -> check(f.defaultValue, f.type));
        }
    }

    private void staticInit(StaticInfo s) {
        var c = s.owner == null ? moduleCtx(s.module) : typeCtx(s.owner);
        c.scope = new Scope(null);
        s.init = in(c, () -> check(s.ast.value(), s.type));
    }

    /** Checks a function's parameter defaults and body. */
    private void function(FnInfo f) {
        // A bodyless trait member or abstract method still needs its defaults: calls through it use
        // them (§4.3).
        if (f.ast.body() == null && f.params.stream().allMatch(p -> p.defaultValue() == null)) {
            in(new Ctx(f.module, decls.scopeOf(f), null, f, 0), () -> {
                for (var p : f.params) bindingName(p.name(), SymbolIndex.nameIn(p.span(), p.name()), null);
                return null;
            });
            for (var p : f.params) f.defaults.add(null);
            return;
        }
        var owner = switch (f.owner) {
            case TypeInfo t -> t;
            case ImplInfo i -> i.targetDecl;
            case null, default -> null;
        };
        var c = new Ctx(f.module, decls.scopeOf(f), owner, f, 0);
        c.returnType = f.ret;
        c.scope = new Scope(null);
        if (f.isUnsafe()) c.unsafeDepth = 1;
        in(c, () -> {
            if (!f.isStatic()) {
                var selfType = c.typeScope.self();
                f.selfLocal = new Local("self", f.receiver == FnInfo.Receiver.MUT_SELF, selfType, f.span());
                c.self = f.selfLocal;
                localDepth.put(f.selfLocal, 0);
            }
            for (var p : f.params) {
                f.defaults.add(p.defaultValue() == null ? null : check(p.defaultValue(), p.type()));
                var local = declare(p.name(), p.isMutable(), p.type(), p.span());
                lints.param(local, Lints.signatureImposed(f));
                f.paramLocals.add(local);
            }
            if (f.ast.body() != null) f.body = fnBody(f.ast.body(), f.ret, f.span());
            return null;
        });
    }

    /** A function body: a block, or {@code => expr} ≡ {@code { return expr; }} (§4.3). */
    private Ir.Block fnBody(Body body, Type ret, Span span) {
        switch (body) {
            case Stmt.Block b -> {
                var block = block(b);
                if (ctx.reachable && !ts.assignable(Type.UNIT, ret)) {
                    diag.error(Code.MISSING_RETURN, closingBrace(b), "missing `return`: this function returns '" + ret + "'");
                }
                return block;
            }
            case Stmt.Assign a -> {
                if (!ts.assignable(Type.UNIT, ret)) {
                    diag.error(Code.TYPE_MISMATCH, a.span(), "an assignment body is only valid when the return type is `()`");
                }
                return new Ir.Block(List.of(stmt(a)));
            }
            case Expr e -> {
                var value = check(e, ret);
                ctx.reachable = false;
                return new Ir.Block(List.of(new Ir.Return(value)));
            }
        }
    }

    private static Span closingBrace(Stmt.Block b) {
        var s = b.span();
        return new Span(s.source(), Math.max(s.start(), s.end() - 1), s.end());
    }

    // ---- Locals and narrowing (§9) ----

    Local declare(String name, boolean mutable, Type type, Span span) {
        return declare(name, mutable, type, span, null);
    }

    /** {@code hint}, if any, follows the error for a name that is a type's (§11). */
    Local declare(String name, boolean mutable, Type type, Span span, String hint) {
        var local = new Local(name, mutable, type, span);
        ctx.scope.locals.put(name, local);
        localDepth.put(local, ctx.depth);
        ctx.narrowed.remove(local);
        var nameSpan = SymbolIndex.nameIn(span, name);
        bindingName(name, nameSpan, hint);
        index.decl(nameSpan, local);
        lints.declared(local, nameSpan);
        return local;
    }

    /** A binding cannot have a type's name (§11). */
    private void bindingName(String name, Span nameSpan, String hint) {
        if (namesType(name)) {
            diag.error(Code.TYPE_NAME_BINDING, nameSpan, "'" + name + "' is a type, so it cannot name a binding"
                    + (hint == null ? "" : "; " + hint));
        }
    }

    /** Whether {@code name} names a type here: a primitive, a type parameter, or a type or alias in scope. */
    boolean namesType(String name) {
        if (Prim.named(name) != null || ctx.typeScope.params().containsKey(name)) return true;
        var symbols = ctx.module.scope().lookup(name);
        return !symbols.isEmpty() && names.entity(symbols) instanceof Names.TypeEntity;
    }

    Local lookupLocal(String name) {
        return ctx.scope == null ? null : ctx.scope.find(name);
    }

    /**
     * An explicit type argument. One that names a local rather than a type most likely came from
     * a comparison that §7.5 read as generic arguments (`f(a < b, c > (d))`), so say so.
     */
    Type typeArg(TypeExpr te) {
        if (te instanceof TypeExpr.Named n && n.path().size() == 1 && n.typeArgs().isEmpty()
                && lookupLocal(n.path().getFirst().text()) != null) {
            int before = diag.dropped();
            var t = diag.muted(() -> types.resolve(te, ctx.typeScope));
            if (diag.dropped() == before) return t;
            diag.error(Code.UNRESOLVED_NAME, n.span(), "'" + n.path().getFirst().text()
                    + "' is a local, not a type: this `<...>` was parsed as generic arguments (§7.5); "
                    + "to compare, parenthesize: `(a < b)`");
            return Type.ERROR;
        }
        return types.resolve(te, ctx.typeScope);
    }

    /** The current (possibly narrowed) type of a local. */
    Type typeOf(Local local) {
        var t = ctx.narrowed.getOrDefault(local, unnarrowed(local));
        if (t == Type.UNBOUND) return t;
        // Captured `let mut` locals are not narrowed inside lambdas (§9).
        if (local.isMutable() && localDepth.getOrDefault(local, 0) < ctx.depth) return local.type;
        return t;
    }

    /** Reading a local: narrowed to a struct, a boxed value becomes a copy (§5.5). */
    Typed readLocal(Local local, Span span) {
        var type = typeOf(local);
        index.ref(span, local, type == Type.UNBOUND ? null : type);
        if (type == Type.UNBOUND) return unbound(local, span);
        Ir.Expr ir = new Ir.Read(new Ir.LocalPlace(local));
        if (type != local.type && TypeSystem.isStruct(type)) ir = new Ir.Unbox(ir);
        return new Typed(ir, type);
    }

    void narrow(Local local, Type type) {
        if (type.equals(unnarrowed(local))) ctx.narrowed.remove(local);
        else ctx.narrowed.put(local, type);
    }

    /** Joins two flow states: a local narrowed differently on each path gets the union. */
    Map<Local, Type> merge(Map<Local, Type> a, Map<Local, Type> b) {
        var out = new HashMap<Local, Type>();
        var keys = new HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (Local l : keys) {
            var unnarrowed = unnarrowed(l);
            var t = TypeSystem.union(a.getOrDefault(l, unnarrowed), b.getOrDefault(l, unnarrowed));
            if (!t.equals(unnarrowed)) out.put(l, t);
        }
        return out;
    }

    /** A local's type where nothing narrows it: unbound for an `is`-pattern binding (§7.3). */
    private static Type unnarrowed(Local l) {
        return l.isConditional ? Type.UNBOUND : l.type;
    }

    /** Reading an `is`-pattern binding where its test is not known to hold. */
    private Typed unbound(Local local, Span span) {
        diag.error(Code.UNBOUND_BINDING, span, "'" + local.name() + "' is only bound where the `is` test that "
                + "binds it holds (§7.3)");
        return new Typed(new Ir.Lit(Values.Unit.UNIT), Type.ERROR);
    }

    static Map<Local, Type> with(Map<Local, Type> base, Map<Local, Type> more) {
        if (more.isEmpty()) return base;
        var m = new HashMap<>(base);
        m.putAll(more);
        return m;
    }

    /** A condition with the narrowing it implies when true and when false. */
    record Cond(Ir.Expr ir, Map<Local, Type> whenTrue, Map<Local, Type> whenFalse) {}

    // ---- Statements (§6) ----

    Ir.Block block(Stmt.Block b) {
        var saved = ctx.scope;
        ctx.scope = new Scope(saved);
        var out = new ArrayList<Ir.Stmt>();
        boolean warned = false;
        for (Stmt s : b.stmts()) {
            if (!ctx.reachable && !warned) {
                lints.unreachable(s, b.stmts().getLast().span());
                warned = true;
            }
            out.add(stmt(s));
        }
        ctx.scope = saved;
        return new Ir.Block(out);
    }

    Ir.Stmt stmt(Stmt s) {
        return switch (s) {
            case Stmt.Let l -> let(l);
            case Stmt.Block b -> block(b);
            case Stmt.If i -> ifStmt(i);
            case Stmt.While w -> whileStmt(w);
            case Stmt.Loop l -> loop(l);
            case Stmt.For f -> forStmt(f);
            case Stmt.ExprStmt e -> exprStmt(e);
            case Stmt.Break b -> jump(b.span(), "break", new Ir.Break());
            case Stmt.Continue c -> jump(c.span(), "continue", new Ir.Continue());
            case Stmt.Return r -> returnStmt(r);
            case Stmt.Defer d -> defer(d);
            case Stmt.Unsafe u -> {
                ctx.unsafeDepth++;
                var b = block(u.body());
                ctx.unsafeDepth--;
                yield b;
            }
            case Stmt.Assign a -> assign(a);
        };
    }

    private Ir.Stmt let(Stmt.Let l) {
        Type declared = l.type() == null ? null : types.resolve(l.type(), ctx.typeScope);
        Typed value = exprs.expr(l.value(), declared);
        Ir.Expr ir = declared == null ? value.ir() : coerce(value, declared, l.value().span());
        Type type = declared != null ? declared : TypeSystem.widenInferred(value.type());
        if (value.type() == Type.NEVER) ctx.reachable = false;
        if (l.pattern() instanceof Pattern.Binding b && b.isType() == null) {
            var local = declare(b.name().text(), b.isMutable(), type, b.span());
            return new Ir.Let(new Ir.PBind(local, null, false), ir);
        }
        var pat = patterns.irrefutable(l.pattern(), type);
        return new Ir.Let(pat, ir);
    }

    private Ir.Stmt exprStmt(Stmt.ExprStmt s) {
        Typed t = s.expr() instanceof Expr.Match m ? patterns.match(m, null, true) : exprs.expr(s.expr(), null);
        if (t.type() == Type.NEVER) ctx.reachable = false;
        mustUse(t.type(), s.expr().span());
        lints.exprStmt(s.expr());
        return new Ir.Eval(t.ir());
    }

    /** Discarding a value whose type has an `Err` member is an error (§6.1). */
    void mustUse(Type type, Span span) {
        if (type == Type.ERROR || type == Type.NEVER) return;
        for (Type m : TypeSystem.members(type)) {
            if (m != Type.ERROR && !(m instanceof Type.Param) && ts.implementsAs(m, ts.error) != null) {
                diag.error(Code.UNUSED_ERROR, span, "this value of type '" + type
                        + "' may be an error; handle it, propagate it with `?`, or discard it with `_ = ...;`");
                return;
            }
        }
    }

    private Ir.Stmt ifStmt(Stmt.If s) {
        var saved = ctx.narrowed;
        Ir.Expr condIr;
        Map<Local, Type> whenTrue;
        Map<Local, Type> whenFalse;
        Local bound = null;
        var outer = ctx.scope;
        if (s.binder() != null) {
            // if let x = e (§6.3): e must include null; x is e's type minus null.
            var value = exprs.expr(s.condition(), null);
            condIr = value.ir();
            if (!TypeSystem.containsNull(value.type()) && value.type() != Type.ERROR) {
                diag.error(Code.BAD_CONDITION, s.condition().span(),
                        "`if let` needs a value that may be null, found '" + value.type() + "'");
            }
            ctx.scope = new Scope(outer);
            bound = declare(s.binder().name().text(), s.binder().isMutable(),
                    TypeSystem.widenInferred(ts.minus(value.type(), Type.NULL)), s.binder().span());
            whenTrue = Map.of();
            whenFalse = Map.of();
        } else {
            var c = exprs.condition(s.condition());
            condIr = c.ir();
            whenTrue = c.whenTrue();
            whenFalse = c.whenFalse();
        }
        ctx.narrowed = with(saved, whenTrue);
        ctx.reachable = true;
        var then = block(s.then());
        ctx.scope = outer;
        var thenNarrowed = ctx.narrowed;
        boolean thenReach = ctx.reachable;

        ctx.narrowed = with(saved, whenFalse);
        ctx.reachable = true;
        Ir.Stmt otherwise = s.otherwise() == null ? null : stmt(s.otherwise());
        var elseNarrowed = ctx.narrowed;
        boolean elseReach = ctx.reachable;

        if (thenReach && elseReach) ctx.narrowed = merge(thenNarrowed, elseNarrowed);
        else if (thenReach) ctx.narrowed = thenNarrowed;
        else if (elseReach) ctx.narrowed = elseNarrowed;
        else ctx.narrowed = saved;
        ctx.reachable = thenReach || elseReach;
        if (bound != null) return new Ir.IfLet(bound, condIr, then, otherwise);
        return new Ir.If(condIr, then, otherwise);
    }

    /** Before a loop, locals assigned in it lose their narrowing (§9). */
    private void forgetAssigned(Stmt body) {
        for (String name : assignedNames(body)) {
            var l = lookupLocal(name);
            if (l != null) ctx.narrowed.remove(l);
        }
    }

    private static Set<String> assignedNames(Object node) {
        var out = new HashSet<String>();
        AstWalk.walk(node, n -> {
            if (n instanceof Stmt.Assign a && a.target() instanceof Expr.Path p && p.segments().size() == 1) {
                out.add(p.segments().getFirst().name().text());
            }
        });
        return out;
    }

    private Ir.Stmt whileStmt(Stmt.While w) {
        forgetAssigned(w);
        var saved = ctx.narrowed;
        Ir.Expr condIr;
        Map<Local, Type> whenTrue;
        Map<Local, Type> whenFalse;
        Local bound = null;
        var outer = ctx.scope;
        if (w.binder() != null) {
            var value = exprs.expr(w.condition(), null);
            condIr = value.ir();
            if (!TypeSystem.containsNull(value.type()) && value.type() != Type.ERROR) {
                diag.error(Code.BAD_CONDITION, w.condition().span(),
                        "`while let` needs a value that may be null, found '" + value.type() + "'");
            }
            ctx.scope = new Scope(outer);
            bound = declare(w.binder().name().text(), w.binder().isMutable(),
                    TypeSystem.widenInferred(ts.minus(value.type(), Type.NULL)), w.binder().span());
            whenTrue = Map.of();
            whenFalse = Map.of();
        } else {
            var c = exprs.condition(w.condition());
            condIr = c.ir();
            whenTrue = c.whenTrue();
            whenFalse = c.whenFalse();
        }
        boolean infinite = w.binder() == null && w.condition() instanceof Expr.BoolLit b && b.value();
        ctx.narrowed = with(saved, whenTrue);
        var body = loopBody(w.body());
        ctx.scope = outer;
        boolean broke = lastLoopBroke;
        ctx.narrowed = broke ? saved : with(saved, whenFalse);
        ctx.reachable = !infinite || broke;
        if (bound != null) return new Ir.WhileLet(bound, condIr, body);
        return new Ir.While(condIr, body);
    }

    /** Whether the loop body checked last contains a `break` for its loop. */
    private boolean lastLoopBroke;

    private Ir.Block loopBody(Stmt.Block b) {
        boolean savedBroke = ctx.loopBroke;
        ctx.loopBroke = false;
        ctx.loops++;
        ctx.reachable = true;
        var saved = ctx.narrowed;
        var body = block(b);
        ctx.loops--;
        ctx.narrowed = saved;
        lastLoopBroke = ctx.loopBroke;
        ctx.loopBroke = savedBroke;
        return body;
    }

    private Ir.Stmt loop(Stmt.Loop l) {
        forgetAssigned(l);
        var saved = ctx.narrowed;
        var body = loopBody(l.body());
        ctx.narrowed = saved;
        ctx.reachable = lastLoopBroke;
        return new Ir.Loop(body);
    }

    private Ir.Stmt forStmt(Stmt.For f) {
        forgetAssigned(f);
        var iteration = iteration(f.iterable());
        var outer = ctx.scope;
        ctx.scope = new Scope(outer);
        var saved = ctx.narrowed;
        var pat = patterns.irrefutable(f.pattern(), iteration.element());
        var body = loopBody(f.body());
        ctx.scope = outer;
        ctx.narrowed = saved;
        ctx.reachable = true;
        return new Ir.For(pat, iteration.iterator(), body);
    }

    /** What a `for` (or comprehension clause) iterates: an iterator and its element type. */
    record Iteration(Ir.Expr iterator, Type element) {}

    /** §6.4: {@code e} must implement `Iterator<T>`, or `Iterable<T>` (then `iter()` is applied). */
    Iteration iteration(Expr e) {
        var iterable = exprs.expr(e, null);
        var t = iterable.type();
        if (t == Type.ERROR) return new Iteration(iterable.ir(), Type.ERROR);
        var asIterator = ts.implementsAs(t, ts.iterator);
        if (asIterator != null) return new Iteration(iterable.ir(), asIterator.args().getFirst());
        var asIterable = ts.implementsAs(t, ts.iterable);
        if (asIterable != null) {
            var iter = ts.iterable.methods("iter").getFirst();
            return new Iteration(new Ir.Call(iter, Ir.CallKind.TRAIT, iterable.ir(), null, List.of(), new int[0],
                    List.of(), null, null, e.span()), asIterable.args().getFirst());
        }
        diag.error(Code.TYPE_MISMATCH, e.span(), "'" + t + "' is not iterable: it implements neither `Iterable` nor `Iterator`");
        return new Iteration(iterable.ir(), Type.ERROR);
    }

    private Ir.Stmt jump(Span span, String what, Ir.Stmt ir) {
        if (ctx.loops == 0) {
            if (ctx.inDefer) diag.error(Code.BAD_DEFER, span, "`" + what + "` cannot leave a deferred block");
            else diag.error(Code.OUTSIDE_LOOP, span, "`" + what + "` outside of a loop");
        }
        if (what.equals("break")) ctx.loopBroke = true;
        ctx.reachable = false;
        return ir;
    }

    private Ir.Stmt returnStmt(Stmt.Return r) {
        if (ctx.inDefer) diag.error(Code.BAD_DEFER, r.span(), "`return` cannot leave a deferred block");
        Ir.Expr value;
        if (ctx.returnType == null) {
            // Inferring a lambda's return type (§7.8).
            if (r.value() == null) {
                ctx.inferredReturns.add(Type.UNIT);
                value = new Ir.Lit(Values.Unit.UNIT);
            } else {
                var t = exprs.expr(r.value(), null);
                ctx.inferredReturns.add(TypeSystem.widenInferred(t.type()));
                value = t.ir();
            }
        } else if (r.value() == null) {
            if (!ts.assignable(Type.UNIT, ctx.returnType)) {
                diag.error(Code.TYPE_MISMATCH, r.span(), "`return;` in a function returning '" + ctx.returnType + "'");
            }
            value = new Ir.Lit(Values.Unit.UNIT);
        } else {
            value = check(r.value(), ctx.returnType);
        }
        ctx.reachable = false;
        return new Ir.Return(value);
    }

    private Ir.Stmt defer(Stmt.Defer d) {
        boolean saved = ctx.inDefer;
        int savedLoops = ctx.loops;
        ctx.inDefer = true;
        ctx.loops = 0;
        boolean reach = ctx.reachable;
        var narrowed = ctx.narrowed;
        Ir.Stmt body = switch (d.body()) {
            case Stmt.Block b -> block(b);
            case Expr e -> {
                var t = exprs.expr(e, null);
                mustUse(t.type(), e.span());
                yield new Ir.Eval(t.ir());
            }
            case Stmt.Assign a -> assign(a);
        };
        ctx.inDefer = saved;
        ctx.loops = savedLoops;
        ctx.reachable = reach;
        ctx.narrowed = narrowed;
        return new Ir.Defer(body);
    }

    // ---- Assignment (§6.2) and places (§5.1) ----

    Ir.Stmt assign(Stmt.Assign a) {
        if (a.target() == null) {
            var t = exprs.expr(a.value(), null);
            if (t.type() == Type.NEVER) ctx.reachable = false;
            return new Ir.Eval(t.ir());
        }
        int errors = diag.count();
        var place = place(a.target());
        if (place == null) {
            if (diag.count() > errors) {
                // `place` already said why (e.g. assigning to a slice); just check the value.
                exprs.expr(a.value(), null);
                return new Ir.Eval(new Ir.Lit(Values.Unit.UNIT));
            }
            exprs.expr(a.target(), null);
            diag.error(Code.NOT_A_PLACE, a.target().span(), "cannot assign to this expression");
            exprs.expr(a.value(), null);
            return new Ir.Eval(new Ir.Lit(Values.Unit.UNIT));
        }
        if (!place.isMutable()) {
            diag.error(Code.IMMUTABLE_PLACE, a.target().span(), place.immutableWhy());
        }
        lints.mutated(place.place());
        // The declared type, not the narrowed one, bounds what may be stored (§9).
        Type target = place.local() != null ? place.local().type : place.type();
        if (a.op() == Stmt.AssignOp.ASSIGN) {
            var value = exprs.expr(a.value(), target);
            var ir = coerce(value, target, a.value().span());
            if (place.local() != null && value.type() != Type.ERROR) {
                var assigned = TypeSystem.widen(value.type());
                narrow(place.local(), ts.assignable(assigned, target) ? assigned : target);
            }
            return new Ir.Assign(place.place(), ir, a.span());
        }
        var old = new Typed(new Ir.Hole(), place.type());
        var combined = exprs.binary(binaryOp(a.op()), old, a.value(), a.span(), target);
        var ir = coerce(combined, target, a.span());
        if (place.local() != null) ctx.narrowed.remove(place.local());
        return new Ir.Compound(place.place(), ir, a.span());
    }

    private static Expr.BinaryOp binaryOp(Stmt.AssignOp op) {
        return switch (op) {
            case ADD -> Expr.BinaryOp.ADD;
            case SUB -> Expr.BinaryOp.SUB;
            case MUL -> Expr.BinaryOp.MUL;
            case DIV -> Expr.BinaryOp.DIV;
            case REM -> Expr.BinaryOp.REM;
            case AND -> Expr.BinaryOp.BIT_AND;
            case OR -> Expr.BinaryOp.BIT_OR;
            case XOR -> Expr.BinaryOp.BIT_XOR;
            case SHL -> Expr.BinaryOp.SHL;
            case SHR -> Expr.BinaryOp.SHR;
            case ASSIGN -> throw new IllegalArgumentException();
        };
    }

    /** {@code e} as a place, or null if it is not one (then it is a temporary). */
    PlaceInfo place(Expr e) {
        return switch (e) {
            case Expr.Path p when p.segments().size() == 1 && p.segments().getFirst().typeArgs().isEmpty() -> {
                String name = p.segments().getFirst().name().text();
                var local = lookupLocal(name);
                if (local != null) {
                    var type = typeOf(local);
                    index.ref(p.span(), local, type == Type.UNBOUND ? null : type);
                    if (type == Type.UNBOUND) type = unbound(local, p.span()).type();
                    yield new PlaceInfo(new Ir.LocalPlace(local), type, local.isMutable(),
                            "cannot assign to immutable binding '" + name + "'; declare it `let mut`", local);
                }
                if (names.lookup(ctx.module, name) instanceof Names.StaticEntity(StaticInfo s)) {
                    index.ref(p.span(), s, null);
                    yield staticPlace(s);
                }
                yield null;
            }
            case Expr.Path p -> {
                var target = exprs.pathTarget(p, true);
                if (target instanceof Exprs.StaticTarget(StaticInfo s)) yield staticPlace(s);
                yield null;
            }
            case Expr.Self s -> {
                if (ctx.self == null) yield null;
                yield new PlaceInfo(new Ir.LocalPlace(ctx.self), typeOf(ctx.self), ctx.self.isMutable(),
                        "cannot assign through `self` in a method declared `self`; declare it `mut self`", ctx.self);
            }
            case Expr.Member m -> exprs.fieldPlace(m);
            case Expr.Index i -> exprs.indexPlace(i);
            case Expr.Slice sl -> {
                diag.error(Code.NOT_A_PLACE, sl.span(), "a slice is a copy, not a place: cannot assign to it (§7.6)");
                yield null;
            }
            default -> null;
        };
    }

    private PlaceInfo staticPlace(StaticInfo s) {
        return new PlaceInfo(new Ir.StaticPlace(s), s.type, s.isMutable(),
                "cannot assign to immutable static '" + s.name() + "'; declare it `static let mut`", null);
    }

    // ---- Coercion ----

    /** {@code e} checked against {@code target} (§3.5), with implicit conversions. */
    Ir.Expr check(Expr e, Type target) {
        return coerce(exprs.expr(e, target), target, e.span());
    }

    Ir.Expr coerce(Typed t, Type target, Span span) {
        if (t.type() == Type.ERROR || target == Type.ERROR) return t.ir();
        if (!ts.assignable(t.type(), target)) {
            var found = TypeSystem.members(target).stream().anyMatch(m -> m instanceof Type.Lit) ? t.type() : TypeSystem.widen(t.type());
            diag.error(Code.TYPE_MISMATCH, span, "expected '" + target + "', found '" + found + "'");
            return t.ir();
        }
        if (t.type() == Type.NEVER || TypeSystem.widen(t.type()).equals(target)) return t.ir();
        return new Ir.Coerce(t.ir(), target);
    }

    boolean inUnsafe() {
        return ctx.unsafeDepth > 0;
    }

    /** {@code inner} as a lambda body context, sharing locals with the enclosing one (captures). */
    Ctx lambdaCtx(Type returnType) {
        var c = new Ctx(ctx.module, ctx.typeScope, ctx.enclosing, ctx.fn, ctx.depth + 1);
        c.returnType = returnType;
        c.scope = new Scope(ctx.scope);
        c.narrowed = new HashMap<>(ctx.narrowed);
        c.self = ctx.self;
        c.unsafeDepth = 0;
        return c;
    }

    <T> T inCtx(Ctx c, java.util.function.Supplier<T> work) {
        return in(c, work);
    }

}
