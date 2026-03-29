// Error and warning diagnostics

use typst::diag::{Severity, SourceDiagnostic};
use typst::World;

/// Convert a slice of SourceDiagnostic to a JSON string array.
/// Each diagnostic becomes an object with severity, message, file, line, column, hints.
pub fn diagnostics_to_json(diagnostics: &[SourceDiagnostic], world: &dyn World) -> String {
    let entries: Vec<serde_json::Value> = diagnostics
        .iter()
        .map(|diag| diagnostic_to_json(diag, world))
        .collect();

    serde_json::to_string(&entries).unwrap_or_else(|_| "[]".to_string())
}

fn diagnostic_to_json(diag: &SourceDiagnostic, world: &dyn World) -> serde_json::Value {
    let severity = match diag.severity {
        Severity::Error => "ERROR",
        Severity::Warning => "WARNING",
    };

    let message = diag.message.to_string();

    // Try to resolve file/line/column from span
    let (file, line, column) = if let Some(id) = diag.span.id() {
        let file_path = id.vpath().as_rooted_path().display().to_string();
        if let Ok(source) = world.source(id) {
            // Try to get position from span range
            if let Some(range) = world.range(diag.span) {
                let line = source.byte_to_line(range.start).unwrap_or(0);
                let col = source.byte_to_column(range.start).unwrap_or(0);
                (file_path, line + 1, col + 1) // 1-based
            } else {
                (file_path, 0, 0)
            }
        } else {
            (file_path, 0, 0)
        }
    } else {
        (String::new(), 0, 0)
    };

    // Collect hints into a single string (or null if empty)
    let hints: Option<String> = if diag.hints.is_empty() {
        None
    } else {
        Some(
            diag.hints
                .iter()
                .map(|h| h.to_string())
                .collect::<Vec<_>>()
                .join("; "),
        )
    };

    serde_json::json!({
        "severity": severity,
        "message": message,
        "file": file,
        "line": line,
        "column": column,
        "hint": hints,
    })
}

use typst::WorldExt;
