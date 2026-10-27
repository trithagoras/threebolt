package dev.threebolt.interp;

import dev.threebolt.diag.Span;
import dev.threebolt.interp.Values.ArrayVal;
import dev.threebolt.interp.Values.Box;
import dev.threebolt.interp.Values.Cell;
import dev.threebolt.interp.Values.Char;
import dev.threebolt.interp.Values.EnumVal;
import dev.threebolt.interp.Values.Env;
import dev.threebolt.interp.Values.Flt;
import dev.threebolt.interp.Values.FnVal;
import dev.threebolt.interp.Values.Int;
import dev.threebolt.interp.Values.IterVal;
import dev.threebolt.interp.Values.Key;
import dev.threebolt.interp.Values.ListVal;
import dev.threebolt.interp.Values.MapVal;
import dev.threebolt.interp.Values.Obj;
import dev.threebolt.interp.Values.PtrVal;
import dev.threebolt.interp.Values.SetVal;
import dev.threebolt.interp.Values.TupleVal;
import dev.threebolt.interp.Values.TypeVal;
import dev.threebolt.interp.Values.Unit;
import dev.threebolt.ir.Ir;
import dev.threebolt.ir.Local;
import dev.threebolt.types.CheckedProgram;
import dev.threebolt.types.FieldInfo;
import dev.threebolt.types.FnInfo;
import dev.threebolt.types.ImplInfo;
import dev.threebolt.types.Prim;
import dev.threebolt.types.StaticInfo;
import dev.threebolt.types.Type;
import dev.threebolt.types.TypeInfo;
import dev.threebolt.types.TypeParamInfo;
import dev.threebolt.types.TypeSystem;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Runs a {@link CheckedProgram}: a tree-walking evaluator over {@link Ir}. */
public final class Interpreter {
    final TypeSystem ts;
    private final CheckedProgram program;
    final PrintStream out;
    final PrintStream err;
    final Natives natives;
    final Ffi ffi = new Ffi(this);
    /** The `Done` sentinel (§16). */
    final Obj done;

    private enum StaticState { RUNNING, DONE }

    private final Map<StaticInfo, Object> staticValues = new HashMap<>();
    private final Map<StaticInfo, StaticState> staticStates = new HashMap<>();
    private final ArrayDeque<Frame> stack = new ArrayDeque<>();
    private final Map<TypeInfo, Map<FnInfo, FnInfo>> virtualCache = new HashMap<>();

    public Interpreter(CheckedProgram program, PrintStream out, PrintStream err) {
        this.program = program;
        this.ts = program.ts();
        this.out = out;
        this.err = err;
        this.natives = new Natives(this);
        this.done = new Obj(ts.done, List.of(), new Object[0]);
    }

    /** One function invocation. */
    static final class Frame {
        final String name;
        final Env env;
        final Map<TypeParamInfo, Type> typeEnv;
        Object hole;
        Span at;

        Frame(String name, Env env, Map<TypeParamInfo, Type> typeEnv) {
            this.name = name;
            this.env = env;
            this.typeEnv = typeEnv;
        }
    }

    // ---- Control flow ----

    private static final class Signal extends RuntimeException {
        Signal() {
            super(null, null, false, false);
        }
    }

    private static final class ReturnSignal extends RuntimeException {
        final Object value;

        ReturnSignal(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    private static final Signal BREAK = new Signal();
    private static final Signal CONTINUE = new Signal();

    /** {@code std::process::exit}: leaves immediately, no defers (§2.4). */
    static final class ExitSignal extends RuntimeException {
        final int code;

        ExitSignal(int code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    // ---- Entry (§2.4) ----

    /** Runs `main`; returns the process exit code. */
    public int run(List<String> args) {
        var main = program.main();
        try {
            var list = new ListVal(Prim.STR, new ArrayList<>(args));
            Object[] a = main.params.isEmpty() ? new Object[0] : new Object[] {list};
            var result = invoke(main, null, a, List.of(), null, null, null);
            if (result != Unit.UNIT && result != null) {
                var message = callByName(result, ts.error, "message");
                err.println(message);
                return 1;
            }
            return 0;
        } catch (ExitSignal e) {
            return e.code;
        } catch (Panic p) {
            err.println("panic: " + p.getMessage());
            for (String line : p.trace()) err.println("    at " + line);
            return 101;
        } finally {
            out.flush();
            err.flush();
        }
    }

    // ---- Calls ----

    /**
     * Invokes {@code fn}. {@code ownerArgs} binds the owner's type parameters for static calls;
     * {@code staticSelf} is the type a static trait function was dispatched on.
     */
    Object invoke(FnInfo fn, Object self, Object[] args, List<Type> typeArgs, List<Type> ownerArgs, Type staticSelf,
                  Span at) {
        var typeEnv = new HashMap<TypeParamInfo, Type>();
        for (int i = 0; i < fn.typeParams.size() && i < typeArgs.size(); i++) typeEnv.put(fn.typeParams.get(i), typeArgs.get(i));
        if (self != null) ownerBindings(fn, self, typeEnv);
        else if (ownerArgs != null && fn.owner instanceof TypeInfo owner) typeEnv.putAll(TypeSystem.bind(owner.typeParams, ownerArgs));
        if (staticSelf != null) staticBindings(fn, staticSelf, typeEnv);
        if (fn.intrinsic != null) return natives.call(fn, self, args, typeEnv);
        if (fn.isExtern) return ffi.call(fn, args);
        if (fn.body == null) throw new Panic("called abstract method '" + fn.qualifiedName() + "'");
        var env = new Env(null);
        if (fn.selfLocal != null) env.define(fn.selfLocal, self);
        for (int i = 0; i < fn.paramLocals.size(); i++) env.define(fn.paramLocals.get(i), args[i]);
        var frame = new Frame(fn.qualifiedName(), env, typeEnv);
        frame.at = fn.span();
        return runBody(frame, () -> {
            execBlock(fn.body, frame);
            return Unit.UNIT;
        });
    }

    private Object runBody(Frame frame, java.util.function.Supplier<Object> body) {
        if (stack.size() > 20_000) throw new Panic("stack overflow");
        stack.push(frame);
        try {
            return body.get();
        } catch (ReturnSignal r) {
            return r.value;
        } catch (Panic p) {
            if (p.trace() == null) p.setTrace(trace());
            throw p;
        } catch (StackOverflowError e) {
            var p = new Panic("stack overflow");
            p.setTrace(trace());
            throw p;
        } finally {
            stack.pop();
        }
    }

    private List<String> trace() {
        var lines = new ArrayList<String>();
        for (Frame f : stack) lines.add(f.name + " (" + f.at + ")");
        return lines;
    }

    /** Binds the type parameters of {@code fn}'s owner from the receiver's runtime type (§10, reified). */
    private void ownerBindings(FnInfo fn, Object self, Map<TypeParamInfo, Type> env) {
        var rt = typeOf(unbox(self));
        switch (fn.owner) {
            case TypeInfo o when o.isTrait() -> {
                env.put(o.selfParam, rt);
                var inst = ts.implementsAs(rt, o);
                if (inst != null) env.putAll(TypeSystem.bind(o.typeParams, inst.args()));
            }
            case TypeInfo o when !o.typeParams.isEmpty() -> {
                if (rt instanceof Type.Nominal n) {
                    var base = TypeSystem.baseInstance(n, o);
                    if (base != null) env.putAll(TypeSystem.bind(o.typeParams, base.args()));
                } else if (rt instanceof Type.Array a) {
                    env.put(o.typeParams.getFirst(), a.element());
                }
            }
            case ImplInfo impl -> {
                Type target = rt;
                if (rt instanceof Type.Nominal n && n.decl() != impl.targetDecl) {
                    var base = TypeSystem.baseInstance(n, impl.targetDecl);
                    if (base != null) target = base;
                }
                var m = ts.matchImpl(impl, target);
                if (m != null) env.putAll(m);
            }
            default -> {}
        }
    }

    private void staticBindings(FnInfo fn, Type self, Map<TypeParamInfo, Type> env) {
        switch (fn.owner) {
            case ImplInfo impl -> {
                var m = ts.matchImpl(impl, self);
                if (m != null) env.putAll(m);
            }
            case TypeInfo o when o.isTrait() -> env.put(o.selfParam, self);
            case TypeInfo o when self instanceof Type.Nominal n && n.decl() == o -> env.putAll(TypeSystem.bind(o.typeParams, n.args()));
            default -> {}
        }
    }

    private Object call(Ir.Call c, Frame f) {
        f.at = c.span();
        if (c.recvPlace() != null) {
            var result = new Object[1];
            store(c.recvPlace(), f, true, recv -> {
                var args = args(c, f);
                f.at = c.span();
                result[0] = dispatch(c, recv, args, f);
                return recv;
            });
            return result[0];
        }
        Object recv = c.recv() == null ? null : evalRef(c.recv(), f);
        var args = args(c, f);
        f.at = c.span();
        return dispatch(c, recv, args, f);
    }

    /** Arguments in parameter order: given ones in source order, then defaults (§4.3, §7.13). */
    private Object[] args(Ir.Call c, Frame f) {
        var fn = c.fn();
        var values = new Object[c.args().size()];
        for (int i : c.order()) values[i] = eval(c.args().get(i), f);
        for (int i = 0; i < values.length; i++) {
            if (c.args().get(i) != null) continue;
            var def = i < fn.defaults.size() ? fn.defaults.get(i) : null;
            if (def == null) throw new IllegalStateException("no default for parameter " + i + " of " + fn);
            // Defaults run in the callee's declaration scope and see earlier parameters.
            var env = new Env(null);
            for (int j = 0; j < i && j < fn.paramLocals.size(); j++) env.define(fn.paramLocals.get(j), values[j]);
            var typeEnv = new HashMap<>(f.typeEnv);
            var typeArgs = substAll(c.typeArgs(), f);
            for (int j = 0; j < fn.typeParams.size() && j < typeArgs.size(); j++) typeEnv.put(fn.typeParams.get(j), typeArgs.get(j));
            values[i] = eval(def, new Frame(fn.qualifiedName(), env, typeEnv));
        }
        return values;
    }

    private Object dispatch(Ir.Call c, Object recv, Object[] args, Frame f) {
        var typeArgs = substAll(c.typeArgs(), f);
        var ownerArgs = c.ownerArgs() == null ? null : substAll(c.ownerArgs(), f);
        return switch (c.kind()) {
            case DIRECT -> invoke(c.fn(), recv, args, typeArgs, ownerArgs, null, c.span());
            case VIRTUAL -> invoke(virtualTarget(c.fn(), recv), recv, args, typeArgs, ownerArgs, null, c.span());
            case TRAIT -> callTrait(c.fn(), recv, args, typeArgs);
            case TRAIT_STATIC -> {
                var self = subst(c.staticSelf(), f);
                var target = staticTraitTarget(c.fn(), self);
                yield invoke(target, null, args, typeArgs, null, self, c.span());
            }
        };
    }

    /** The most-derived override of {@code fn} for the receiver's runtime class (§8.2). */
    FnInfo virtualTarget(FnInfo fn, Object recv) {
        if (!(unbox(recv) instanceof Obj o)) return fn;
        var cache = virtualCache.computeIfAbsent(o.decl, k -> new HashMap<>());
        return cache.computeIfAbsent(fn, k -> {
            var root = fn.root();
            for (TypeInfo cls = o.decl; cls != null; cls = cls.baseDecl()) {
                for (FnInfo m : cls.methods(fn.name())) if (!m.isStatic() && m.root() == root) return m;
            }
            return fn;
        });
    }

    /** Calls trait member {@code fn} on {@code recv}, through the implementation for its runtime type (§8.3). */
    Object callTrait(FnInfo fn, Object recv, Object[] args, List<Type> typeArgs) {
        var target = traitTarget(fn, recv);
        if (target == null) return builtinTraitCall(fn, recv, args);
        return invoke(target, recv, args, typeArgs, null, null, null);
    }

    /** Calls the trait member named {@code name} of {@code trait} on {@code recv}. */
    Object callByName(Object recv, TypeInfo trait, String name, Object... args) {
        return callTrait(trait.methods(name).getFirst(), recv, args, List.of());
    }

    private FnInfo traitTarget(FnInfo fn, Object recv) {
        var trait = (TypeInfo) fn.owner;
        var v = unbox(recv);
        if (v instanceof ArrayVal) {
            var m = ts.list.methods(fn.name());
            return m.isEmpty() ? null : m.getFirst();
        }
        for (TypeInfo d = declOf(v); d != null; d = d.baseDecl()) {
            var impl = ts.impl(trait, d);
            if (impl == null) continue;
            var fns = impl.methods.get(fn.name());
            if (fns == null || fns.isEmpty()) return fn.hasBody() ? fn : null;
            FnInfo m = fns.getFirst();
            for (FnInfo g : fns) if (g.params.size() == fn.params.size()) m = g;
            return m.isVirtual() ? virtualTarget(m, recv) : m;
        }
        return fn.hasBody() ? fn : null;
    }

    private FnInfo staticTraitTarget(FnInfo fn, Type self) {
        var trait = (TypeInfo) fn.owner;
        TypeInfo decl = switch (TypeSystem.expand(self)) {
            case Type.Nominal n -> n.decl();
            case Prim p -> ts.prims.get(p);
            default -> null;
        };
        for (TypeInfo d = decl; d != null; d = d.baseDecl()) {
            var impl = ts.impl(trait, d);
            if (impl == null) continue;
            var fns = impl.methods.get(fn.name());
            if (fns != null && !fns.isEmpty()) return fns.getFirst();
        }
        if (fn.hasBody()) return fn;
        throw new Panic("'" + self + "' does not implement '" + fn.qualifiedName() + "'");
    }

    /** Automatic `Eq`/`Hash` (structs, tuples, unions) and other members without an impl. */
    private Object builtinTraitCall(FnInfo fn, Object recv, Object[] args) {
        return switch (fn.name()) {
            case "eq" -> valueEquals(recv, args[0]);
            case "hash" -> new Int(Prim.U64, hashOf(recv));
            case "to_str" -> display(recv);
            case "cmp" -> new Int(Prim.I32, compare(recv, args[0]));
            default -> throw new Panic("'" + typeOf(recv) + "' does not implement '" + fn.qualifiedName() + "'");
        };
    }

    TypeInfo declOf(Object v) {
        return switch (v) {
            case Obj o -> o.decl;
            case ListVal l -> ts.list;
            case MapVal m -> ts.map;
            case SetVal s -> ts.set;
            case TypeVal t -> ts.typeDecl;
            case IterVal i -> ts.nativeIter;
            case PtrVal p -> ts.ptr;
            case EnumVal e -> e.decl();
            case Int i -> ts.prims.get(i.kind());
            case Flt fl -> ts.prims.get(fl.kind());
            case Char ch -> ts.prims.get(Prim.CHAR);
            case Boolean b -> ts.prims.get(Prim.BOOL);
            case String s -> ts.prims.get(Prim.STR);
            case null, default -> null;
        };
    }

    /** Calls a function value. */
    Object callFn(FnVal fn, Object[] args) {
        if (fn.fn != null) return invoke(fn.fn, null, args, fn.typeArgs, null, null, null);
        var lambda = fn.lambda;
        var env = new Env(fn.env);
        for (int i = 0; i < lambda.params().size(); i++) env.define(lambda.params().get(i), args[i]);
        var frame = new Frame("<lambda>", env, fn.typeEnv);
        return runBody(frame, () -> {
            if (lambda.value() != null) return eval(lambda.value(), frame);
            exec(lambda.body(), frame);
            return Unit.UNIT;
        });
    }

    // ---- Types at runtime ----

    Type subst(Type t, Frame f) {
        return f.typeEnv.isEmpty() ? t : TypeSystem.subst(t, f.typeEnv);
    }

    private List<Type> substAll(List<Type> ts, Frame f) {
        return f.typeEnv.isEmpty() ? ts : TypeSystem.substAll(ts, f.typeEnv);
    }

    static Object unbox(Object v) {
        return v instanceof Box b ? b.value : v;
    }

    /** The runtime type of a value (§15). */
    Type typeOf(Object v) {
        return switch (v) {
            case null -> Type.NULL;
            case Boolean b -> Prim.BOOL;
            case String s -> Prim.STR;
            case Int i -> i.kind();
            case Flt fl -> fl.kind();
            case Char ch -> Prim.CHAR;
            case Unit u -> Type.UNIT;
            case Obj o -> new Type.Nominal(o.decl, o.args);
            case Box b -> typeOf(b.value);
            case EnumVal e -> new Type.Nominal(e.decl(), List.of());
            case TupleVal t -> {
                var elems = new ArrayList<Type>();
                for (Object x : t.items) elems.add(typeOf(x));
                yield new Type.Tuple(elems);
            }
            case ListVal l -> ts.listOf(l.element);
            case ArrayVal a -> new Type.Array(a.element, a.items.length);
            case MapVal m -> new Type.Nominal(ts.map, List.of(m.key, m.value));
            case SetVal s -> new Type.Nominal(ts.set, List.of(s.element));
            case TypeVal t -> new Type.Nominal(ts.typeDecl, List.of());
            case IterVal i -> new Type.Nominal(ts.nativeIter, List.of(i.element));
            case PtrVal p -> new Type.Nominal(ts.ptr, List.of(p.target()));
            case FnVal fn -> fn.type;
            default -> throw new IllegalStateException("type of " + v);
        };
    }

    /** {@code x is T} at runtime (§7.3); {@code t} has no type parameters left. */
    boolean isInstance(Object v, Type t) {
        t = TypeSystem.expand(t);
        switch (t) {
            case Type.Union u -> {
                for (Type m : u.members()) if (isInstance(v, m)) return true;
                return false;
            }
            case Type.Special s -> {
                return switch (s) {
                    case NULL -> v == null;
                    case UNIT -> v == Unit.UNIT;
                    case NEVER -> false;
                    case ERROR -> true;
                };
            }
            case Type.Lit l -> {
                return switch (v) {
                    case Int i -> l.value() instanceof java.math.BigInteger b && i.big().equals(b);
                    case String s -> s.equals(l.value());
                    case Boolean b -> b.equals(l.value());
                    case Char ch -> l.value() instanceof Integer cp && ch.cp() == cp;
                    case null, default -> false;
                };
            }
            case Prim p -> {
                return switch (v) {
                    case Int i -> i.kind() == p;
                    case Flt fl -> fl.kind() == p;
                    case Char ch -> p == Prim.CHAR;
                    case Boolean b -> p == Prim.BOOL;
                    case String s -> p == Prim.STR;
                    case null, default -> false;
                };
            }
            case Type.Nominal n -> {
                if (v == null) return false;
                var rt = typeOf(unbox(v));
                if (n.decl().isTrait()) {
                    var inst = ts.implementsAs(rt, n.decl());
                    return inst != null && inst.equals(n);
                }
                if (n.decl().isClass() && rt instanceof Type.Nominal rn && rn.decl().isClass()) {
                    var base = TypeSystem.baseInstance(rn, n.decl());
                    return base != null && base.equals(n);
                }
                return rt.equals(n);
            }
            case Type.Tuple tu -> {
                if (!(v instanceof TupleVal tv) || tv.items.length != tu.elements().size()) return false;
                for (int i = 0; i < tv.items.length; i++) if (!isInstance(tv.items[i], tu.elements().get(i))) return false;
                return true;
            }
            case Type.Array a -> {
                return v instanceof ArrayVal av && av.items.length == a.size() && av.element.equals(a.element());
            }
            case Type.Fn fn -> {
                return v instanceof FnVal fv && fv.type.params().size() == fn.params().size();
            }
            default -> {
                return true;
            }
        }
    }

    /** Implicit conversion (§3.5): numeric widening and boxing for trait types. */
    Object coerce(Object v, Type t) {
        t = TypeSystem.expand(t);
        if (t instanceof Type.Union u) {
            for (Type m : u.members()) if (!TypeSystem.isTraitRef(m) && isInstance(v, m)) return v;
            for (Type m : u.members()) {
                if (m instanceof Prim p && p.isNumeric() && (v instanceof Int || v instanceof Flt)) {
                    var from = (Prim) typeOf(v);
                    if (from.widensTo(p)) return Ops.cast(v, p);
                }
            }
            for (Type m : u.members()) if (TypeSystem.isTraitRef(m) && isInstance(v, m)) return box(v);
            return v;
        }
        if (t instanceof Prim p && p.isNumeric() && (v instanceof Int || v instanceof Flt) && typeOf(v) != p) {
            return Ops.cast(v, p);
        }
        if (TypeSystem.isTraitRef(t)) return box(v);
        return v;
    }

    static Object box(Object v) {
        return v instanceof Obj o && o.decl.isStruct() ? new Box(o) : v;
    }

    private Object cast(Object v, Type t, boolean checked) {
        t = TypeSystem.expand(t);
        var p = TypeSystem.prim(t);
        if (p != null && !(t instanceof Type.Lit) && (v instanceof Int || v instanceof Flt || v instanceof Char || v instanceof Boolean)
                && (p.isNumeric() || p == Prim.CHAR)) {
            var r = Ops.cast(v, p);
            if (r == null && !checked) throw new Panic("cannot cast " + display(v) + " to char: not a Unicode scalar value");
            return r;
        }
        if (v instanceof EnumVal e && p != null && p.isInteger()) return Ops.wrap(p, e.v());
        if (v instanceof Int i && t instanceof Type.Nominal n && n.decl().isEnum()) {
            var raw = Ops.wrap(n.decl().backing, i.v());
            if (!n.decl().isFlags && !n.decl().enumMembers.containsValue(raw.v())) {
                if (checked) return null;
                throw new Panic(i + " is not a value of enum '" + n.decl().name() + "'");
            }
            return new EnumVal(n.decl(), raw.v());
        }
        if (isInstance(v, t)) {
            if (v instanceof Box b && (TypeSystem.isStruct(t) || t instanceof Type.Union)) {
                return TypeSystem.isTraitRef(t) ? v : Values.copy(b.value);
            }
            return TypeSystem.isTraitRef(t) ? box(v) : v;
        }
        if (checked) return null;
        throw new Panic("cannot cast a value of type '" + TypeSystem.name(typeOf(v)) + "' to '" + TypeSystem.name(t) + "'");
    }

    // ---- Equality, hashing, ordering, display ----

    /** `==` (§7.3): runtime type then value; structs field-wise unless `Eq` is implemented; classes by identity. */
    boolean valueEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Box || b instanceof Box) {
            if (a instanceof Box && b instanceof Box) return false;
            return equalValues(unbox(a), unbox(b));
        }
        return equalValues(a, b);
    }

    private boolean equalValues(Object a, Object b) {
        switch (a) {
            case Obj o when b instanceof Obj p -> {
                if (o.decl != p.decl && !(o.decl.isClass() && p.decl.isClass())) return false;
                var eq = userImpl(o.decl, ts.eq);
                if (eq != null) return (Boolean) invoke(eq.methods.get("eq").getFirst(), a, new Object[] {b}, List.of(), null, null, null);
                if (o.decl.isClass()) return false;
                for (int i = 0; i < o.fields.length; i++) if (!valueEquals(o.fields[i], p.fields[i])) return false;
                return true;
            }
            case TupleVal t when b instanceof TupleVal u -> {
                if (t.items.length != u.items.length) return false;
                for (int i = 0; i < t.items.length; i++) if (!valueEquals(t.items[i], u.items[i])) return false;
                return true;
            }
            case ArrayVal t when b instanceof ArrayVal u -> {
                if (t.items.length != u.items.length) return false;
                for (int i = 0; i < t.items.length; i++) if (!valueEquals(t.items[i], u.items[i])) return false;
                return true;
            }
            case Flt x when b instanceof Flt y -> {
                return x.kind() == y.kind() && x.v() == y.v();
            }
            case ListVal l -> {
                return false;
            }
            case MapVal m -> {
                return false;
            }
            case SetVal s -> {
                return false;
            }
            case FnVal fn -> {
                return false;
            }
            default -> {
                return a.equals(b);
            }
        }
    }

    /** An explicit (non-builtin) impl of {@code trait} for {@code decl} or a base class. */
    private ImplInfo userImpl(TypeInfo decl, TypeInfo trait) {
        for (TypeInfo d = decl; d != null; d = d.baseDecl()) {
            var impl = ts.impl(trait, d);
            if (impl != null && impl.methods.containsKey(trait == ts.eq ? "eq" : trait == ts.hash ? "hash" : "cmp")) return impl;
        }
        return null;
    }

    int hashOf(Object v) {
        v = unbox(v);
        return switch (v) {
            case null -> 0;
            case Obj o -> {
                var h = userImpl(o.decl, ts.hash);
                if (h != null) {
                    var r = (Int) invoke(h.methods.get("hash").getFirst(), o, new Object[0], List.of(), null, null, null);
                    yield Long.hashCode(r.v());
                }
                if (o.decl.isClass()) yield System.identityHashCode(o);
                int hash = o.decl.hashCode();
                for (Object f : o.fields) hash = hash * 31 + hashOf(f);
                yield hash;
            }
            case TupleVal t -> {
                int hash = 7;
                for (Object x : t.items) hash = hash * 31 + hashOf(x);
                yield hash;
            }
            case ArrayVal a -> {
                int hash = 11;
                for (Object x : a.items) hash = hash * 31 + hashOf(x);
                yield hash;
            }
            case ListVal l -> System.identityHashCode(l);
            case MapVal m -> System.identityHashCode(m);
            case SetVal s -> System.identityHashCode(s);
            case FnVal f -> System.identityHashCode(f);
            default -> v.hashCode();
        };
    }

    /** {@code Ord} (§7.3): primitives, enums by value, otherwise {@code cmp}. */
    int compare(Object a, Object b) {
        a = unbox(a);
        b = unbox(b);
        if (a instanceof EnumVal x && b instanceof EnumVal y) return Long.compare(x.v(), y.v());
        if (a instanceof Obj o) {
            var r = (Int) callByName(a, ts.ord, "cmp", b);
            return Long.signum(r.v());
        }
        return Ops.comparePrim(a, b);
    }

    /** {@code Display} (§16), as used by f-strings. */
    String display(Object v) {
        return switch (v) {
            case String s -> s;
            case Int i -> i.toString();
            case Flt fl -> Ops.display(fl);
            case Char ch -> Ops.display(ch);
            case Boolean b -> b.toString();
            case EnumVal e -> enumDisplay(e);
            case TypeVal t -> TypeSystem.name(t.type());
            case null -> "null";
            default -> (String) callByName(v, ts.display, "to_str");
        };
    }

    /** Member name; flags combinations as {@code A | B}; undeclared bits as the integer (§4.11). */
    String enumDisplay(EnumVal e) {
        var members = e.decl().enumMembers;
        for (var m : members.entrySet()) if (m.getValue() == e.v()) return m.getKey();
        if (!e.decl().isFlags) return Ops.wrap(e.decl().backing, e.v()).toString();
        var parts = new ArrayList<String>();
        long rest = e.v();
        for (var m : members.entrySet()) {
            long bits = m.getValue();
            if (bits != 0 && (rest & bits) == bits) {
                parts.add(m.getKey());
                rest &= ~bits;
            }
        }
        if (rest != 0) parts.add(Ops.wrap(e.decl().backing, rest).toString());
        return String.join(" | ", parts);
    }

    boolean truthy(Object v) {
        return switch (v) {
            case null -> false;
            case Boolean b -> b;
            case String s -> !s.isEmpty();
            case ListVal l -> !l.items.isEmpty();
            case MapVal m -> !m.entries.isEmpty();
            case SetVal s -> !s.entries.isEmpty();
            default -> (Boolean) callByName(v, ts.truthy, "truthy");
        };
    }

    boolean isDone(Object v) {
        return unbox(v) instanceof Obj o && o.decl == ts.done;
    }

    /** One step of an iterator: {@code Iterator::next} on a value used in place. */
    Object next(Object iterator) {
        if (iterator instanceof IterVal it) return it.next.get();
        return callByName(iterator, ts.iterator, "next");
    }

    // ---- Statics (§4.2) ----

    Object staticValue(StaticInfo s) {
        var state = staticStates.get(s);
        if (state == StaticState.DONE) return staticValues.get(s);
        if (state == StaticState.RUNNING) throw new Panic("initialization of static '" + s + "' depends on itself");
        staticStates.put(s, StaticState.RUNNING);
        var frame = new Frame("static " + s, new Env(null), Map.of());
        var v = runBody(frame, () -> eval(s.init, frame));
        staticValues.put(s, v);
        staticStates.put(s, StaticState.DONE);
        return v;
    }

    // ---- Places ----

    private Object read(Ir.Place p, Frame f) {
        return switch (p) {
            case Ir.LocalPlace l -> f.env.cell(l.local()).value;
            case Ir.StaticPlace s -> staticValue(s.info());
            case Ir.FieldPlace fp -> fields(fp.basePlace() != null ? read(fp.basePlace(), f) : evalRef(fp.baseExpr(), f))[fp.slot()];
            case Ir.IndexPlace ip -> {
                var container = ip.basePlace() != null ? read(ip.basePlace(), f) : evalRef(ip.baseExpr(), f);
                var index = eval(ip.index(), f);
                f.at = ip.span();
                yield indexGet(container, index);
            }
        };
    }

    private static Object[] fields(Object base) {
        return switch (base) {
            case Obj o -> o.fields;
            case Box b -> b.value.fields;
            case TupleVal t -> t.items;
            default -> throw new IllegalStateException("no fields in " + base);
        };
    }

    /**
     * Writes a place: {@code update} maps the old value (null unless {@code needOld}) to the new
     * one. Value-type bases are updated in place, through indexers by read-modify-write (§5.3).
     */
    private void store(Ir.Place p, Frame f, boolean needOld, UnaryOperator<Object> update) {
        switch (p) {
            case Ir.LocalPlace l -> {
                Cell cell = f.env.cell(l.local());
                cell.value = update.apply(needOld ? cell.value : null);
            }
            case Ir.StaticPlace s -> {
                var old = staticValue(s.info());
                staticValues.put(s.info(), update.apply(needOld ? old : null));
            }
            case Ir.FieldPlace fp -> {
                if (fp.basePlace() != null) {
                    store(fp.basePlace(), f, true, base -> {
                        var fs = fields(base);
                        fs[fp.slot()] = update.apply(needOld ? fs[fp.slot()] : null);
                        return base;
                    });
                } else {
                    var fs = fields(eval(fp.baseExpr(), f));
                    fs[fp.slot()] = update.apply(needOld ? fs[fp.slot()] : null);
                }
            }
            case Ir.IndexPlace ip -> {
                UnaryOperator<Object> inContainer = container -> {
                    var index = eval(ip.index(), f);
                    f.at = ip.span();
                    var old = needOld ? indexGet(container, index) : null;
                    var v = update.apply(old);
                    f.at = ip.span();
                    indexSet(container, index, v);
                    return container;
                };
                if (ip.basePlace() != null) store(ip.basePlace(), f, true, inContainer);
                else inContainer.apply(eval(ip.baseExpr(), f));
            }
        }
    }

    Object indexGet(Object container, Object index) {
        return switch (container) {
            case ListVal l -> Values.copy(l.items.get(checkIndex(index, l.items.size())));
            case ArrayVal a -> Values.copy(a.items[checkIndex(index, a.items.length)]);
            case MapVal m -> {
                var key = new Key(index, this);
                if (!m.entries.containsKey(key)) throw new Panic("key not found in map: " + debug(index));
                yield Values.copy(m.entries.get(key));
            }
            default -> callByName(container, ts.index, "index", index);
        };
    }

    void indexSet(Object container, Object index, Object value) {
        switch (container) {
            case ListVal l -> l.items.set(checkIndex(index, l.items.size()), value);
            case ArrayVal a -> a.items[checkIndex(index, a.items.length)] = value;
            case MapVal m -> m.entries.put(new Key(index, this), value);
            default -> callByName(container, ts.indexMut, "set", index, value);
        }
    }

    static int checkIndex(Object index, int size) {
        var i = (Int) index;
        if (i.v() < 0 || i.v() >= size) throw new Panic("index out of range: " + i + " >= " + size);
        return (int) i.v();
    }

    String debug(Object v) {
        if (v instanceof String s) return '"' + s + '"';
        try {
            return display(v);
        } catch (RuntimeException e) {
            return TypeSystem.name(typeOf(v));
        }
    }

    // ---- Statements ----

    void execBlock(Ir.Block b, Frame f) {
        List<Ir.Stmt> defers = null;
        try {
            for (Ir.Stmt s : b.stmts()) {
                if (s instanceof Ir.Defer d) {
                    if (defers == null) defers = new ArrayList<>();
                    defers.add(d.body());
                } else {
                    exec(s, f);
                }
            }
        } catch (Signal | ReturnSignal e) {
            runDefers(defers, f);
            throw e;
        }
        runDefers(defers, f);
    }

    /** Defers run in reverse order on every exit except panics and `exit` (§6.5). */
    private void runDefers(List<Ir.Stmt> defers, Frame f) {
        if (defers == null) return;
        for (int i = defers.size() - 1; i >= 0; i--) exec(defers.get(i), f);
    }

    void exec(Ir.Stmt s, Frame f) {
        switch (s) {
            case Ir.Let l -> bind(l.pattern(), eval(l.value(), f), f);
            case Ir.Assign a -> store(a.place(), f, false, old -> eval(a.value(), f));
            case Ir.Compound c -> store(c.place(), f, true, old -> {
                var saved = f.hole;
                f.hole = old;
                try {
                    return eval(c.combined(), f);
                } finally {
                    f.hole = saved;
                }
            });
            case Ir.Eval e -> eval(e.expr(), f);
            case Ir.If i -> {
                if ((Boolean) eval(i.condition(), f)) execBlock(i.then(), f);
                else if (i.otherwise() != null) exec(i.otherwise(), f);
            }
            case Ir.IfLet i -> {
                var v = eval(i.value(), f);
                if (v != null) {
                    f.env.define(i.local(), v);
                    execBlock(i.then(), f);
                } else if (i.otherwise() != null) {
                    exec(i.otherwise(), f);
                }
            }
            case Ir.While w -> {
                while ((Boolean) eval(w.condition(), f)) {
                    if (!loopBody(w.body(), f)) break;
                }
            }
            case Ir.WhileLet w -> {
                while (true) {
                    var v = eval(w.value(), f);
                    if (v == null) break;
                    f.env.define(w.local(), v);
                    if (!loopBody(w.body(), f)) break;
                }
            }
            case Ir.Loop l -> {
                while (true) if (!loopBody(l.body(), f)) break;
            }
            case Ir.For fr -> {
                var it = eval(fr.iterator(), f);
                while (true) {
                    var v = next(it);
                    if (isDone(v)) break;
                    bind(fr.pattern(), v, f);
                    if (!loopBody(fr.body(), f)) break;
                }
            }
            case Ir.Break b -> throw BREAK;
            case Ir.Continue c -> throw CONTINUE;
            case Ir.Return r -> throw new ReturnSignal(eval(r.value(), f));
            case Ir.Defer d -> throw new IllegalStateException("defer outside a block");
            case Ir.Block b -> execBlock(b, f);
        }
    }

    /** Runs one iteration; false on `break`. */
    private boolean loopBody(Ir.Block body, Frame f) {
        try {
            execBlock(body, f);
        } catch (Signal s) {
            return s != BREAK;
        }
        return true;
    }

    private void bind(Ir.Pat p, Object v, Frame f) {
        if (!match(p, v, f)) throw new Panic("pattern did not match");
    }

    // ---- Patterns (§11) ----

    boolean match(Ir.Pat p, Object v, Frame f) {
        return switch (p) {
            case Ir.PWild w -> true;
            case Ir.PBind b -> {
                if (b.sub() != null && !match(b.sub(), v, f)) yield false;
                f.env.define(b.local(), b.unbox() && v instanceof Box box ? Values.copy(box.value) : v);
                yield true;
            }
            case Ir.PLit l -> valueEquals(v, l.value());
            case Ir.PRange r -> {
                if (!(v instanceof Int || v instanceof Char) || v.getClass() != r.low().getClass()) yield false;
                if (v instanceof Int i && ((Int) r.low()).kind() != i.kind()) yield false;
                int lo = Ops.comparePrim(v, r.low());
                int hi = Ops.comparePrim(v, r.high());
                yield lo >= 0 && (r.isInclusive() ? hi <= 0 : hi < 0);
            }
            case Ir.PTuple t -> {
                if (!(v instanceof TupleVal tv) || tv.items.length != t.elements().size()) yield false;
                for (int i = 0; i < tv.items.length; i++) if (!match(t.elements().get(i), tv.items[i], f)) yield false;
                yield true;
            }
            case Ir.POr o -> {
                for (var alt : o.alternatives()) if (match(alt, v, f)) yield true;
                yield false;
            }
            case Ir.PList l -> {
                List<Object> items = switch (v) {
                    case ListVal lv -> lv.items;
                    case ArrayVal av -> java.util.Arrays.asList(av.items);
                    case null, default -> null;
                };
                if (items == null) yield false;
                int fixed = l.head().size() + l.tail().size();
                if (l.hasRest() ? items.size() < fixed : items.size() != fixed) yield false;
                for (int i = 0; i < l.head().size(); i++) if (!match(l.head().get(i), items.get(i), f)) yield false;
                int tailStart = items.size() - l.tail().size();
                for (int i = 0; i < l.tail().size(); i++) if (!match(l.tail().get(i), items.get(tailStart + i), f)) yield false;
                if (l.rest() != null) {
                    var rest = new ArrayList<Object>();
                    for (int i = l.head().size(); i < tailStart; i++) rest.add(Values.copy(items.get(i)));
                    f.env.define(l.rest(), new ListVal(subst(l.element(), f), rest));
                }
                yield true;
            }
            case Ir.PStruct s -> {
                if (!(unbox(v) instanceof Obj o) || !o.decl.isSubclassOf(s.decl())) yield false;
                for (int i = 0; i < s.fields().size(); i++) {
                    if (!match(s.patterns().get(i), o.fields[s.fields().get(i).slot], f)) yield false;
                }
                yield true;
            }
            case Ir.PIs is -> isInstance(v, subst(is.type(), f));
        };
    }

    // ---- Expressions ----

    /** Evaluates without copying a place's value (field bases and receivers). */
    private Object evalRef(Ir.Expr e, Frame f) {
        if (e instanceof Ir.Read r) return read(r.place(), f);
        return eval(e, f);
    }

    Object eval(Ir.Expr e, Frame f) {
        return switch (e) {
            case Ir.Lit l -> l.value();
            case Ir.Read r -> Values.copy(read(r.place(), f));
            case Ir.Hole h -> f.hole;
            case Ir.Call c -> call(c, f);
            case Ir.CallValue c -> {
                var fn = eval(c.fn(), f);
                var args = new Object[c.args().size()];
                for (int i = 0; i < args.length; i++) args[i] = eval(c.args().get(i), f);
                f.at = c.span();
                yield callFn((FnVal) fn, args);
            }
            case Ir.FnRef r -> new FnVal((Type.Fn) subst(r.type(), f), null, null, Map.of(), r.fn(), substAll(r.typeArgs(), f));
            case Ir.Lambda l -> new FnVal((Type.Fn) subst(l.type(), f), l, f.env.snapshot(), f.typeEnv, null, null);
            case Ir.Unary u -> {
                var v = eval(u.operand(), f);
                f.at = u.span();
                yield u.op() == Ir.UnOp.NEG ? Ops.negate(v) : Ops.bitNot((Int) v);
            }
            case Ir.Binary b -> {
                var l = eval(b.left(), f);
                var r = eval(b.right(), f);
                f.at = b.span();
                if (b.flags() != null) {
                    var x = (EnumVal) l;
                    var y = (EnumVal) r;
                    long v = switch (b.op()) {
                        case BIT_AND -> x.v() & y.v();
                        case BIT_OR -> x.v() | y.v();
                        default -> x.v() ^ y.v();
                    };
                    yield new EnumVal(b.flags(), v);
                }
                yield Ops.binary(b.op(), b.prim(), l, r);
            }
            case Ir.FlagsNot n -> {
                var x = (EnumVal) eval(n.operand(), f);
                yield new EnumVal(n.flags(), Ops.wrap(n.flags().backing, ~x.v()).v());
            }
            case Ir.Eq q -> {
                var l = eval(q.left(), f);
                var r = eval(q.right(), f);
                f.at = q.span();
                yield valueEquals(l, r) != q.negate();
            }
            case Ir.Compare c -> {
                var l = eval(c.left(), f);
                var r = eval(c.right(), f);
                f.at = c.span();
                int cmp = compare(l, r);
                yield switch (c.op()) {
                    case LT -> cmp < 0;
                    case LE -> cmp <= 0;
                    case GT -> cmp > 0;
                    default -> cmp >= 0;
                };
            }
            case Ir.And a -> (Boolean) eval(a.left(), f) && (Boolean) eval(a.right(), f);
            case Ir.Or o -> (Boolean) eval(o.left(), f) || (Boolean) eval(o.right(), f);
            case Ir.Not n -> !(Boolean) eval(n.operand(), f);
            case Ir.Truthy t -> {
                var v = eval(t.operand(), f);
                f.at = t.span();
                yield truthy(v);
            }
            case Ir.Coalesce c -> {
                var l = eval(c.left(), f);
                yield l != null ? l : eval(c.right(), f);
            }
            case Ir.Cond c -> (Boolean) eval(c.condition(), f) ? eval(c.then(), f) : eval(c.otherwise(), f);
            case Ir.Is is -> isInstance(eval(is.operand(), f), subst(is.type(), f));
            case Ir.Cast c -> {
                var v = eval(c.operand(), f);
                f.at = c.span();
                yield cast(v, subst(c.type(), f), c.isChecked());
            }
            case Ir.Coerce c -> coerce(eval(c.operand(), f), subst(c.type(), f));
            case Ir.Unbox u -> {
                var v = eval(u.operand(), f);
                yield v instanceof Box b ? Values.copy(b.value) : v;
            }
            case Ir.Propagate p -> {
                var v = eval(p.operand(), f);
                for (Type t : p.fail()) if (isInstance(v, subst(t, f))) throw new ReturnSignal(v);
                yield v;
            }
            case Ir.FString s -> {
                var sb = new StringBuilder();
                for (Object part : s.parts()) {
                    if (part instanceof String text) sb.append(text);
                    else sb.append(display(eval((Ir.Expr) part, f)));
                }
                yield sb.toString();
            }
            case Ir.New n -> construct(n, f);
            case Ir.NewCollection c -> {
                var element = subst(c.element(), f);
                var items = new ArrayList<Object>();
                for (var x : c.elements()) items.add(eval(x, f));
                yield switch (c.kind()) {
                    case LIST -> new ListVal(element, items);
                    case ARRAY -> new ArrayVal(element, items.toArray());
                    case SET -> {
                        var set = new SetVal(element);
                        for (Object x : items) set.entries.put(new Key(x, this), x);
                        yield set;
                    }
                };
            }
            case Ir.NewMap m -> {
                var map = new MapVal(subst(m.key(), f), subst(m.value(), f));
                for (int i = 0; i < m.keys().size(); i++) {
                    var k = eval(m.keys().get(i), f);
                    map.entries.put(new Key(k, this), eval(m.values().get(i), f));
                }
                yield map;
            }
            case Ir.Repeat r -> {
                var element = subst(r.element(), f);
                var v = eval(r.value(), f);
                var count = (Int) eval(r.count(), f);
                f.at = r.span();
                if (count.v() < 0 || count.v() > Integer.MAX_VALUE - 8) throw new Panic("repeat count " + count + " is too large");
                var items = new ArrayList<Object>();
                for (long i = 0; i < count.v(); i++) items.add(i == 0 ? v : Values.copy(v));
                yield r.kind() == Ir.CollectionKind.ARRAY ? new ArrayVal(element, items.toArray()) : new ListVal(element, items);
            }
            case Ir.Tuple t -> {
                var items = new Object[t.elements().size()];
                for (int i = 0; i < items.length; i++) items[i] = eval(t.elements().get(i), f);
                yield new TupleVal(items);
            }
            case Ir.TypeOf t -> new TypeVal(subst(t.type(), f));
            case Ir.NameOf n -> TypeSystem.name(subst(n.type(), f));
            case Ir.TypeOfValue t -> new TypeVal(typeOf(unbox(eval(t.operand(), f))));
            case Ir.Match m -> match(m, f);
        };
    }

    private Object match(Ir.Match m, Frame f) {
        var v = eval(m.scrutinee(), f);
        for (var arm : m.arms()) {
            if (!match(arm.pattern(), v, f)) continue;
            if (arm.guard() != null && !(Boolean) eval(arm.guard(), f)) continue;
            if (arm.value() != null) return eval(arm.value(), f);
            exec(arm.body(), f);
            return Unit.UNIT;
        }
        f.at = m.span();
        throw new Panic("no match arm matched " + debug(v));
    }

    /** An initializer (§4.7): spread, explicit fields in order, then defaults. */
    private Object construct(Ir.New n, Frame f) {
        var decl = n.decl();
        var args = substAll(n.typeArgs(), f);
        var fields = new Object[decl.allFields.size()];
        var set = new boolean[fields.length];
        if (n.spread() != null) {
            var spread = unbox(eval(n.spread(), f));
            if (spread instanceof Obj s) {
                for (FieldInfo field : s.decl.allFields) {
                    if (field.slot < fields.length && decl.allFields.get(field.slot) == field) {
                        fields[field.slot] = Values.copy(s.fields[field.slot]);
                        set[field.slot] = true;
                    }
                }
            }
        }
        for (var init : n.fields()) {
            fields[init.slot()] = eval(init.value(), f);
            set[init.slot()] = true;
        }
        var self = new Type.Nominal(decl, args);
        for (FieldInfo field : decl.allFields) {
            if (set[field.slot] || field.defaultIr == null) continue;
            var base = field.owner() == decl ? self : TypeSystem.baseInstance(self, field.owner());
            var typeEnv = TypeSystem.bind(field.owner().typeParams, base == null ? List.of() : base.args());
            fields[field.slot] = eval(field.defaultIr, new Frame(decl.name(), new Env(null), typeEnv));
        }
        return new Obj(decl, args, fields);
    }
}
