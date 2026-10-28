package dev.threebolt.interp;

import dev.threebolt.interp.Values.ArrayVal;
import dev.threebolt.interp.Values.EnumVal;
import dev.threebolt.interp.Values.Flt;
import dev.threebolt.interp.Values.FnVal;
import dev.threebolt.interp.Values.Int;
import dev.threebolt.interp.Values.ListVal;
import dev.threebolt.interp.Values.Obj;
import dev.threebolt.interp.Values.PtrVal;
import dev.threebolt.interp.Values.Unit;
import dev.threebolt.types.FieldInfo;
import dev.threebolt.types.FnInfo;
import dev.threebolt.types.Prim;
import dev.threebolt.types.Type;
import dev.threebolt.types.TypeInfo;
import dev.threebolt.types.TypeParamInfo;
import dev.threebolt.types.TypeSystem;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.PaddingLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * C interop through the FFM API (§14): extern calls (including variadics and `@c struct`s by
 * value), C layouts, `Ptr<T>`, C strings, `c::alloc`/`c::free`, `extern fn` pointers in both
 * directions (§14.7) and `with_ptr` (§14.5).
 *
 * <p>Threebolt values do not live in native memory, so `with_ptr` copies the elements into a
 * native buffer for the duration of the callback and copies them back when it returns.
 */
final class Ffi {
    private final Callbacks rt;
    private final Linker linker = Linker.nativeLinker();
    private final Map<String, SymbolLookup> libraries = new ConcurrentHashMap<>();
    private final Map<FnInfo, MethodHandle> handles = new ConcurrentHashMap<>();
    /** Downcall handles for C function pointers, by address and `extern fn` type. */
    private final Map<List<Object>, MethodHandle> pointerHandles = new ConcurrentHashMap<>();
    private final Map<TypeInfo, StructLayout> structs = new ConcurrentHashMap<>();
    /** Upcall stubs of Threebolt functions converted to `extern fn`; they live as long as the program. */
    private final Map<FnInfo, MemorySegment> upcalls = new ConcurrentHashMap<>();
    /** libc's malloc and free, looked up on first use. */
    private volatile MethodHandle malloc;
    private volatile MethodHandle free;

    private static final MethodHandle ON_UPCALL;

    static {
        try {
            ON_UPCALL = MethodHandles.lookup().findVirtual(Ffi.class, "onUpcall",
                    MethodType.methodType(Object.class, FnInfo.class, Object[].class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    Ffi(Callbacks rt) {
        this.rt = rt;
    }

    // ---- Layouts (§14.2, §14.3) ----

    MemoryLayout layout(Type t) {
        t = TypeSystem.expand(t);
        return switch (t) {
            case Prim p -> switch (p) {
                case I8, U8 -> ValueLayout.JAVA_BYTE;
                case I16, U16 -> ValueLayout.JAVA_SHORT;
                case I32, U32 -> ValueLayout.JAVA_INT;
                case I64, U64, ISIZE, USIZE -> ValueLayout.JAVA_LONG;
                case F32 -> ValueLayout.JAVA_FLOAT;
                case F64 -> ValueLayout.JAVA_DOUBLE;
                case BOOL -> ValueLayout.JAVA_BOOLEAN;
                case STR -> throw new Panic("str is not an ABI type; use to_cstr()");
                case CHAR -> throw new Panic("char is not an ABI type; use c::char or u32");
            };
            case Type.Nominal n when n.decl().isEnum() -> layout(n.decl().backing);
            case Type.Nominal n when n.decl().isStd("Ptr") -> ValueLayout.ADDRESS;
            case Type.Nominal n when n.decl().isC -> struct(n.decl());
            case Type.Fn f when f.isExtern() -> ValueLayout.ADDRESS;
            case Type.Array a -> MemoryLayout.sequenceLayout(a.size(), layout(a.element()));
            default -> throw new Panic("'" + TypeSystem.name(t) + "' is not an ABI type");
        };
    }

    /** C layout: declaration order, each field at its alignment, the size rounded up to the largest one. */
    private StructLayout struct(TypeInfo decl) {
        var cached = structs.get(decl);
        if (cached != null) return cached;
        var parts = new ArrayList<MemoryLayout>();
        long offset = 0;
        long align = 1;
        for (FieldInfo f : decl.allFields) {
            var l = layout(f.type).withName(f.name());
            long a = l.byteAlignment();
            if (offset % a != 0) {
                parts.add(MemoryLayout.paddingLayout(a - offset % a));
                offset += a - offset % a;
            }
            parts.add(l);
            offset += l.byteSize();
            align = Math.max(align, a);
        }
        if (offset % align != 0) parts.add(MemoryLayout.paddingLayout(align - offset % align));
        var s = MemoryLayout.structLayout(parts.toArray(MemoryLayout[]::new));
        structs.putIfAbsent(decl, s);
        return s;
    }

    // ---- Values in native memory ----

    /** Reads a value of type {@code t} at {@code offset}. */
    Object load(MemorySegment seg, long offset, Type t) {
        t = TypeSystem.expand(t);
        var l = layout(t);
        switch (t) {
            case Type.Nominal n when n.decl().isC -> {
                var decl = n.decl();
                var fields = new Object[decl.allFields.size()];
                int i = 0;
                long at = offset;
                for (MemoryLayout m : ((StructLayout) l).memberLayouts()) {
                    if (!(m instanceof PaddingLayout)) {
                        fields[i] = load(seg, at, decl.allFields.get(i).type);
                        i++;
                    }
                    at += m.byteSize();
                }
                return new Obj(decl, List.of(), fields);
            }
            case Type.Array a -> {
                long size = layout(a.element()).byteSize();
                var items = new Object[a.size()];
                for (int i = 0; i < items.length; i++) items[i] = load(seg, offset + i * size, a.element());
                return new ArrayVal(a.element(), items);
            }
            default -> {
                return fromJava(get(seg, offset, (ValueLayout) l), t);
            }
        }
    }

    /** Writes {@code v}, of type {@code t}, at {@code offset}. */
    void store(MemorySegment seg, long offset, Type t, Object v) {
        t = TypeSystem.expand(t);
        var l = layout(t);
        switch (t) {
            case Type.Nominal n when n.decl().isC -> {
                var o = (Obj) Interpreter.unbox(v);
                int i = 0;
                long at = offset;
                for (MemoryLayout m : ((StructLayout) l).memberLayouts()) {
                    if (!(m instanceof PaddingLayout)) {
                        store(seg, at, n.decl().allFields.get(i).type, o.fields[i]);
                        i++;
                    }
                    at += m.byteSize();
                }
            }
            case Type.Array a -> {
                long size = layout(a.element()).byteSize();
                var items = ((ArrayVal) v).items;
                for (int i = 0; i < items.length; i++) store(seg, offset + i * size, a.element(), items[i]);
            }
            default -> set(seg, offset, (ValueLayout) l, toJava(v, l));
        }
    }

    /** A scalar Threebolt value as the Java value FFM expects for {@code layout}. */
    private Object toJava(Object v, MemoryLayout layout) {
        return switch (v) {
            case Int i when layout == ValueLayout.JAVA_BYTE -> (byte) i.v();
            case Int i when layout == ValueLayout.JAVA_SHORT -> (short) i.v();
            case Int i when layout == ValueLayout.JAVA_INT -> (int) i.v();
            case Int i -> i.v();
            case Flt f when layout == ValueLayout.JAVA_FLOAT -> (float) f.v();
            case Flt f -> f.v();
            case Boolean b when layout == ValueLayout.JAVA_INT -> b ? 1 : 0;
            case Boolean b -> b;
            case EnumVal e -> toJava(new Int(e.decl().backing, e.v()), layout);
            case PtrVal p -> MemorySegment.ofAddress(p.addr());
            case FnVal f -> MemorySegment.ofAddress(address(f));
            default -> throw new Panic("cannot pass " + rt.debug(v) + " to C");
        };
    }

    /** A scalar from FFM as a Threebolt value of type {@code t}. */
    private Object fromJava(Object v, Type t) {
        t = TypeSystem.expand(t);
        if (t == Type.UNIT) return Unit.UNIT;
        if (t instanceof Type.Nominal n && n.decl().isEnum()) {
            return new EnumVal(n.decl(), Ops.wrap(n.decl().backing, ((Number) v).longValue()).v());
        }
        if (t instanceof Type.Nominal n && n.decl().isStd("Ptr")) {
            return new PtrVal(n.args().getFirst(), ((MemorySegment) v).address());
        }
        if (t instanceof Type.Fn f) return FnVal.pointer(f, ((MemorySegment) v).address());
        var p = (Prim) t;
        return switch (p) {
            case F32 -> Ops.flt(p, (Float) v);
            case F64 -> Ops.flt(p, (Double) v);
            case BOOL -> v;
            default -> Ops.wrap(p, ((Number) v).longValue());
        };
    }

    private static Object get(MemorySegment seg, long offset, ValueLayout l) {
        return switch (l) {
            case ValueLayout.OfByte b -> seg.get(b, offset);
            case ValueLayout.OfShort s -> seg.get(s.withByteAlignment(1), offset);
            case ValueLayout.OfInt i -> seg.get(i.withByteAlignment(1), offset);
            case ValueLayout.OfLong lo -> seg.get(lo.withByteAlignment(1), offset);
            case ValueLayout.OfFloat f -> seg.get(f.withByteAlignment(1), offset);
            case ValueLayout.OfDouble d -> seg.get(d.withByteAlignment(1), offset);
            case ValueLayout.OfBoolean b -> seg.get(b, offset);
            case AddressLayout a -> seg.get(a.withByteAlignment(1), offset);
            default -> throw new Panic("cannot read " + l);
        };
    }

    private static void set(MemorySegment seg, long offset, ValueLayout l, Object v) {
        switch (l) {
            case ValueLayout.OfByte b -> seg.set(b, offset, (Byte) v);
            case ValueLayout.OfShort s -> seg.set(s.withByteAlignment(1), offset, (Short) v);
            case ValueLayout.OfInt i -> seg.set(i.withByteAlignment(1), offset, (Integer) v);
            case ValueLayout.OfLong lo -> seg.set(lo.withByteAlignment(1), offset, (Long) v);
            case ValueLayout.OfFloat f -> seg.set(f.withByteAlignment(1), offset, (Float) v);
            case ValueLayout.OfDouble d -> seg.set(d.withByteAlignment(1), offset, (Double) v);
            case ValueLayout.OfBoolean b -> seg.set(b, offset, (Boolean) v);
            case AddressLayout a -> seg.set(a.withByteAlignment(1), offset, (MemorySegment) v);
            default -> throw new Panic("cannot write " + l);
        }
    }

    // ---- Extern calls (§14.1) ----

    Object call(FnInfo fn, Object[] args) {
        int fixed = fn.params.size();
        var layouts = new ArrayList<MemoryLayout>();
        for (var p : fn.params) layouts.add(layout(p.type()));
        for (int i = fixed; i < args.length; i++) layouts.add(variadicLayout(args[i]));
        MethodHandle handle = fn.isVariadic
                ? linker.downcallHandle(symbol(fn), descriptor(layouts, fn.ret), Linker.Option.firstVariadicArg(fixed))
                : handles.computeIfAbsent(fn, f -> linker.downcallHandle(symbol(f), descriptor(layouts, f.ret)));
        return downcall(handle, layouts, fn.ret, args, "extern '" + fn.name() + "'");
    }

    /** Calls a C function pointer held in an `extern fn` value. */
    Object callPointer(FnVal f, Object[] args) {
        if (f.addr == 0) throw new Panic("call through a null `extern fn` pointer");
        var layouts = new ArrayList<MemoryLayout>();
        for (Type p : f.type.params()) layouts.add(layout(p));
        var handle = pointerHandles.computeIfAbsent(List.of(f.addr, f.type),
                k -> linker.downcallHandle(MemorySegment.ofAddress(f.addr), descriptor(layouts, f.type.ret())));
        return downcall(handle, layouts, f.type.ret(), args, "'" + TypeSystem.name(f.type) + "' pointer");
    }

    private Object downcall(MethodHandle handle, List<MemoryLayout> layouts, Type ret, Object[] args, String what) {
        try (var arena = Arena.ofConfined()) {
            var javaArgs = new ArrayList<Object>();
            var retType = TypeSystem.expand(ret);
            boolean structRet = retType != Type.UNIT && layout(retType) instanceof GroupLayout;
            if (structRet) javaArgs.add(arena);
            for (int i = 0; i < args.length; i++) {
                var l = layouts.get(i);
                if (l instanceof GroupLayout g) {
                    var seg = arena.allocate(g);
                    store(seg, 0, rt.typeOf(Interpreter.unbox(args[i])), args[i]);
                    javaArgs.add(seg);
                } else {
                    javaArgs.add(toJava(args[i], l));
                }
            }
            var result = handle.invokeWithArguments(javaArgs);
            // A callback may have panicked; C has returned, so the process can now stop.
            rt.checkAbort();
            if (retType == Type.UNIT) return Unit.UNIT;
            return structRet ? load((MemorySegment) result, 0, retType) : fromJava(result, retType);
        } catch (Panic | Callbacks.Abort | Callbacks.ExitSignal e) {
            throw e;
        } catch (Throwable t) {
            throw new Panic("call to " + what + " failed: " + t);
        }
    }

    private FunctionDescriptor descriptor(List<MemoryLayout> args, Type ret) {
        var a = args.toArray(MemoryLayout[]::new);
        return TypeSystem.expand(ret) == Type.UNIT ? FunctionDescriptor.ofVoid(a) : FunctionDescriptor.of(layout(ret), a);
    }

    /** C default argument promotions for variadic arguments (§14.1). */
    private MemoryLayout variadicLayout(Object v) {
        return switch (v) {
            case Flt f -> ValueLayout.JAVA_DOUBLE;
            case Int i when i.kind().bits() <= 32 -> ValueLayout.JAVA_INT;
            case Int i -> ValueLayout.JAVA_LONG;
            case Boolean b -> ValueLayout.JAVA_INT;
            case EnumVal e -> e.decl().backing.bits() <= 32 ? ValueLayout.JAVA_INT : ValueLayout.JAVA_LONG;
            case PtrVal p -> ValueLayout.ADDRESS;
            case FnVal f -> ValueLayout.ADDRESS;
            default -> throw new Panic("cannot pass " + rt.debug(v) + " as a C variadic argument");
        };
    }

    private MemorySegment symbol(FnInfo fn) {
        return lookup(fn.linkLibrary).find(fn.name())
                .orElseThrow(() -> new Panic("extern function '" + fn.name() + "' not found"));
    }

    private SymbolLookup lookup(String library) {
        if (library == null) return linker.defaultLookup();
        return libraries.computeIfAbsent(library, lib -> {
            try {
                return SymbolLookup.libraryLookup(System.mapLibraryName(lib), Arena.global());
            } catch (IllegalArgumentException e) {
                try {
                    return SymbolLookup.libraryLookup(lib, Arena.global());
                } catch (IllegalArgumentException e2) {
                    throw new Panic("cannot load library '" + lib + "'");
                }
            }
        });
    }

    // ---- Callbacks (§14.7) ----

    /** The C address of an `extern fn` value. */
    long address(FnVal f) {
        if (f.fn == null && f.lambda == null) return f.addr;
        if (f.fn != null && f.fn.isExtern) return symbol(f.fn).address();
        if (f.fn != null) return upcall(f.fn);
        throw new Panic("a closure cannot be passed to C");
    }

    /** An upcall stub that runs {@code fn} (a top-level function with an ABI signature) when C calls it. */
    private long upcall(FnInfo fn) {
        var stub = upcalls.get(fn);
        if (stub == null) {
            var layouts = new ArrayList<MemoryLayout>();
            for (var p : fn.params) layouts.add(layout(p.type()));
            var desc = descriptor(layouts, fn.ret);
            var target = MethodHandles.insertArguments(ON_UPCALL, 0, this, fn)
                    .asCollector(Object[].class, layouts.size())
                    .asType(desc.toMethodType());
            stub = linker.upcallStub(target, desc, Arena.global());
            var raced = upcalls.putIfAbsent(fn, stub);
            if (raced != null) stub = raced;
        }
        return stub.address();
    }

    /** {@code fn} as a function value, to call it like any other callback. */
    private static FnVal named(FnInfo fn) {
        var params = new ArrayList<Type>();
        for (var p : fn.params) params.add(p.type());
        return new FnVal(new Type.Fn(params, fn.ret, true), null, null, Map.of(), fn, List.of());
    }

    /**
     * Runs a callback. Nothing may unwind through the C frames, so a panic or `exit` here ends the
     * process (§13.1) and C gets a zero result; the calling Threebolt code stops when C returns.
     */
    @SuppressWarnings("unused") // called through ON_UPCALL
    private Object onUpcall(FnInfo fn, Object[] args) {
        try {
            if (!rt.isTerminating()) {
                var values = new Object[args.length];
                for (int i = 0; i < args.length; i++) {
                    var t = fn.params.get(i).type();
                    values[i] = args[i] instanceof MemorySegment seg && layout(t) instanceof GroupLayout
                            ? load(seg, 0, t) : fromJava(args[i], t);
                }
                var result = rt.call(named(fn), values);
                return toC(result, fn.ret);
            }
        } catch (Panic p) {
            rt.panicked(p);
        } catch (Callbacks.ExitSignal e) {
            rt.terminate(e.code);
        } catch (Callbacks.Abort a) {
            // The process is already ending.
        } catch (RuntimeException e) {
            rt.panicked(new Panic("callback '" + fn.qualifiedName() + "' failed: " + e));
        }
        return zero(fn.ret);
    }

    private Object toC(Object v, Type ret) {
        if (TypeSystem.expand(ret) == Type.UNIT) return null;
        var l = layout(ret);
        if (l instanceof GroupLayout g) {
            var seg = Arena.ofAuto().allocate(g);
            store(seg, 0, ret, v);
            return seg;
        }
        return toJava(v, l);
    }

    private Object zero(Type ret) {
        if (TypeSystem.expand(ret) == Type.UNIT) return null;
        return switch (layout(ret)) {
            case GroupLayout g -> Arena.ofAuto().allocate(g);
            case ValueLayout.OfByte b -> (byte) 0;
            case ValueLayout.OfShort s -> (short) 0;
            case ValueLayout.OfInt i -> 0;
            case ValueLayout.OfLong lo -> 0L;
            case ValueLayout.OfFloat f -> 0f;
            case ValueLayout.OfDouble d -> 0d;
            case ValueLayout.OfBoolean b -> false;
            default -> MemorySegment.NULL;
        };
    }

    // ---- Passing GC memory (§14.5) ----

    /**
     * {@code x.with_ptr(f)} on a list, an array or an `@c struct` place: the elements (or the
     * struct) are copied into native memory for the duration of {@code f}, then copied back.
     */
    Object withPtr(Object self, FnVal f) {
        Type element;
        int count;
        switch (self) {
            case ListVal l -> {
                element = l.element;
                count = l.items.size();
            }
            case ArrayVal a -> {
                element = a.element;
                count = a.items.length;
            }
            case Obj o -> {
                element = new Type.Nominal(o.decl, List.of());
                count = 1;
            }
            default -> throw new IllegalStateException("with_ptr on " + self);
        }
        long size = layout(element).byteSize();
        try (var arena = Arena.ofConfined()) {
            var seg = arena.allocate(Math.max(1, size * count), layout(element).byteAlignment());
            for (int i = 0; i < count; i++) store(seg, i * size, element, item(self, i));
            var result = rt.call(f, new PtrVal(element, seg.address()));
            for (int i = 0; i < count; i++) setItem(self, i, load(seg, i * size, element));
            return result;
        }
    }

    private static Object item(Object self, int i) {
        return switch (self) {
            case ListVal l -> l.items.get(i);
            case ArrayVal a -> a.items[i];
            default -> self;
        };
    }

    private static void setItem(Object self, int i, Object v) {
        switch (self) {
            case ListVal l -> {
                if (i < l.items.size()) l.items.set(i, v);
            }
            case ArrayVal a -> a.items[i] = v;
            case Obj o -> System.arraycopy(((Obj) v).fields, 0, o.fields, 0, o.fields.length);
            default -> throw new IllegalStateException("with_ptr on " + self);
        }
    }

    // ---- Allocation ----

    private MethodHandle libc(String name, FunctionDescriptor desc) {
        var symbol = linker.defaultLookup().find(name).orElseThrow();
        return linker.downcallHandle(symbol, desc);
    }

    private long malloc(long size) {
        try {
            if (malloc == null) malloc = libc("malloc", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            var seg = (MemorySegment) malloc.invokeExact(size);
            if (seg.address() == 0 && size > 0) throw new Panic("out of memory");
            return seg.address();
        } catch (Panic p) {
            throw p;
        } catch (Throwable t) {
            throw new Panic("malloc failed: " + t);
        }
    }

    private void free(long addr) {
        try {
            if (free == null) free = libc("free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            free.invokeExact(MemorySegment.ofAddress(addr));
        } catch (Throwable t) {
            throw new Panic("free failed: " + t);
        }
    }

    // ---- Intrinsics: c::alloc/free, Ptr<T>, str C strings (§14.2, §14.4) ----

    Object intrinsic(String owner, String name, Object self, Object[] a, Map<TypeParamInfo, Type> typeEnv, FnInfo fn) {
        var ptr = rt.ts().ptr;
        Type target = typeEnv.getOrDefault(ptr.typeParams.getFirst(), Type.ERROR);
        switch (owner + "." + name) {
            case "c.alloc" -> {
                var t = typeEnv.get(fn.typeParams.getFirst());
                long count = ((Int) a[0]).v();
                return new PtrVal(t, malloc(Math.max(1, count * layout(t).byteSize())));
            }
            case "c.free" -> {
                free(((PtrVal) a[0]).addr());
                return Unit.UNIT;
            }
            case "Ptr.null" -> {
                return new PtrVal(target, 0);
            }
            case "Ptr.from_addr" -> {
                return new PtrVal(target, ((Int) a[0]).v());
            }
            case "Ptr.is_null" -> {
                return ((PtrVal) self).addr() == 0;
            }
            case "Ptr.addr" -> {
                return new Int(Prim.USIZE, ((PtrVal) self).addr());
            }
            case "Ptr.offset" -> {
                var p = (PtrVal) self;
                return new PtrVal(p.target(), p.addr() + ((Int) a[0]).v() * layout(p.target()).byteSize());
            }
            case "Ptr.cast" -> {
                return new PtrVal(typeEnv.get(fn.typeParams.getFirst()), ((PtrVal) self).addr());
            }
            case "Ptr.read" -> {
                var p = nonNull((PtrVal) self);
                return load(MemorySegment.ofAddress(p.addr()).reinterpret(layout(p.target()).byteSize()), 0, p.target());
            }
            case "Ptr.write" -> {
                var p = nonNull((PtrVal) self);
                store(MemorySegment.ofAddress(p.addr()).reinterpret(layout(p.target()).byteSize()), 0, p.target(), a[0]);
                return Unit.UNIT;
            }
            case "str.to_cstr" -> {
                var bytes = self.toString().getBytes(StandardCharsets.UTF_8);
                long addr = malloc(bytes.length + 1);
                var seg = MemorySegment.ofAddress(addr).reinterpret(bytes.length + 1);
                MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0, bytes.length);
                seg.set(ValueLayout.JAVA_BYTE, bytes.length, (byte) 0);
                return new PtrVal(((Type.Nominal) TypeSystem.expand(fn.ret)).args().getFirst(), addr);
            }
            case "str.from_cstr" -> {
                var p = nonNull((PtrVal) a[0]);
                return Values.Str.of(MemorySegment.ofAddress(p.addr()).reinterpret(Long.MAX_VALUE).getString(0));
            }
            default -> throw new IllegalStateException("unknown intrinsic " + owner + "." + name);
        }
    }

    private static PtrVal nonNull(PtrVal p) {
        if (p.addr() == 0) throw new Panic("null pointer dereference");
        return p;
    }
}
