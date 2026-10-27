package dev.threebolt.interp;

import dev.threebolt.interp.Values.EnumVal;
import dev.threebolt.interp.Values.Flt;
import dev.threebolt.interp.Values.Int;
import dev.threebolt.interp.Values.PtrVal;
import dev.threebolt.interp.Values.Unit;
import dev.threebolt.types.FnInfo;
import dev.threebolt.types.Prim;
import dev.threebolt.types.Type;
import dev.threebolt.types.TypeParamInfo;
import dev.threebolt.types.TypeSystem;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * C interop through the FFM API (§14): extern calls with ABI scalar and pointer types, `Ptr<T>`
 * reads and writes of primitives, C strings, and `c::alloc`/`c::free`. `@c struct`s, callbacks
 * and `with_ptr` are Phase 8.
 */
final class Ffi {
    private final Interpreter in;
    private final Linker linker = Linker.nativeLinker();
    private final Map<String, SymbolLookup> libraries = new HashMap<>();
    private final Map<FnInfo, MethodHandle> handles = new HashMap<>();
    private MethodHandle malloc;
    private MethodHandle free;

    Ffi(Interpreter in) {
        this.in = in;
    }

    // ---- Layouts ----

    private static MemoryLayout layout(Type t) {
        t = TypeSystem.expand(t);
        if (t instanceof Type.Nominal n && n.decl().isEnum()) return layout(n.decl().backing);
        if (t instanceof Type.Nominal n && n.decl().isStd("Ptr")) return ValueLayout.ADDRESS;
        if (t instanceof Prim p) {
            return switch (p) {
                case I8, U8 -> ValueLayout.JAVA_BYTE;
                case I16, U16 -> ValueLayout.JAVA_SHORT;
                case I32, U32, CHAR -> ValueLayout.JAVA_INT;
                case I64, U64, ISIZE, USIZE -> ValueLayout.JAVA_LONG;
                case F32 -> ValueLayout.JAVA_FLOAT;
                case F64 -> ValueLayout.JAVA_DOUBLE;
                case BOOL -> ValueLayout.JAVA_BOOLEAN;
                case STR -> throw new Panic("str is not an ABI type; use to_cstr()");
            };
        }
        throw new Panic("'" + t + "' is not an ABI type");
    }

    private static Object toJava(Object v, MemoryLayout layout) {
        return switch (v) {
            case Int i when layout == ValueLayout.JAVA_BYTE -> (byte) i.v();
            case Int i when layout == ValueLayout.JAVA_SHORT -> (short) i.v();
            case Int i when layout == ValueLayout.JAVA_INT -> (int) i.v();
            case Int i -> i.v();
            case Flt f when layout == ValueLayout.JAVA_FLOAT -> (float) f.v();
            case Flt f -> f.v();
            case Boolean b -> b;
            case EnumVal e -> toJava(new Int(e.decl().backing, e.v()), layout);
            case PtrVal p -> MemorySegment.ofAddress(p.addr());
            case Values.Char c -> c.cp();
            default -> throw new Panic("cannot pass " + v + " to C");
        };
    }

    private Object fromJava(Object v, Type t) {
        t = TypeSystem.expand(t);
        if (t == Type.UNIT) return Unit.UNIT;
        if (t instanceof Type.Nominal n && n.decl().isEnum()) {
            return new EnumVal(n.decl(), Ops.wrap(n.decl().backing, ((Number) v).longValue()).v());
        }
        if (t instanceof Type.Nominal n && n.decl().isStd("Ptr")) {
            return new PtrVal(n.args().getFirst(), ((MemorySegment) v).address());
        }
        var p = (Prim) t;
        return switch (p) {
            case F32 -> Ops.flt(p, (Float) v);
            case F64 -> Ops.flt(p, (Double) v);
            case BOOL -> v;
            case CHAR -> new Values.Char((Integer) v);
            default -> Ops.wrap(p, ((Number) v).longValue());
        };
    }

    // ---- Extern calls (§14.1) ----

    Object call(FnInfo fn, Object[] args) {
        try {
            var fixed = fn.params.size();
            var argLayouts = new ArrayList<MemoryLayout>();
            for (var p : fn.params) argLayouts.add(layout(p.type()));
            for (int i = fixed; i < args.length; i++) argLayouts.add(variadicLayout(args[i]));
            MethodHandle handle = fn.isVariadic ? handle(fn, argLayouts, fixed) : handles.computeIfAbsent(fn,
                    f -> handle(f, argLayouts, -1));
            var javaArgs = new Object[args.length];
            for (int i = 0; i < args.length; i++) javaArgs[i] = toJava(args[i], argLayouts.get(i));
            var result = handle.invokeWithArguments(javaArgs);
            return fromJava(result, fn.ret);
        } catch (Panic p) {
            throw p;
        } catch (Throwable t) {
            throw new Panic("call to extern '" + fn.name() + "' failed: " + t);
        }
    }

    /** C default argument promotions for variadic arguments (§14.1). */
    private static MemoryLayout variadicLayout(Object v) {
        return switch (v) {
            case Flt f -> ValueLayout.JAVA_DOUBLE;
            case Int i when i.kind().bits() <= 32 -> ValueLayout.JAVA_INT;
            case Int i -> ValueLayout.JAVA_LONG;
            case Boolean b -> ValueLayout.JAVA_INT;
            case PtrVal p -> ValueLayout.ADDRESS;
            default -> throw new Panic("cannot pass " + v + " as a C variadic argument");
        };
    }

    private MethodHandle handle(FnInfo fn, List<MemoryLayout> args, int firstVariadic) {
        var symbol = lookup(fn.linkLibrary).find(fn.name())
                .orElseThrow(() -> new Panic("extern function '" + fn.name() + "' not found"));
        var desc = fn.ret == Type.UNIT ? FunctionDescriptor.ofVoid(args.toArray(MemoryLayout[]::new))
                : FunctionDescriptor.of(layout(fn.ret), args.toArray(MemoryLayout[]::new));
        if (firstVariadic >= 0) return linker.downcallHandle(symbol, desc, Linker.Option.firstVariadicArg(firstVariadic));
        return linker.downcallHandle(symbol, desc);
    }

    private SymbolLookup lookup(String library) {
        if (library == null) return linker.defaultLookup();
        return libraries.computeIfAbsent(library, lib -> {
            try {
                return SymbolLookup.libraryLookup(System.mapLibraryName(lib), Arena.global());
            } catch (IllegalArgumentException e) {
                return SymbolLookup.libraryLookup(lib, Arena.global());
            }
        });
    }

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
        var ptr = in.ts.ptr;
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
                var p = (PtrVal) self;
                nonNull(p);
                var l = (ValueLayout) layout(p.target());
                var seg = MemorySegment.ofAddress(p.addr()).reinterpret(l.byteSize());
                return fromJava(read(seg, l), p.target());
            }
            case "Ptr.write" -> {
                var p = (PtrVal) self;
                nonNull(p);
                var l = (ValueLayout) layout(p.target());
                write(MemorySegment.ofAddress(p.addr()).reinterpret(l.byteSize()), l, toJava(a[0], l));
                return Unit.UNIT;
            }
            case "str.to_cstr" -> {
                var bytes = ((String) self).getBytes(StandardCharsets.UTF_8);
                long addr = malloc(bytes.length + 1);
                var seg = MemorySegment.ofAddress(addr).reinterpret(bytes.length + 1);
                MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0, bytes.length);
                seg.set(ValueLayout.JAVA_BYTE, bytes.length, (byte) 0);
                return new PtrVal(Prim.I8, addr);
            }
            case "str.from_cstr" -> {
                var p = (PtrVal) a[0];
                nonNull(p);
                return MemorySegment.ofAddress(p.addr()).reinterpret(Long.MAX_VALUE).getString(0);
            }
            default -> throw new IllegalStateException("unknown intrinsic " + owner + "." + name);
        }
    }

    private static void nonNull(PtrVal p) {
        if (p.addr() == 0) throw new Panic("null pointer dereference");
    }

    private static Object read(MemorySegment seg, ValueLayout l) {
        return switch (l) {
            case ValueLayout.OfByte b -> seg.get(b, 0);
            case ValueLayout.OfShort s -> seg.get(s.withByteAlignment(1), 0);
            case ValueLayout.OfInt i -> seg.get(i.withByteAlignment(1), 0);
            case ValueLayout.OfLong lo -> seg.get(lo.withByteAlignment(1), 0);
            case ValueLayout.OfFloat f -> seg.get(f.withByteAlignment(1), 0);
            case ValueLayout.OfDouble d -> seg.get(d.withByteAlignment(1), 0);
            case ValueLayout.OfBoolean b -> seg.get(b, 0);
            case java.lang.foreign.AddressLayout a -> seg.get(a.withByteAlignment(1), 0);
            default -> throw new Panic("cannot read " + l);
        };
    }

    private static void write(MemorySegment seg, ValueLayout l, Object v) {
        switch (l) {
            case ValueLayout.OfByte b -> seg.set(b, 0, (Byte) v);
            case ValueLayout.OfShort s -> seg.set(s.withByteAlignment(1), 0, (Short) v);
            case ValueLayout.OfInt i -> seg.set(i.withByteAlignment(1), 0, (Integer) v);
            case ValueLayout.OfLong lo -> seg.set(lo.withByteAlignment(1), 0, (Long) v);
            case ValueLayout.OfFloat f -> seg.set(f.withByteAlignment(1), 0, (Float) v);
            case ValueLayout.OfDouble d -> seg.set(d.withByteAlignment(1), 0, (Double) v);
            case ValueLayout.OfBoolean b -> seg.set(b, 0, (Boolean) v);
            case java.lang.foreign.AddressLayout a -> seg.set(a.withByteAlignment(1), 0, (MemorySegment) v);
            default -> throw new Panic("cannot write " + l);
        }
    }
}
