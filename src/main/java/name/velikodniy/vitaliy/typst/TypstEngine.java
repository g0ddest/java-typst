package name.velikodniy.vitaliy.typst;

import name.velikodniy.vitaliy.typst.internal.PackageManager;
import name.velikodniy.vitaliy.typst.internal.TypstNative;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * High-level Typst compilation engine. Manages the native engine lifecycle,
 * font loading, and template creation.
 *
 * <p>Usage:
 * <pre>{@code
 * try (var engine = TypstEngine.builder()
 *         .addFontDir(Path.of("/usr/share/fonts"))
 *         .enableTemplateCache(true)
 *         .build()) {
 *     byte[] pdf = engine.template(Path.of("invoice.typ"))
 *             .data("customer", customer)
 *             .renderPdf();
 * }
 * }</pre>
 *
 * <p>Implements {@link AutoCloseable} to ensure the native engine is freed.
 *
 * <h2>Thread safety</h2>
 * <ul>
 *   <li>{@code TypstEngine} instances are thread-safe; a single engine can (and
 *       should) be shared across request threads. Concurrent renders proceed in
 *       parallel — they take a shared read lock against the engine pointer.</li>
 *   <li>{@link TypstTemplate} instances are <em>not</em> thread-safe. Create a
 *       new template (via {@link #template(Path)} or {@link #template(String, String)})
 *       per render call.</li>
 *   <li>{@link #close()} acquires an exclusive write lock, so it blocks until
 *       all in-flight renders complete and prevents new renders from starting.
 *       After {@code close()} returns, further calls into the engine throw
 *       {@link TypstEngineException}.</li>
 *   <li>If the user forgets to {@code close()} the engine, a {@link Cleaner}
 *       registration releases the native engine when the {@code TypstEngine}
 *       object becomes phantom-reachable. Explicit {@code close()} remains
 *       strongly preferred — Cleaner timing is non-deterministic.</li>
 *   <li>If a custom {@link TypstPackageResolver} throws during a render, the
 *       original exception (e.g. {@link java.io.IOException},
 *       {@link TypstPackageNotFoundException}) is preserved and surfaced
 *       through {@link TypstTemplate#renderPdf()} rather than being collapsed
 *       into a generic Typst diagnostic.</li>
 * </ul>
 */
public final class TypstEngine implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    private final AtomicReference<MemorySegment> enginePtr;
    private final Arena nativeArena;
    private final PackageManager packageManager;
    private final AutoCloseable resolver;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private final Cleaner.Cleanable cleanable;

    private TypstEngine(MemorySegment enginePtr, Arena nativeArena,
                        PackageManager packageManager, AutoCloseable resolver) {
        this.enginePtr = new AtomicReference<>(enginePtr);
        this.nativeArena = nativeArena;
        this.packageManager = packageManager;
        this.resolver = resolver;
        // Register a cleanup hook for the case where the user forgets close().
        // The runnable must NOT capture `this`, only the state it needs.
        this.cleanable = CLEANER.register(this,
                new CleanupState(this.enginePtr, this.nativeArena, this.resolver));
    }

    /**
     * Create a new builder for configuring a TypstEngine.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a template from a file path. The path is used both as the cache key
     * and as the source file location (the native engine reads the file).
     *
     * @param path path to the .typ template file
     * @return a new TypstTemplate for data binding and rendering
     */
    public TypstTemplate template(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        return new TypstTemplate(this, path.toString(), null);
    }

    /**
     * Create a template from an in-memory source string with a given name.
     * The name is used as the cache key.
     *
     * @param name   cache key / logical name for the template
     * @param source Typst source text
     * @return a new TypstTemplate for data binding and rendering
     */
    public TypstTemplate template(String name, String source) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(source, "source must not be null");
        return new TypstTemplate(this, name, source);
    }

    /**
     * Invalidate a cached template by file path.
     *
     * @param path the path that was used when creating the template
     */
    public void invalidateTemplate(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        invalidateTemplate(path.toString());
    }

    /**
     * Invalidate a cached template by its name/key.
     *
     * @param key the name or path string used as the template key
     */
    public void invalidateTemplate(String key) {
        Objects.requireNonNull(key, "key must not be null");
        lifecycleLock.readLock().lock();
        try {
            MemorySegment ptr = getEnginePtr();
            try (var localArena = Arena.ofConfined()) {
                TypstNative.invalidateTemplate(localArena, ptr, key);
            }
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * Invalidate all cached templates.
     */
    public void invalidateAllTemplates() {
        lifecycleLock.readLock().lock();
        try {
            TypstNative.invalidateAll(getEnginePtr());
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * Package-private accessor for TypstTemplate to use during compilation.
     */
    MemorySegment enginePtr() {
        return getEnginePtr();
    }

    /**
     * Package-private: returns the package manager for this engine.
     */
    PackageManager packageManager() {
        return packageManager;
    }

    /**
     * Package-private: lock used by {@link TypstTemplate#renderPdf()} to keep
     * the native engine pointer alive for the duration of a compile() call.
     * Multiple renders may hold the read lock concurrently.
     */
    ReentrantReadWriteLock.ReadLock readLock() {
        return lifecycleLock.readLock();
    }

    /**
     * Package-private: lock acquired by {@link #close()} to block until all
     * in-flight renders finish before freeing the native engine.
     */
    ReentrantReadWriteLock.WriteLock writeLock() {
        return lifecycleLock.writeLock();
    }

    @Override
    public void close() {
        // Block until in-flight renders complete; new renders see a closed engine.
        lifecycleLock.writeLock().lock();
        try {
            MemorySegment ptr = enginePtr.getAndSet(null);
            if (ptr != null) {
                TypstNative.engineFree(ptr);
                nativeArena.close();
                try { resolver.close(); } catch (Exception _) { /* ignore */ }
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
        // Deregister the cleaner now that we've cleaned up explicitly.
        cleanable.clean();
    }

    private MemorySegment getEnginePtr() {
        MemorySegment ptr = enginePtr.get();
        if (ptr == null) {
            throw new TypstEngineException("Engine is already closed");
        }
        return ptr;
    }

    /**
     * Cleaner-invoked fallback that releases the native engine if the user
     * forgot {@link #close()}. Must not hold a reference to the enclosing
     * {@code TypstEngine} instance or the Cleaner registration would pin it
     * forever.
     */
    private record CleanupState(AtomicReference<MemorySegment> enginePtr,
                                Arena nativeArena,
                                AutoCloseable resolver) implements Runnable {
        @Override
        public void run() {
            MemorySegment ptr = enginePtr.getAndSet(null);
            if (ptr != null) {
                try {
                    TypstNative.engineFree(ptr);
                } catch (Throwable _) { /* best-effort cleanup */ }
                try {
                    nativeArena.close();
                } catch (Throwable _) { /* best-effort cleanup */ }
                try {
                    resolver.close();
                } catch (Exception _) { /* best-effort cleanup */ }
            }
        }
    }

    /**
     * Fluent builder for constructing a {@link TypstEngine}.
     */
    public static final class Builder {
        private static final String DEFAULT_REGISTRY = "https://packages.typst.org";

        private boolean templateCacheEnabled = true;
        private String registry = null;
        private TypstPackageResolver packageResolver = null;
        private final List<FontSource> fontSources = new ArrayList<>();

        private Builder() {}

        /**
         * Add a font from raw byte data.
         *
         * @param fontData the font file contents (TTF, OTF, TTC, etc.)
         * @return this builder
         */
        public Builder addFont(byte[] fontData) {
            Objects.requireNonNull(fontData, "fontData must not be null");
            fontSources.add(new FontBytes(fontData.clone()));
            return this;
        }

        /**
         * Add a font from an InputStream. The stream is read fully and closed.
         *
         * @param inputStream the font data stream
         * @return this builder
         * @throws TypstEngineException if reading the stream fails
         */
        public Builder addFont(InputStream inputStream) {
            Objects.requireNonNull(inputStream, "inputStream must not be null");
            try (inputStream) {
                fontSources.add(new FontBytes(inputStream.readAllBytes()));
            } catch (IOException e) {
                throw new TypstEngineException("Failed to read font from InputStream", e);
            }
            return this;
        }

        /**
         * Add all fonts found in a directory (recursive).
         *
         * @param dir path to the font directory
         * @return this builder
         */
        public Builder addFontDir(Path dir) {
            Objects.requireNonNull(dir, "dir must not be null");
            fontSources.add(new FontDir(dir.toAbsolutePath().toString()));
            return this;
        }

        /**
         * Set a custom package registry URL, replacing the default
         * {@code https://packages.typst.org}. Creates an HTTP-based resolver.
         *
         * @param registryUrl the registry base URL
         * @return this builder
         */
        public Builder registry(String registryUrl) {
            Objects.requireNonNull(registryUrl, "registryUrl must not be null");
            this.registry = registryUrl;
            return this;
        }

        /**
         * Set a custom package resolver. Takes precedence over {@link #registry(String)}.
         *
         * @param resolver the resolver to use for fetching packages
         * @return this builder
         */
        public Builder packageResolver(TypstPackageResolver resolver) {
            Objects.requireNonNull(resolver, "resolver must not be null");
            this.packageResolver = resolver;
            return this;
        }

        /**
         * Enable or disable the template cache in the native engine.
         * Default is {@code true}.
         *
         * @param enabled whether to cache compiled templates
         * @return this builder
         */
        public Builder enableTemplateCache(boolean enabled) {
            this.templateCacheEnabled = enabled;
            return this;
        }

        /**
         * Build the engine, initializing the native library and loading fonts.
         *
         * @return a new TypstEngine
         * @throws TypstEngineException if engine creation or font loading fails
         */
        public TypstEngine build() {
            // Shared (not confined): the engine is shareable across threads, the
            // resolver upcall stub bound to this arena is invoked on arbitrary
            // render threads, and close()/the Cleaner may run on any thread.
            Arena arena = Arena.ofShared();
            String config = "{\"template_cache_enabled\":" + templateCacheEnabled + "}";
            TypstPackageResolver resolver = this.packageResolver != null
                    ? this.packageResolver
                    : new HttpPackageResolver(
                            registry != null ? registry : DEFAULT_REGISTRY);
            // Track resolver for cleanup if it's AutoCloseable (e.g. HttpPackageResolver)
            AutoCloseable closeable = resolver instanceof AutoCloseable ac ? ac : () -> {};
            var packageManager = new PackageManager(resolver);
            MemorySegment resolverStub = TypstNative.createResolverStub(arena);
            MemorySegment ptr = TypstNative.engineNew(arena, config, resolverStub);
            if (ptr == null || ptr.equals(MemorySegment.NULL)) {
                throw new TypstEngineException("Failed to create native engine");
            }

            TypstEngine engine = new TypstEngine(ptr, arena, packageManager, closeable);

            // Load fonts
            try (var fontArena = Arena.ofConfined()) {
                for (FontSource source : fontSources) {
                    switch (source) {
                        case FontBytes(var data) -> {
                            int rc = TypstNative.addFont(fontArena, ptr, data);
                            if (rc != 0) {
                                engine.close();
                                throw new TypstEngineException(
                                        "Failed to add font (error code: " + rc + ")");
                            }
                        }
                        case FontDir(var path) -> {
                            int rc = TypstNative.addFontDir(fontArena, ptr, path);
                            if (rc != 0) {
                                engine.close();
                                throw new TypstEngineException(
                                        "Failed to add font directory '" + path
                                                + "' (error code: " + rc + ")");
                            }
                        }
                    }
                }
            }

            return engine;
        }

        // Internal sealed hierarchy for deferred font loading
        private sealed interface FontSource permits FontBytes, FontDir {}

        private record FontBytes(byte[] data) implements FontSource {
            @Override public boolean equals(Object o) {
                return o instanceof FontBytes(var other) && Arrays.equals(data, other);
            }
            @Override public int hashCode() { return Arrays.hashCode(data); }
            @Override public String toString() { return "FontBytes[" + data.length + " bytes]"; }
        }

        private record FontDir(String path) implements FontSource {}
    }
}
