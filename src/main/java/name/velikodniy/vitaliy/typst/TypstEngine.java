package name.velikodniy.vitaliy.typst;

import name.velikodniy.vitaliy.typst.internal.TypstNative;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

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
 */
public final class TypstEngine implements AutoCloseable {

    private final AtomicReference<MemorySegment> enginePtr;

    private TypstEngine(MemorySegment enginePtr) {
        this.enginePtr = new AtomicReference<>(enginePtr);
    }

    /**
     * Create a new builder for configuring a TypstEngine.
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
        MemorySegment ptr = getEnginePtr();
        try (var localArena = Arena.ofConfined()) {
            TypstNative.invalidateTemplate(localArena, ptr, key);
        }
    }

    /**
     * Invalidate all cached templates.
     */
    public void invalidateAllTemplates() {
        TypstNative.invalidateAll(getEnginePtr());
    }

    /**
     * Package-private accessor for TypstTemplate to use during compilation.
     */
    MemorySegment enginePtr() {
        return getEnginePtr();
    }

    @Override
    public void close() {
        MemorySegment ptr = enginePtr.getAndSet(null);
        if (ptr != null) {
            TypstNative.engineFree(ptr);
        }
    }

    private MemorySegment getEnginePtr() {
        MemorySegment ptr = enginePtr.get();
        if (ptr == null) {
            throw new TypstEngineException("Engine is already closed");
        }
        return ptr;
    }

    /**
     * Fluent builder for constructing a {@link TypstEngine}.
     */
    public static final class Builder {
        private boolean templateCacheEnabled = true;
        private final java.util.List<FontSource> fontSources = new java.util.ArrayList<>();

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
            Arena arena = Arena.ofAuto();
            String config = "{\"template_cache_enabled\":" + templateCacheEnabled + "}";
            MemorySegment ptr = TypstNative.engineNew(arena, config);
            if (ptr == null || ptr.equals(MemorySegment.NULL)) {
                throw new TypstEngineException("Failed to create native engine");
            }

            TypstEngine engine = new TypstEngine(ptr);

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
