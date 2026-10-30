package dev.threebolt.types;

import dev.threebolt.ast.Expr;
import dev.threebolt.ast.Stmt;
import dev.threebolt.diag.Code;
import dev.threebolt.diag.Diagnostics;
import dev.threebolt.diag.Span;
import dev.threebolt.ir.Ir;
import dev.threebolt.resolve.ModulePath;
import dev.threebolt.types.Checker.Typed;
import java.util.ArrayList;
import java.util.List;

/** `select` (§7.14): its case forms, bindings and type. */
final class Selects {
    private static final String FORMS = "a select case is `let p = ch.recv()`, `let p = ch.send(v)`, `let p = t.join()`, `after(d)` or `_`";

    private final Checker c;
    private final TypeSystem ts;
    private final Diagnostics diag;

    Selects(Checker c) {
        this.c = c;
        this.ts = c.ts;
        this.diag = c.diag;
    }

    /** The declaration {@code name} of {@code std::module}, or null if that module is not loaded. */
    private Object std(String module, String name) {
        var m = c.program.module(ModulePath.of("std", module));
        if (m == null) return null;
        var decls = m.scope().items(name);
        return decls.isEmpty() ? null : c.names.info(decls.getFirst().item());
    }

    private Type stdType(String module, String name) {
        return std(module, name) instanceof TypeInfo t ? new Type.Nominal(t, List.of()) : Type.ERROR;
    }

    /** A call of {@code fn} (static, without arguments) as IR. */
    private static Ir.Expr call(FnInfo fn, Span span) {
        return new Ir.Call(fn, Ir.CallKind.DIRECT, null, null, List.of(), new int[0], List.of(), List.of(), null, span);
    }

    Typed select(Expr.Select s, Type expected) {
        var ctx = c.ctx;
        var cases = new ArrayList<Ir.SelectCase>();
        var armTypes = new ArrayList<Type>();
        var saved = ctx.narrowed;
        boolean anyReach = false;
        int defaults = 0;
        var closedErr = stdType("io", "ClosedErr");
        var cancelledErr = stdType("thread", "CancelledErr");
        for (int i = 0; i < s.arms().size(); i++) {
            var arm = s.arms().get(i);
            var outer = ctx.scope;
            ctx.scope = new Checker.Scope(outer);
            ctx.narrowed = saved;
            Ir.SelectKind kind;
            Ir.Expr target = null, value = null;
            Ir.Pat pattern = null;
            if (arm.op() == null) {
                kind = Ir.SelectKind.DEFAULT;
                defaults++;
                if (defaults > 1) diag.error(Code.BAD_SELECT, arm.span(), "a select has at most one `_` arm");
                else if (i != s.arms().size() - 1) diag.error(Code.BAD_SELECT, arm.span(), "the `_` arm must be the last");
            } else if (arm.pattern() == null) {
                kind = Ir.SelectKind.AFTER;
                if (arm.op() instanceof Expr.Call call && call.callee() instanceof Expr.Path p && p.segments().size() == 1
                        && p.segments().getFirst().name().text().equals("after") && call.args().size() == 1
                        && call.args().getFirst().name() == null) {
                    target = c.check(call.args().getFirst().value(), stdType("time", "Duration"));
                } else {
                    diag.error(Code.BAD_SELECT, arm.op().span(), FORMS);
                    c.exprs.expr(arm.op(), null);
                }
            } else {
                var op = operation(arm.op(), closedErr);
                kind = op.kind;
                target = op.target;
                value = op.value;
                pattern = c.patterns.irrefutable(arm.pattern(), op.binds);
            }
            ctx.reachable = true;
            Ir.Stmt body = null;
            Ir.Expr result = null;
            switch (arm.body()) {
                case Expr e -> {
                    var t = c.exprs.expr(e, expected);
                    result = t.ir();
                    if (t.type() == Type.NEVER) ctx.reachable = false;
                    else armTypes.add(t.type());
                }
                case Stmt.Block b -> {
                    body = c.block(b);
                    if (ctx.reachable) armTypes.add(Type.UNIT);
                }
                case Stmt.Assign a -> {
                    body = c.assign(a);
                    armTypes.add(Type.UNIT);
                }
            }
            anyReach |= ctx.reachable;
            ctx.scope = outer;
            cases.add(new Ir.SelectCase(kind, target, value, pattern, body, result));
        }
        ctx.narrowed = saved;
        if (s.arms().size() == defaults) diag.error(Code.BAD_SELECT, s.span(), "a select needs a case besides `_`");
        // Waiting is a cancellation point, so a select can always yield `CancelledErr`.
        ctx.reachable = true;
        var types = new ArrayList<Type>(expected == null ? armTypes.stream().map(TypeSystem::widenInferred).toList() : armTypes);
        types.add(cancelledErr);
        var type = armTypes.contains(Type.ERROR) ? Type.ERROR : TypeSystem.union(types);
        var closed = std("sync", "closed") instanceof FnInfo f ? call(f, s.span()) : null;
        var cancelled = std("thread", "CancelledErr") instanceof TypeInfo t ? call(t.methods("new").getFirst(), s.span()) : null;
        return new Typed(new Ir.Select(cases, closed, cancelled, s.span()), type);
    }

    /** A checked `let p = e.op(...)` case: what {@code p} binds. */
    private record Operation(Ir.SelectKind kind, Ir.Expr target, Ir.Expr value, Type binds) {}

    private Operation operation(Expr op, Type closedErr) {
        if (!(op instanceof Expr.Call call && call.callee() instanceof Expr.Member m)) {
            diag.error(Code.BAD_SELECT, op.span(), FORMS);
            c.exprs.expr(op, null);
            return new Operation(Ir.SelectKind.RECV, null, null, Type.ERROR);
        }
        if (c.completer != null && c.completer.at(m.name())) {
            var t = c.exprs.expr(m.receiver(), null).type();
            if (t != Type.ERROR) c.completer.members(t, m.name());
            for (var a : call.args()) c.exprs.expr(a.value(), null);
            return new Operation(Ir.SelectKind.RECV, null, null, Type.ERROR);
        }
        String name = m.name().text();
        int arity = name.equals("send") ? 1 : 0;
        if (!List.of("recv", "send", "join").contains(name) || call.args().size() != arity
                || (arity == 1 && call.args().getFirst().name() != null)) {
            diag.error(Code.BAD_SELECT, op.span(), FORMS);
            c.exprs.expr(m.receiver(), null);
            for (var a : call.args()) c.exprs.expr(a.value(), null);
            return new Operation(Ir.SelectKind.RECV, null, null, Type.ERROR);
        }
        var recv = c.exprs.expr(m.receiver(), null);
        var t = recv.type();
        if (t == Type.ERROR) {
            for (var a : call.args()) c.exprs.expr(a.value(), null);
            return new Operation(Ir.SelectKind.RECV, null, null, Type.ERROR);
        }
        switch (name) {
            case "recv" -> {
                var receiver = std("sync", "Receiver") instanceof TypeInfo r ? ts.implementsAs(t, r) : null;
                if (receiver == null) return wrong(m.receiver().span(), t, "`recv`", "a `std::sync::Receiver`");
                var item = receiver.args().getFirst();
                return new Operation(Ir.SelectKind.RECV, recv.ir(), null, TypeSystem.union(List.of(item, closedErr)));
            }
            case "send" -> {
                var sender = std("sync", "Sender") instanceof TypeInfo r ? ts.implementsAs(t, r) : null;
                if (sender == null) {
                    c.exprs.expr(call.args().getFirst().value(), null);
                    return wrong(m.receiver().span(), t, "`send`", "a `std::sync::Sender`");
                }
                var v = c.check(call.args().getFirst().value(), sender.args().getFirst());
                return new Operation(Ir.SelectKind.SEND, recv.ir(), v, TypeSystem.union(List.of(Type.UNIT, closedErr)));
            }
            default -> {
                var thread = std("thread", "Thread");
                if (!(TypeSystem.expand(t) instanceof Type.Nominal n && n.decl() == thread)) {
                    return wrong(m.receiver().span(), t, "`join`", "a `std::thread::Thread`");
                }
                return new Operation(Ir.SelectKind.JOIN, recv.ir(), null, n.args().getFirst());
            }
        }
    }

    private Operation wrong(Span span, Type t, String op, String needed) {
        diag.error(Code.BAD_SELECT, span, op + " in a select needs " + needed + ", not '" + t + "'");
        return new Operation(Ir.SelectKind.RECV, null, null, Type.ERROR);
    }
}
