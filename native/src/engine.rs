// Typst compilation engine

use std::sync::Arc;

use parking_lot::RwLock;
use typst::syntax::VirtualPath;
use typst_layout::PagedDocument;
use typst_pdf::PdfOptions;

use crate::cache::{CachedTemplate, TemplateCache};
use crate::diagnostics::diagnostics_to_json;
use crate::fonts::FontManager;
use crate::packages::ResolveFn;
use crate::result::TypstResult;
use crate::world::TypstJavaWorld;

/// The main engine that owns fonts, cache, and compiles templates.
pub struct TypstJavaEngine {
    /// Shared font manager.
    font_manager: Arc<FontManager>,
    /// Whether template caching is enabled.
    cache_enabled: bool,
    /// Template source cache.
    cache: RwLock<TemplateCache>,
    /// Package resolver callback provided by the host (Java).
    download_fn: ResolveFn,
}

impl TypstJavaEngine {
    /// Create a new engine.
    pub fn new(cache_enabled: bool, download_fn: ResolveFn) -> Self {
        Self {
            font_manager: Arc::new(FontManager::new()),
            cache_enabled,
            cache: RwLock::new(TemplateCache::new()),
            download_fn,
        }
    }

    /// Add font data (raw bytes). Returns 0 on success, -1 on failure.
    pub fn add_font(&self, data: &[u8]) -> i32 {
        self.font_manager.add_font_data(data)
    }

    /// Add a directory of fonts. Returns 0 on success, -1 on failure.
    pub fn add_font_dir(&self, path: &str) -> i32 {
        self.font_manager.add_font_dir(std::path::Path::new(path))
    }

    /// Compile a template.
    ///
    /// - `template_key`: cache key / file path
    /// - `source`: if non-None, the template source text (cached under template_key)
    /// - `data_json`: if non-None, injected as data.json in VFS
    /// - `root`: if non-None, the explicit root directory for file resolution;
    ///   otherwise the legacy default is used (CWD for string templates, the
    ///   file's parent directory for file templates)
    pub fn compile(
        &self,
        template_key: &str,
        source: Option<&str>,
        data_json: Option<&str>,
        root: Option<&str>,
    ) -> TypstResult {
        // 1. Resolve the template source text
        let source_text = match self.resolve_source(template_key, source) {
            Ok(s) => s,
            Err(err) => {
                let error_json = serde_json::json!([{
                    "severity": "ERROR",
                    "message": err,
                    "file": "",
                    "line": 0,
                    "column": 0,
                    "hint": null
                }]);
                return TypstResult::failure(error_json.to_string(), "[]".to_string());
            }
        };

        // 2. Determine the root directory and the main file's virtual path
        let (root, main_vpath) = match resolve_root(template_key, source.is_some(), root) {
            Ok(pair) => pair,
            Err(err) => {
                let error_json = serde_json::json!([{
                    "severity": "ERROR",
                    "message": err,
                    "file": "",
                    "line": 0,
                    "column": 0,
                    "hint": null
                }]);
                return TypstResult::failure(error_json.to_string(), "[]".to_string());
            }
        };

        // 3. Build font book snapshot
        let book = self.font_manager.book();

        // 4. Create the world
        let world = TypstJavaWorld::new(
            self.font_manager.clone(),
            book,
            root,
            main_vpath,
            source_text,
            data_json.map(|s| s.to_string()),
            self.download_fn,
        );

        // 5. Compile
        let result = typst::compile::<PagedDocument>(&world);

        // 6. Process warnings
        let warnings_json = diagnostics_to_json(&result.warnings, &world);

        // 7. Process output
        match result.output {
            Ok(document) => {
                // Export to PDF
                let options = PdfOptions::default();
                match typst_pdf::pdf(&document, &options) {
                    Ok(pdf_bytes) => TypstResult::success(pdf_bytes, warnings_json),
                    Err(errors) => {
                        let errors_json = diagnostics_to_json(&errors, &world);
                        TypstResult::failure(errors_json, warnings_json)
                    }
                }
            }
            Err(errors) => {
                let errors_json = diagnostics_to_json(&errors, &world);
                TypstResult::failure(errors_json, warnings_json)
            }
        }
    }

    /// Resolve the source text for a template, using the cache if enabled.
    fn resolve_source(
        &self,
        template_key: &str,
        source: Option<&str>,
    ) -> Result<String, String> {
        // If source is provided directly, use it and cache it
        if let Some(src) = source {
            let text = src.to_string();
            if self.cache_enabled {
                let entry = CachedTemplate::from_string(template_key.to_string(), text.clone());
                self.cache.write().insert(template_key.to_string(), entry);
            }
            return Ok(text);
        }

        // Source is None — treat template_key as a file path
        // Check cache first
        if self.cache_enabled {
            let cache = self.cache.read();
            if let Some(cached) = cache.get(template_key) {
                return Ok(cached.to_string());
            }
        }

        // Read from disk
        let path = std::path::Path::new(template_key);
        let text = std::fs::read_to_string(path)
            .map_err(|e| format!("failed to read template file '{}': {}", template_key, e))?;

        // Cache it with mtime
        if self.cache_enabled {
            if let Ok(metadata) = std::fs::metadata(path) {
                if let Ok(mtime) = metadata.modified() {
                    let entry = CachedTemplate::from_file(template_key, text.clone(), mtime);
                    self.cache.write().insert(template_key.to_string(), entry);
                }
            }
        }

        Ok(text)
    }

    /// Invalidate a specific cached template.
    pub fn invalidate_template(&self, key: &str) {
        self.cache.write().invalidate(key);
    }

    /// Invalidate all cached templates.
    pub fn invalidate_all(&self) {
        self.cache.write().invalidate_all();
    }
}

/// Determine the real root directory and the main file's virtual path.
///
/// String templates always live directly at the root as `main.typ`. File
/// templates keep their real position within the root (like `typst --root`);
/// with no explicit root, the root defaults to the file's parent directory,
/// which yields a virtual path of just the file name. Errors if an explicit
/// root does not contain the template file (matching the typst CLI).
fn resolve_root(
    template_key: &str,
    is_string_template: bool,
    root: Option<&str>,
) -> Result<(std::path::PathBuf, VirtualPath), String> {
    use std::path::{Path, PathBuf};

    if let Some(r) = root {
        if r.trim().is_empty() {
            return Err("root directory must not be empty".to_string());
        }
    }

    if is_string_template {
        let root_dir = match root {
            Some(r) => PathBuf::from(r),
            None => std::env::current_dir().unwrap_or_else(|_| std::env::temp_dir()),
        };
        let vpath = VirtualPath::new("main.typ")
            .expect("static path 'main.typ' is always valid");
        return Ok((root_dir, vpath));
    }

    let file = Path::new(template_key);
    let root_dir = match root {
        Some(r) => PathBuf::from(r),
        None => file
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .map(|p| p.to_path_buf())
            .unwrap_or_else(|| std::env::current_dir().unwrap_or_else(|_| std::env::temp_dir())),
    };

    // Canonicalize both sides so the lexical containment check is immune to
    // relative paths and symlinked directories (e.g. /var vs /private/var on
    // macOS). Reads still go through `root_dir` as given.
    let canon_root = std::fs::canonicalize(&root_dir).map_err(|e| {
        format!("root directory '{}' is not accessible: {}", root_dir.display(), e)
    })?;
    let canon_file = std::fs::canonicalize(file).map_err(|e| {
        format!("template file '{}' is not accessible: {}", file.display(), e)
    })?;

    let vpath = VirtualPath::virtualize(&canon_root, &canon_file).map_err(|_| {
        format!(
            "template file '{}' must be contained in the root directory '{}'",
            file.display(),
            root_dir.display()
        )
    })?;

    Ok((canon_root, vpath))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::c_int;

    /// Stub download function for tests that don't need packages.
    extern "C" fn noop_resolve(
        _ns: *const std::ffi::c_char,
        _name: *const std::ffi::c_char,
        _ver: *const std::ffi::c_char,
        _out_path: *mut *const std::ffi::c_char,
    ) -> c_int {
        -1 // always fail — no packages needed
    }

    #[test]
    fn test_simple_compile() {
        let engine = TypstJavaEngine::new(true, noop_resolve);
        let result = engine.compile("test", Some("Hello, World!"), None, None);
        assert!(result.is_ok(), "Simple compile should succeed");
        assert!(result.pdf_data().is_some(), "Should produce PDF bytes");
        let (data, len) = result.pdf_data().unwrap();
        assert!(len > 0, "PDF should not be empty");
        assert!(data.starts_with(b"%PDF"), "PDF should start with %PDF header");
    }

    #[test]
    fn test_compile_with_data_json() {
        let engine = TypstJavaEngine::new(true, noop_resolve);
        let source = r#"
#let data = json("data.json")
Hello, #data.name!
"#;
        let data_json = r#"{"name": "World"}"#;
        let result = engine.compile("test", Some(source), Some(data_json), None);
        assert!(result.is_ok(), "Compile with data should succeed");
        assert!(result.pdf_data().is_some());
    }

    #[test]
    fn test_string_template_with_explicit_root_resolves_import() {
        let dir = std::env::temp_dir().join("typst_java_test_string_root");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("helper.typ"), "#let greet(name) = [Hello, #name!]\n").unwrap();

        let engine = TypstJavaEngine::new(true, noop_resolve);
        let source = "#import \"helper.typ\": greet\n#greet(\"Root\")\n";
        let result = engine.compile("test", Some(source), None, Some(dir.to_str().unwrap()));

        std::fs::remove_dir_all(&dir).ok();
        assert!(result.is_ok(), "Compile with explicit root should resolve the import");
        assert!(result.pdf_data().is_some());
    }

    #[test]
    fn test_file_template_sibling_import_with_ancestor_root() {
        let dir = std::env::temp_dir().join("typst_java_test_file_ancestor_root");
        let sub = dir.join("sub");
        std::fs::create_dir_all(&sub).unwrap();
        std::fs::write(sub.join("helper.typ"), "#let greet(name) = [Hi, #name!]\n").unwrap();
        let main_path = sub.join("main.typ");
        std::fs::write(&main_path, "#import \"helper.typ\": greet\n#greet(\"Sub\")\n").unwrap();

        let engine = TypstJavaEngine::new(true, noop_resolve);
        // Like `typst compile --root dir sub/main.typ`: the relative import must
        // resolve against the file's own directory, not against the root.
        let result = engine.compile(
            main_path.to_str().unwrap(),
            None,
            None,
            Some(dir.to_str().unwrap()),
        );

        std::fs::remove_dir_all(&dir).ok();
        assert!(
            result.is_ok(),
            "Sibling import must resolve relative to the template file: {:?}",
            unsafe { std::ffi::CStr::from_ptr(result.errors_ptr()) }
        );
    }

    #[test]
    fn test_file_template_outside_explicit_root_errors() {
        let dir_a = std::env::temp_dir().join("typst_java_test_outside_root_a");
        let dir_b = std::env::temp_dir().join("typst_java_test_outside_root_b");
        std::fs::create_dir_all(&dir_a).unwrap();
        std::fs::create_dir_all(&dir_b).unwrap();
        let main_path = dir_a.join("main.typ");
        std::fs::write(&main_path, "Hello\n").unwrap();

        let engine = TypstJavaEngine::new(true, noop_resolve);
        let result = engine.compile(
            main_path.to_str().unwrap(),
            None,
            None,
            Some(dir_b.to_str().unwrap()),
        );

        let errors = unsafe { std::ffi::CStr::from_ptr(result.errors_ptr()) }
            .to_str().unwrap().to_string();
        std::fs::remove_dir_all(&dir_a).ok();
        std::fs::remove_dir_all(&dir_b).ok();
        assert!(!result.is_ok(), "File outside the explicit root must be rejected");
        assert!(errors.contains("must be contained in the root directory"),
                "Error should explain the containment failure, got: {errors}");
    }

    #[test]
    fn test_empty_root_returns_error() {
        let engine = TypstJavaEngine::new(true, noop_resolve);
        let result = engine.compile("test", Some("Hello"), None, Some(""));
        let errors = unsafe { std::ffi::CStr::from_ptr(result.errors_ptr()) }
            .to_str().unwrap();
        assert!(!result.is_ok(), "Empty root must be rejected");
        assert!(errors.contains("root directory must not be empty"), "got: {errors}");
    }

    #[test]
    fn test_traversal_escape_is_denied_and_content_not_leaked() {
        let parent = std::env::temp_dir().join("typst_java_test_traversal");
        let root = parent.join("root");
        std::fs::create_dir_all(&root).unwrap();
        let secret = "SUPER-SECRET-CONTENT-42";
        std::fs::write(parent.join("secret.typ"), format!("#let leak = [{secret}]\n")).unwrap();

        let engine = TypstJavaEngine::new(true, noop_resolve);
        let source = "#import \"../secret.typ\": leak\n#leak\n";
        let result = engine.compile("test", Some(source), None, Some(root.to_str().unwrap()));

        let errors = unsafe { std::ffi::CStr::from_ptr(result.errors_ptr()) }
            .to_str().unwrap().to_string();
        std::fs::remove_dir_all(&parent).ok();
        assert!(!result.is_ok(), "Escaping the root via ../ must fail");
        assert!(!errors.contains(secret), "Error output must not leak file content");
    }

    #[test]
    fn test_data_json_reaches_nested_file_template() {
        let dir = std::env::temp_dir().join("typst_java_test_nested_data");
        let sub = dir.join("sub");
        std::fs::create_dir_all(&sub).unwrap();
        let main_path = sub.join("main.typ");
        std::fs::write(&main_path, "#let d = json(\"data.json\")\nHello, #d.name!\n").unwrap();

        let engine = TypstJavaEngine::new(true, noop_resolve);
        let result = engine.compile(
            main_path.to_str().unwrap(),
            None,
            Some(r#"{"name": "Nested"}"#),
            Some(dir.to_str().unwrap()),
        );

        let errors = unsafe { std::ffi::CStr::from_ptr(result.errors_ptr()) }
            .to_str().unwrap().to_string();
        std::fs::remove_dir_all(&dir).ok();
        assert!(result.is_ok(),
                "Bound data must reach a template nested under the root: {errors}");
    }

    #[test]
    fn test_file_template_without_root_keeps_sibling_imports() {
        let dir = std::env::temp_dir().join("typst_java_test_legacy_file");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("helper.typ"), "#let greet(name) = [Hey, #name!]\n").unwrap();
        let main_path = dir.join("main.typ");
        std::fs::write(&main_path, "#import \"helper.typ\": greet\n#greet(\"Legacy\")\n").unwrap();

        let engine = TypstJavaEngine::new(true, noop_resolve);
        let result = engine.compile(main_path.to_str().unwrap(), None, None, None);

        std::fs::remove_dir_all(&dir).ok();
        assert!(result.is_ok(), "Legacy parent-dir root behavior must be preserved");
    }

    #[test]
    fn test_invalid_template_returns_errors() {
        let engine = TypstJavaEngine::new(true, noop_resolve);
        let source = "#unknown_function()";
        let result = engine.compile("test", Some(source), None, None);
        assert!(!result.is_ok(), "Invalid template should fail");
        let errors = unsafe { std::ffi::CStr::from_ptr(result.errors_ptr()) }
            .to_str()
            .unwrap();
        assert!(errors.contains("ERROR"), "Errors should contain ERROR severity");
    }

    #[test]
    fn test_compile_with_custom_registry_package() {
        use std::io::{Read, Write};
        use std::net::TcpListener;
        use std::thread;
        use crate::packages::tests::{
            test_resolve, cleanup_test_package_cache, REGISTRY_TEST_LOCK, TEST_SERVER_PORT,
        };
        use std::sync::atomic::Ordering;

        let _guard = REGISTRY_TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());

        let mut tar_builder = tar::Builder::new(Vec::new());

        let typst_toml = b"[package]\nname = \"test-hello\"\nversion = \"0.1.0\"\nentrypoint = \"lib.typ\"\n";
        let mut header = tar::Header::new_gnu();
        header.set_path("typst.toml").unwrap();
        header.set_size(typst_toml.len() as u64);
        header.set_mode(0o644);
        header.set_cksum();
        tar_builder.append(&header, &typst_toml[..]).unwrap();

        let lib_typ = b"#let greet(name) = [Hello, #name!]\n";
        let mut header = tar::Header::new_gnu();
        header.set_path("lib.typ").unwrap();
        header.set_size(lib_typ.len() as u64);
        header.set_mode(0o644);
        header.set_cksum();
        tar_builder.append(&header, &lib_typ[..]).unwrap();

        let tar_data = tar_builder.into_inner().unwrap();
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        encoder.write_all(&tar_data).unwrap();
        let tar_gz = encoder.finish().unwrap();

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();

        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut buf = [0u8; 4096];
            let _ = stream.read(&mut buf);

            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nContent-Type: application/gzip\r\n\r\n",
                tar_gz.len()
            );
            stream.write_all(response.as_bytes()).unwrap();
            stream.write_all(&tar_gz).unwrap();
            stream.flush().unwrap();
        });

        cleanup_test_package_cache();

        TEST_SERVER_PORT.store(port, Ordering::Relaxed);
        let engine = TypstJavaEngine::new(true, test_resolve);

        let source = r#"#import "@test-local/test-hello:0.1.0": greet
#greet("Typst")
"#;
        let result = engine.compile("test", Some(source), None, None);
        assert!(result.is_ok(), "Compile with custom registry package should succeed");
        assert!(result.pdf_data().is_some(), "Should produce PDF");

        cleanup_test_package_cache();
        server.join().unwrap();
    }
}
