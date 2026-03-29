// Compilation cache

use std::collections::HashMap;
use std::time::SystemTime;

/// A cached template entry.
pub struct CachedTemplate {
    /// The template source text.
    pub source: String,
    /// If this was loaded from a file, its path.
    pub file_path: Option<String>,
    /// The mtime of the file when it was cached (for file-based templates).
    pub mtime: Option<SystemTime>,
}

impl CachedTemplate {
    /// Create a cached template from inline source.
    pub fn from_string(_key: String, source: String) -> Self {
        Self {
            source,
            file_path: None,
            mtime: None,
        }
    }

    /// Create a cached template from a file path.
    pub fn from_file(path: &str, source: String, mtime: SystemTime) -> Self {
        Self {
            source,
            file_path: Some(path.to_string()),
            mtime: Some(mtime),
        }
    }

    /// Check if this cache entry is still valid (mtime hasn't changed).
    pub fn is_valid(&self) -> bool {
        if let (Some(path), Some(cached_mtime)) = (&self.file_path, &self.mtime) {
            if let Ok(metadata) = std::fs::metadata(path) {
                if let Ok(current_mtime) = metadata.modified() {
                    return current_mtime == *cached_mtime;
                }
            }
            // If we can't read metadata, consider the cache invalid
            false
        } else {
            // String-based templates are always valid
            true
        }
    }
}

/// Template cache: stores compiled template source texts, keyed by name.
pub struct TemplateCache {
    entries: HashMap<String, CachedTemplate>,
}

impl TemplateCache {
    pub fn new() -> Self {
        Self {
            entries: HashMap::new(),
        }
    }

    /// Get cached source if valid. Returns None if not cached or invalidated.
    pub fn get(&self, key: &str) -> Option<&str> {
        let entry = self.entries.get(key)?;
        if entry.is_valid() {
            Some(&entry.source)
        } else {
            None
        }
    }

    /// Insert or replace a cached template.
    pub fn insert(&mut self, key: String, entry: CachedTemplate) {
        self.entries.insert(key, entry);
    }

    /// Remove a specific template from the cache.
    pub fn invalidate(&mut self, key: &str) {
        self.entries.remove(key);
    }

    /// Clear all cached templates.
    pub fn invalidate_all(&mut self) {
        self.entries.clear();
    }
}
