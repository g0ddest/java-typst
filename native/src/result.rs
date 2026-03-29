// Compilation result types

use std::ffi::CString;

/// Holds the result of a Typst compilation: either PDF bytes or error diagnostics.
pub struct TypstResult {
    ok: bool,
    pdf: Option<Vec<u8>>,
    errors_json: CString,
    warnings_json: CString,
}

impl TypstResult {
    /// Create a successful result with PDF bytes and optional warnings.
    pub fn success(pdf: Vec<u8>, warnings_json: String) -> Self {
        Self {
            ok: true,
            pdf: Some(pdf),
            errors_json: CString::new("[]").unwrap(),
            warnings_json: CString::new(warnings_json).unwrap_or_else(|_| {
                CString::new("[]").unwrap()
            }),
        }
    }

    /// Create a failure result with error and warning diagnostics.
    pub fn failure(errors_json: String, warnings_json: String) -> Self {
        Self {
            ok: false,
            pdf: None,
            errors_json: CString::new(errors_json).unwrap_or_else(|_| {
                CString::new("[]").unwrap()
            }),
            warnings_json: CString::new(warnings_json).unwrap_or_else(|_| {
                CString::new("[]").unwrap()
            }),
        }
    }

    /// Whether the compilation succeeded.
    pub fn is_ok(&self) -> bool {
        self.ok
    }

    /// Get the PDF bytes and length, if compilation succeeded.
    pub fn pdf_data(&self) -> Option<(&[u8], usize)> {
        self.pdf.as_ref().map(|v| (v.as_slice(), v.len()))
    }

    /// Get a pointer to the errors JSON string (null-terminated).
    pub fn errors_ptr(&self) -> *const std::ffi::c_char {
        self.errors_json.as_ptr()
    }

    /// Get a pointer to the warnings JSON string (null-terminated).
    pub fn warnings_ptr(&self) -> *const std::ffi::c_char {
        self.warnings_json.as_ptr()
    }
}
