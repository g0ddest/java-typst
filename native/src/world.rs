// Typst world implementation

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;

use parking_lot::RwLock;
use typst::diag::{FileError, FileResult};
use typst::foundations::{Bytes, Datetime};
use typst::syntax::package::PackageSpec;
use typst::syntax::{FileId, Source, VirtualPath};
use typst::text::{Font, FontBook};
use typst::utils::LazyHash;
use typst::{Library, LibraryExt, World};

use crate::fonts::FontManager;
use crate::packages::{self, ResolveFn};
use crate::vfs::Vfs;

/// The World implementation for typst-java.
pub struct TypstJavaWorld {
    /// The standard library.
    library: LazyHash<Library>,
    /// Font book snapshot.
    book: LazyHash<FontBook>,
    /// Reference to the font manager.
    font_manager: Arc<FontManager>,
    /// The main source file.
    main_source: Source,
    /// The main file id.
    main_id: FileId,
    /// The virtual filesystem.
    vfs: Vfs,
    /// Cache of already loaded sources (for imports, etc.)
    sources: RwLock<HashMap<FileId, Source>>,
    /// Cache of resolved package paths.
    package_paths: RwLock<HashMap<PackageSpec, PathBuf>>,
    /// Package resolver callback.
    download_fn: ResolveFn,
}

impl TypstJavaWorld {
    /// Create a new world for compilation.
    ///
    /// - `font_manager`: shared font manager
    /// - `book`: snapshot of the font book
    /// - `root`: root directory for file resolution
    /// - `main_source_text`: the Typst source to compile
    /// - `data_json`: optional JSON data to inject as `data.json`
    pub fn new(
        font_manager: Arc<FontManager>,
        book: FontBook,
        root: PathBuf,
        main_source_text: String,
        data_json: Option<String>,
        download_fn: ResolveFn,
    ) -> Self {
        let main_id = FileId::new(None, VirtualPath::new("main.typ"));
        let main_source = Source::new(main_id, main_source_text);

        let vfs = Vfs::new(root);

        // Inject data.json if provided
        if let Some(json) = data_json {
            vfs.inject("data.json", json.into_bytes());
        }

        Self {
            library: LazyHash::new(Library::default()),
            book: LazyHash::new(book),
            font_manager,
            main_source,
            main_id,
            vfs,
            sources: RwLock::new(HashMap::new()),
            package_paths: RwLock::new(HashMap::new()),
            download_fn,
        }
    }

    /// Resolve the root path for a file id, handling packages.
    fn resolve_path(&self, id: FileId) -> FileResult<PathBuf> {
        if let Some(spec) = id.package() {
            // It's a package file — resolve via package manager
            let mut paths = self.package_paths.write();
            let package_root = if let Some(root) = paths.get(spec) {
                root.clone()
            } else {
                let root = packages::resolve_package(spec, self.download_fn)
                    .map_err(|e| FileError::Package(e))?;
                paths.insert(spec.clone(), root.clone());
                root
            };
            id.vpath()
                .resolve(&package_root)
                .ok_or(FileError::AccessDenied)
        } else {
            // Local file — resolve relative to VFS root
            id.vpath()
                .resolve(self.vfs.root())
                .ok_or(FileError::AccessDenied)
        }
    }
}

impl World for TypstJavaWorld {
    fn library(&self) -> &LazyHash<Library> {
        &self.library
    }

    fn book(&self) -> &LazyHash<FontBook> {
        &self.book
    }

    fn main(&self) -> FileId {
        self.main_id
    }

    fn source(&self, id: FileId) -> FileResult<Source> {
        // Return main source if requested
        if id == self.main_id {
            return Ok(self.main_source.clone());
        }

        // Check source cache
        {
            let sources = self.sources.read();
            if let Some(source) = sources.get(&id) {
                return Ok(source.clone());
            }
        }

        // Read the file from VFS or disk
        let rel_path = id.vpath().as_rootless_path();
        let text = if id.package().is_some() {
            // Package file — read from resolved path
            let path = self.resolve_path(id)?;
            std::fs::read_to_string(&path)
                .map_err(|_| FileError::NotFound(path))?
        } else {
            // Local file — try VFS first
            let rel_str = rel_path.to_string_lossy().to_string();
            let bytes = self.vfs.read(&rel_str).map_err(|_| {
                FileError::NotFound(self.vfs.root().join(rel_path).to_path_buf())
            })?;
            String::from_utf8(bytes.as_ref().to_vec())
                .map_err(|_| FileError::InvalidUtf8)?
        };

        let source = Source::new(id, text);
        self.sources.write().insert(id, source.clone());
        Ok(source)
    }

    fn file(&self, id: FileId) -> FileResult<Bytes> {
        let rel_path = id.vpath().as_rootless_path();

        if id.package().is_some() {
            // Package file
            let path = self.resolve_path(id)?;
            let data = std::fs::read(&path)
                .map_err(|_| FileError::NotFound(path))?;
            Ok(Bytes::new(data))
        } else {
            // Local file — try VFS first
            let rel_str = rel_path.to_string_lossy().to_string();
            self.vfs.read(&rel_str).map_err(|_| {
                FileError::NotFound(self.vfs.root().join(rel_path).to_path_buf())
            })
        }
    }

    fn font(&self, index: usize) -> Option<Font> {
        self.font_manager.font(index)
    }

    fn today(&self, offset: Option<i64>) -> Option<Datetime> {
        let now = chrono::Local::now();

        let naive = if let Some(offset) = offset {
            let utc = chrono::Utc::now().naive_utc();
            utc + chrono::Duration::hours(offset)
        } else {
            now.naive_local()
        };

        Datetime::from_ymd(
            naive.date().year(),
            naive.date().month() as u8,
            naive.date().day() as u8,
        )
    }
}

use chrono::Datelike;
