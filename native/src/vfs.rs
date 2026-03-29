// Virtual filesystem for in-memory files

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use parking_lot::RwLock;
use typst::foundations::Bytes;

/// A virtual filesystem that overlays injected in-memory files on top of
/// real filesystem reads. Virtual files take priority over real files.
pub struct Vfs {
    /// The root directory for resolving real file paths.
    root: PathBuf,
    /// In-memory virtual files, keyed by relative path (e.g. "data.json").
    virtual_files: RwLock<HashMap<String, Bytes>>,
}

impl Vfs {
    /// Create a new VFS rooted at the given directory.
    pub fn new(root: PathBuf) -> Self {
        Self {
            root,
            virtual_files: RwLock::new(HashMap::new()),
        }
    }

    /// Inject a virtual file at the given relative path.
    pub fn inject(&self, rel_path: &str, content: Vec<u8>) {
        self.virtual_files
            .write()
            .insert(rel_path.to_string(), Bytes::new(content));
    }

    /// Read a file by its relative path. Virtual files take priority over real FS.
    pub fn read(&self, rel_path: &str) -> Result<Bytes, std::io::Error> {
        // Check virtual files first
        if let Some(bytes) = self.virtual_files.read().get(rel_path) {
            return Ok(bytes.clone());
        }

        // Fall back to real filesystem
        let full_path = self.root.join(rel_path);
        let data = std::fs::read(&full_path)?;
        Ok(Bytes::new(data))
    }

    /// Read a file by its absolute path (for package files, etc.).
    #[allow(dead_code)]
    pub fn read_absolute(&self, path: &Path) -> Result<Bytes, std::io::Error> {
        let data = std::fs::read(path)?;
        Ok(Bytes::new(data))
    }

    /// Remove all injected virtual files.
    #[allow(dead_code)]
    pub fn clear_virtual(&self) {
        self.virtual_files.write().clear();
    }

    /// Get the root path.
    pub fn root(&self) -> &Path {
        &self.root
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn test_inject_and_read() {
        let vfs = Vfs::new(PathBuf::from("/tmp"));
        vfs.inject("test.txt", b"hello world".to_vec());
        let data = vfs.read("test.txt").unwrap();
        assert_eq!(data.as_ref(), b"hello world");
    }

    #[test]
    fn test_missing_file_error() {
        let vfs = Vfs::new(PathBuf::from("/tmp"));
        let result = vfs.read("nonexistent_file_abc123.txt");
        assert!(result.is_err());
    }

    #[test]
    fn test_virtual_overrides_real() {
        let dir = std::env::temp_dir().join("typst_java_vfs_test");
        fs::create_dir_all(&dir).ok();
        let real_file = dir.join("override.txt");
        fs::write(&real_file, b"real content").unwrap();

        let vfs = Vfs::new(dir.clone());

        // Without virtual override, reads from disk
        let data = vfs.read("override.txt").unwrap();
        assert_eq!(data.as_ref(), b"real content");

        // Inject virtual override
        vfs.inject("override.txt", b"virtual content".to_vec());
        let data = vfs.read("override.txt").unwrap();
        assert_eq!(data.as_ref(), b"virtual content");

        // Cleanup
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn test_clear_virtual() {
        let vfs = Vfs::new(PathBuf::from("/tmp"));
        vfs.inject("a.txt", b"aaa".to_vec());
        vfs.inject("b.txt", b"bbb".to_vec());

        // Both should be readable
        assert!(vfs.read("a.txt").is_ok());
        assert!(vfs.read("b.txt").is_ok());

        // After clear, both should fail (no real files at /tmp/a.txt etc.)
        vfs.clear_virtual();
        assert!(vfs.read("a.txt").is_err());
        assert!(vfs.read("b.txt").is_err());
    }
}
