mod engine;
mod world;
mod vfs;
mod fonts;
mod cache;
mod diagnostics;
mod packages;
mod result;

use std::ffi::{CStr, c_char, c_int};
use std::ptr;

use crate::engine::TypstJavaEngine;
use crate::result::TypstResult;

/// Create a new Typst engine.
///
/// `config_json` is a JSON string like `{"template_cache_enabled": true}`.
/// Pass null for defaults (cache enabled).
///
/// Returns a heap-allocated engine pointer. Must be freed with `typst_engine_free`.
#[no_mangle]
pub extern "C" fn typst_engine_new(config_json: *const c_char) -> *mut TypstJavaEngine {
    let cache_enabled = if config_json.is_null() {
        true
    } else {
        let c_str = unsafe { CStr::from_ptr(config_json) };
        match c_str.to_str() {
            Ok(s) => {
                if let Ok(v) = serde_json::from_str::<serde_json::Value>(s) {
                    v.get("template_cache_enabled")
                        .and_then(|v| v.as_bool())
                        .unwrap_or(true)
                } else {
                    true
                }
            }
            Err(_) => true,
        }
    };

    let engine = TypstJavaEngine::new(cache_enabled);
    Box::into_raw(Box::new(engine))
}

/// Free a Typst engine.
#[no_mangle]
pub extern "C" fn typst_engine_free(engine: *mut TypstJavaEngine) {
    if !engine.is_null() {
        unsafe {
            drop(Box::from_raw(engine));
        }
    }
}

/// Add a font from raw byte data.
/// Returns 0 on success, negative on error.
#[no_mangle]
pub extern "C" fn typst_add_font(
    engine: *mut TypstJavaEngine,
    buf: *const u8,
    len: usize,
) -> c_int {
    if engine.is_null() || buf.is_null() || len == 0 {
        return -1;
    }
    let engine = unsafe { &*engine };
    let data = unsafe { std::slice::from_raw_parts(buf, len) };
    engine.add_font(data)
}

/// Add all fonts from a directory.
/// Returns 0 on success, negative on error.
#[no_mangle]
pub extern "C" fn typst_add_font_dir(
    engine: *mut TypstJavaEngine,
    path: *const c_char,
) -> c_int {
    if engine.is_null() || path.is_null() {
        return -1;
    }
    let engine = unsafe { &*engine };
    let c_str = unsafe { CStr::from_ptr(path) };
    match c_str.to_str() {
        Ok(s) => engine.add_font_dir(s),
        Err(_) => -1,
    }
}

/// Compile a template.
///
/// - `template_key`: cache key or file path (required, must not be null)
/// - `source`: template source text, or null to read from file at `template_key`
/// - `data_json`: JSON data to inject as `data.json`, or null for no data
///
/// Returns a heap-allocated TypstResult. Must be freed with `typst_result_free`.
#[no_mangle]
pub extern "C" fn typst_compile(
    engine: *mut TypstJavaEngine,
    template_key: *const c_char,
    source: *const c_char,
    data_json: *const c_char,
) -> *mut TypstResult {
    if engine.is_null() || template_key.is_null() {
        let result = TypstResult::failure(
            r#"[{"severity":"ERROR","message":"null pointer passed to typst_compile","file":"","line":0,"column":0,"hint":null}]"#.to_string(),
            "[]".to_string(),
        );
        return Box::into_raw(Box::new(result));
    }

    let engine = unsafe { &*engine };

    let key = match unsafe { CStr::from_ptr(template_key) }.to_str() {
        Ok(s) => s,
        Err(_) => {
            let result = TypstResult::failure(
                r#"[{"severity":"ERROR","message":"invalid UTF-8 in template_key","file":"","line":0,"column":0,"hint":null}]"#.to_string(),
                "[]".to_string(),
            );
            return Box::into_raw(Box::new(result));
        }
    };

    let source_str = if source.is_null() {
        None
    } else {
        match unsafe { CStr::from_ptr(source) }.to_str() {
            Ok(s) => Some(s),
            Err(_) => {
                let result = TypstResult::failure(
                    r#"[{"severity":"ERROR","message":"invalid UTF-8 in source","file":"","line":0,"column":0,"hint":null}]"#.to_string(),
                    "[]".to_string(),
                );
                return Box::into_raw(Box::new(result));
            }
        }
    };

    let data_str = if data_json.is_null() {
        None
    } else {
        match unsafe { CStr::from_ptr(data_json) }.to_str() {
            Ok(s) => Some(s),
            Err(_) => None,
        }
    };

    let result = engine.compile(key, source_str, data_str);
    Box::into_raw(Box::new(result))
}

/// Check if a compilation result is successful.
/// Returns 1 for success, 0 for failure.
#[no_mangle]
pub extern "C" fn typst_result_is_ok(result: *const TypstResult) -> c_int {
    if result.is_null() {
        return 0;
    }
    let result = unsafe { &*result };
    if result.is_ok() { 1 } else { 0 }
}

/// Get the PDF bytes from a successful result.
/// Sets `*out_len` to the byte count.
/// Returns null if the result is an error.
#[no_mangle]
pub extern "C" fn typst_result_pdf(
    result: *const TypstResult,
    out_len: *mut usize,
) -> *const u8 {
    if result.is_null() {
        if !out_len.is_null() {
            unsafe { *out_len = 0; }
        }
        return ptr::null();
    }
    let result = unsafe { &*result };
    match result.pdf_data() {
        Some((data, len)) => {
            if !out_len.is_null() {
                unsafe { *out_len = len; }
            }
            data.as_ptr()
        }
        None => {
            if !out_len.is_null() {
                unsafe { *out_len = 0; }
            }
            ptr::null()
        }
    }
}

/// Get the errors JSON string from a result.
/// Returns a null-terminated UTF-8 string. The pointer is valid until `typst_result_free`.
#[no_mangle]
pub extern "C" fn typst_result_errors(result: *const TypstResult) -> *const c_char {
    if result.is_null() {
        return ptr::null();
    }
    let result = unsafe { &*result };
    result.errors_ptr()
}

/// Get the warnings JSON string from a result.
/// Returns a null-terminated UTF-8 string. The pointer is valid until `typst_result_free`.
#[no_mangle]
pub extern "C" fn typst_result_warnings(result: *const TypstResult) -> *const c_char {
    if result.is_null() {
        return ptr::null();
    }
    let result = unsafe { &*result };
    result.warnings_ptr()
}

/// Free a compilation result.
#[no_mangle]
pub extern "C" fn typst_result_free(result: *mut TypstResult) {
    if !result.is_null() {
        unsafe {
            drop(Box::from_raw(result));
        }
    }
}

/// Invalidate a specific cached template.
#[no_mangle]
pub extern "C" fn typst_invalidate_template(
    engine: *mut TypstJavaEngine,
    key: *const c_char,
) {
    if engine.is_null() || key.is_null() {
        return;
    }
    let engine = unsafe { &*engine };
    if let Ok(k) = unsafe { CStr::from_ptr(key) }.to_str() {
        engine.invalidate_template(k);
    }
}

/// Invalidate all cached templates.
#[no_mangle]
pub extern "C" fn typst_invalidate_all(engine: *mut TypstJavaEngine) {
    if engine.is_null() {
        return;
    }
    let engine = unsafe { &*engine };
    engine.invalidate_all();
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CString;

    #[test]
    fn test_full_lifecycle() {
        // Create engine
        let engine = typst_engine_new(ptr::null());
        assert!(!engine.is_null());

        // Compile a simple template
        let key = CString::new("test").unwrap();
        let source = CString::new("Hello, World!").unwrap();
        let result = typst_compile(engine, key.as_ptr(), source.as_ptr(), ptr::null());
        assert!(!result.is_null());

        // Check result
        let is_ok = typst_result_is_ok(result);
        assert_eq!(is_ok, 1, "Compilation should succeed");

        // Get PDF
        let mut pdf_len: usize = 0;
        let pdf_ptr = typst_result_pdf(result, &mut pdf_len);
        assert!(!pdf_ptr.is_null(), "PDF pointer should not be null");
        assert!(pdf_len > 0, "PDF length should be > 0");

        // Verify PDF header
        let pdf_bytes = unsafe { std::slice::from_raw_parts(pdf_ptr, pdf_len) };
        assert!(pdf_bytes.starts_with(b"%PDF"), "Should start with %PDF");

        // Free result
        typst_result_free(result);

        // Free engine
        typst_engine_free(engine);
    }

    #[test]
    fn test_null_safety() {
        // All functions should handle null gracefully
        typst_engine_free(ptr::null_mut());
        typst_result_free(ptr::null_mut());

        assert_eq!(typst_result_is_ok(ptr::null()), 0);
        assert!(typst_result_errors(ptr::null()).is_null());
        assert!(typst_result_warnings(ptr::null()).is_null());

        let mut len: usize = 0;
        assert!(typst_result_pdf(ptr::null(), &mut len).is_null());
        assert_eq!(len, 0);

        assert_eq!(typst_add_font(ptr::null_mut(), ptr::null(), 0), -1);
        assert_eq!(typst_add_font_dir(ptr::null_mut(), ptr::null()), -1);

        typst_invalidate_template(ptr::null_mut(), ptr::null());
        typst_invalidate_all(ptr::null_mut());

        // Compile with null engine
        let key = CString::new("test").unwrap();
        let result = typst_compile(ptr::null_mut(), key.as_ptr(), ptr::null(), ptr::null());
        assert!(!result.is_null());
        assert_eq!(typst_result_is_ok(result), 0);
        typst_result_free(result);
    }

    #[test]
    fn test_compile_with_data() {
        let engine = typst_engine_new(ptr::null());

        let key = CString::new("test").unwrap();
        let source = CString::new(
            r#"#let data = json("data.json")
= Hello, #data.name!
"#,
        )
        .unwrap();
        let data = CString::new(r#"{"name":"Typst"}"#).unwrap();

        let result = typst_compile(engine, key.as_ptr(), source.as_ptr(), data.as_ptr());
        let is_ok = typst_result_is_ok(result);

        if is_ok != 1 {
            let errors_ptr = typst_result_errors(result);
            let errors = unsafe { CStr::from_ptr(errors_ptr) }.to_str().unwrap();
            panic!("Compilation failed: {}", errors);
        }

        assert_eq!(is_ok, 1, "Compile with data should succeed");

        typst_result_free(result);
        typst_engine_free(engine);
    }

    #[test]
    fn test_error_diagnostics() {
        let engine = typst_engine_new(ptr::null());

        let key = CString::new("test").unwrap();
        let source = CString::new("#nonexistent()").unwrap();

        let result = typst_compile(engine, key.as_ptr(), source.as_ptr(), ptr::null());
        assert_eq!(typst_result_is_ok(result), 0, "Should fail");

        let errors_ptr = typst_result_errors(result);
        assert!(!errors_ptr.is_null());
        let errors = unsafe { CStr::from_ptr(errors_ptr) }.to_str().unwrap();
        assert!(
            errors.contains("ERROR"),
            "Error JSON should contain ERROR, got: {}",
            errors
        );

        typst_result_free(result);
        typst_engine_free(engine);
    }

    #[test]
    fn test_config_parsing() {
        let config = CString::new(r#"{"template_cache_enabled": false}"#).unwrap();
        let engine = typst_engine_new(config.as_ptr());
        assert!(!engine.is_null());

        // Should still compile fine even with cache disabled
        let key = CString::new("test").unwrap();
        let source = CString::new("Hello!").unwrap();
        let result = typst_compile(engine, key.as_ptr(), source.as_ptr(), ptr::null());
        assert_eq!(typst_result_is_ok(result), 1);

        typst_result_free(result);
        typst_engine_free(engine);
    }
}
