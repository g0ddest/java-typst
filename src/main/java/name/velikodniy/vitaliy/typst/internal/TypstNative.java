package name.velikodniy.vitaliy.typst.internal;

import name.velikodniy.vitaliy.typst.TypstNativeException;
import name.velikodniy.vitaliy.typst.TypstPackageNotFoundException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * FFM API bindings for the typst-java native library.
 * All C ABI functions are bound as MethodHandles and exposed as typed Java methods.
 */
public final class TypstNative {

    private static final MethodHandle ENGINE_NEW;
    private static final MethodHandle ENGINE_FREE;
    private static final MethodHandle ADD_FONT;
    private static final MethodHandle ADD_FONT_DIR;
    private static final MethodHandle COMPILE;
    private static final MethodHandle RESULT_IS_OK;
    private static final MethodHandle RESULT_PDF;
    private static final MethodHandle RESULT_ERRORS;
    private static final MethodHandle RESULT_WARNINGS;
    private static final MethodHandle RESULT_FREE;
    private static final MethodHandle INVALIDATE_TEMPLATE;
    private static final MethodHandle INVALIDATE_ALL;

    static {
        SymbolLookup symbols = NativeLibLoader.load();
        Linker linker = Linker.nativeLinker();

        // typst_engine_new(const char*, download_fn) -> void*
        ENGINE_NEW = linker.downcallHandle(
                symbols.find("typst_engine_new").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        // typst_engine_free(void*)
        ENGINE_FREE = linker.downcallHandle(
                symbols.find("typst_engine_free").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        // typst_add_font(void*, const uint8_t*, size_t) -> int32_t
        ADD_FONT = linker.downcallHandle(
                symbols.find("typst_add_font").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

        // typst_add_font_dir(void*, const char*) -> int32_t
        ADD_FONT_DIR = linker.downcallHandle(
                symbols.find("typst_add_font_dir").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        // typst_compile(void*, const char*, const char*, const char*) -> void*
        COMPILE = linker.downcallHandle(
                symbols.find("typst_compile").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        // typst_result_is_ok(const void*) -> int32_t
        RESULT_IS_OK = linker.downcallHandle(
                symbols.find("typst_result_is_ok").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        // typst_result_pdf(const void*, size_t*) -> const uint8_t*
        RESULT_PDF = linker.downcallHandle(
                symbols.find("typst_result_pdf").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        // typst_result_errors(const void*) -> const char*
        RESULT_ERRORS = linker.downcallHandle(
                symbols.find("typst_result_errors").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        // typst_result_warnings(const void*) -> const char*
        RESULT_WARNINGS = linker.downcallHandle(
                symbols.find("typst_result_warnings").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        // typst_result_free(void*)
        RESULT_FREE = linker.downcallHandle(
                symbols.find("typst_result_free").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        // typst_invalidate_template(void*, const char*)
        INVALIDATE_TEMPLATE = linker.downcallHandle(
                symbols.find("typst_invalidate_template").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        // typst_invalidate_all(void*)
        INVALIDATE_ALL = linker.downcallHandle(
                symbols.find("typst_invalidate_all").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }

    private TypstNative() {}

    // int32_t(namespace, name, version, out_path*) — 4 ADDRESS params
    private static final FunctionDescriptor RESOLVE_FN_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS);

    // Per-thread state set before each compile() call so the upcall resolves
    // packages through the correct engine's resolver and allocates in a
    // deterministic arena.
    private static final ThreadLocal<PackageManager> ACTIVE_PACKAGE_MANAGER = new ThreadLocal<>();
    private static final ThreadLocal<Arena> COMPILE_ARENA = new ThreadLocal<>();

    /**
     * Set the per-thread context for a compilation call.
     * Must be called before each native compilation.
     *
     * @param pm    the package manager for this engine
     * @param arena the arena for allocating path strings returned to Rust
     */
    public static void setCompileContext(PackageManager pm, Arena arena) {
        ACTIVE_PACKAGE_MANAGER.set(pm);
        COMPILE_ARENA.set(arena);
    }

    /**
     * Clear the per-thread compilation context.
     */
    public static void clearCompileContext() {
        ACTIVE_PACKAGE_MANAGER.remove();
        COMPILE_ARENA.remove();
    }

    /**
     * Upcall target: called from Rust when a package needs resolving.
     * Returns a path to the unpacked package directory.
     */
    @SuppressWarnings("unused") // called via upcall from native code
    private static int resolveCallback(MemorySegment nsPtr,
                                       MemorySegment namePtr,
                                       MemorySegment versionPtr,
                                       MemorySegment outPathPtr) {
        try {
            PackageManager pm = ACTIVE_PACKAGE_MANAGER.get();
            if (pm == null) return -1;

            String ns = nsPtr.reinterpret(Long.MAX_VALUE).getString(0);
            String name = namePtr.reinterpret(Long.MAX_VALUE).getString(0);
            String version = versionPtr.reinterpret(Long.MAX_VALUE).getString(0);

            String path = pm.resolveToPath(ns, name, version);

            Arena arena = COMPILE_ARENA.get();
            MemorySegment pathSeg = (arena != null ? arena : Arena.ofAuto()).allocateFrom(path);
            outPathPtr.reinterpret(ValueLayout.ADDRESS.byteSize())
                    .set(ValueLayout.ADDRESS, 0, pathSeg);
            return 0;
        } catch (TypstPackageNotFoundException _) {
            return 1;
        } catch (Exception _) {
            return -1;
        }
    }

    /**
     * Create an upcall stub for the package resolver.
     *
     * @param arena arena whose lifetime controls the stub's validity
     * @return a function pointer usable from native code
     */
    public static MemorySegment createResolverStub(Arena arena) {
        try {
            var handle = MethodHandles.lookup().findStatic(
                    TypstNative.class, "resolveCallback",
                    MethodType.methodType(int.class,
                            MemorySegment.class, MemorySegment.class,
                            MemorySegment.class, MemorySegment.class));
            return Linker.nativeLinker().upcallStub(handle, RESOLVE_FN_DESC, arena);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * Create a new Typst engine with the given JSON config and download callback.
     *
     * @param arena       arena for allocating the config string
     * @param config      JSON config string (e.g. {"template_cache_enabled":true})
     * @param downloadFn  function pointer for downloading package URLs
     * @return pointer to the engine (opaque)
     */
    public static MemorySegment engineNew(Arena arena, String config, MemorySegment downloadFn) {
        try {
            MemorySegment configPtr = (config != null)
                    ? arena.allocateFrom(config)
                    : MemorySegment.NULL;
            return (MemorySegment) ENGINE_NEW.invokeExact(configPtr, downloadFn);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * Free a Typst engine.
     *
     * @param enginePtr pointer to the engine to free
     */
    public static void engineFree(MemorySegment enginePtr) {
        try {
            ENGINE_FREE.invokeExact(enginePtr);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * Add a font from raw byte data.
     *
     * @param arena     arena for allocating the font buffer
     * @param enginePtr pointer to the engine
     * @param fontData  raw font bytes
     * @return 0 on success, negative on error
     */
    public static int addFont(Arena arena, MemorySegment enginePtr, byte[] fontData) {
        try {
            MemorySegment buf = arena.allocate(fontData.length);
            buf.copyFrom(MemorySegment.ofArray(fontData));
            return (int) ADD_FONT.invokeExact(enginePtr, buf, (long) fontData.length);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * Add all fonts from a directory.
     *
     * @param arena     arena for allocating the path string
     * @param enginePtr pointer to the engine
     * @param path      path to the font directory
     * @return 0 on success, negative on error
     */
    public static int addFontDir(Arena arena, MemorySegment enginePtr, String path) {
        try {
            MemorySegment pathPtr = arena.allocateFrom(path);
            return (int) ADD_FONT_DIR.invokeExact(enginePtr, pathPtr);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * Compile a template.
     *
     * @param arena       arena for allocating strings
     * @param enginePtr   engine pointer
     * @param templateKey cache key or file path
     * @param source      template source text, or null to read from file
     * @param dataJson    JSON data, or null for no data
     * @return pointer to the result (must be freed with resultFree)
     */
    public static MemorySegment compile(Arena arena, MemorySegment enginePtr,
                                         String templateKey, String source, String dataJson) {
        try {
            MemorySegment keyPtr = arena.allocateFrom(templateKey);
            MemorySegment sourcePtr = (source != null)
                    ? arena.allocateFrom(source)
                    : MemorySegment.NULL;
            MemorySegment dataPtr = (dataJson != null)
                    ? arena.allocateFrom(dataJson)
                    : MemorySegment.NULL;
            return (MemorySegment) COMPILE.invokeExact(enginePtr, keyPtr, sourcePtr, dataPtr);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * Check if a compilation result is successful.
     *
     * @param resultPtr pointer to the compilation result
     * @return true if success
     */
    public static boolean resultIsOk(MemorySegment resultPtr) {
        try {
            return (int) RESULT_IS_OK.invokeExact(resultPtr) == 1;
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * Get the PDF bytes from a successful result.
     *
     * @param arena     arena for allocating the output length buffer
     * @param resultPtr pointer to the compilation result
     * @return PDF byte array, or null if error result
     */
    public static byte[] resultPdf(Arena arena, MemorySegment resultPtr) {
        try {
            MemorySegment outLen = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment pdfPtr = (MemorySegment) RESULT_PDF.invokeExact(resultPtr, outLen);
            if (pdfPtr.equals(MemorySegment.NULL)) return null;
            long len = outLen.get(ValueLayout.JAVA_LONG, 0);
            if (len <= 0) return null;
            // Reinterpret to the actual size so we can read bytes
            MemorySegment pdfSegment = pdfPtr.reinterpret(len);
            return pdfSegment.toArray(ValueLayout.JAVA_BYTE);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * Get the errors JSON string from a result.
     *
     * @param resultPtr pointer to the compilation result
     * @return JSON array string of error diagnostics
     */
    public static String resultErrors(MemorySegment resultPtr) {
        try {
            MemorySegment ptr = (MemorySegment) RESULT_ERRORS.invokeExact(resultPtr);
            if (ptr.equals(MemorySegment.NULL)) return "[]";
            return ptr.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * Get the warnings JSON string from a result.
     *
     * @param resultPtr pointer to the compilation result
     * @return JSON array string of warning diagnostics
     */
    public static String resultWarnings(MemorySegment resultPtr) {
        try {
            MemorySegment ptr = (MemorySegment) RESULT_WARNINGS.invokeExact(resultPtr);
            if (ptr.equals(MemorySegment.NULL)) return "[]";
            return ptr.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * Free a compilation result.
     *
     * @param resultPtr pointer to the result to free
     */
    public static void resultFree(MemorySegment resultPtr) {
        try {
            RESULT_FREE.invokeExact(resultPtr);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * Invalidate a specific cached template.
     *
     * @param arena     arena for allocating the key string
     * @param enginePtr pointer to the engine
     * @param key       the template cache key to invalidate
     */
    public static void invalidateTemplate(Arena arena, MemorySegment enginePtr, String key) {
        try {
            MemorySegment keyPtr = arena.allocateFrom(key);
            INVALIDATE_TEMPLATE.invokeExact(enginePtr, keyPtr);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * Invalidate all cached templates.
     *
     * @param enginePtr pointer to the engine
     */
    public static void invalidateAll(MemorySegment enginePtr) {
        try {
            INVALIDATE_ALL.invokeExact(enginePtr);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    private static TypstNativeException wrap(Throwable t) {
        if (t instanceof TypstNativeException tne) return tne;
        return new TypstNativeException("Native call failed: " + t.getMessage(), t);
    }
}
