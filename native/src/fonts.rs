// Font loading and management

use std::path::Path;
use std::sync::OnceLock;

use parking_lot::RwLock;
use typst::foundations::Bytes;
use typst::text::{Font, FontBook, FontInfo};

/// A slot for lazily-loaded fonts from disk.
struct FontSlot {
    /// Path to the font file on disk (None for embedded/in-memory fonts).
    path: Option<std::path::PathBuf>,
    /// Index of the font face within a collection file.
    index: u32,
    /// The lazily loaded font.
    font: OnceLock<Option<Font>>,
}

impl FontSlot {
    /// Get the font, loading it from disk on first access if needed.
    fn get(&self) -> Option<Font> {
        self.font
            .get_or_init(|| {
                let path = self.path.as_ref()?;
                let data = std::fs::read(path).ok()?;
                Font::new(Bytes::new(data), self.index)
            })
            .clone()
    }
}

/// Manages all available fonts: embedded typst-assets fonts plus custom fonts
/// added at runtime.
pub struct FontManager {
    book: RwLock<FontBook>,
    fonts: RwLock<Vec<FontSlot>>,
}

impl FontManager {
    /// Create a new FontManager with embedded typst-assets fonts pre-loaded.
    pub fn new() -> Self {
        let mut book = FontBook::new();
        let mut fonts = Vec::new();

        // Load all embedded fonts from typst-assets
        for data in typst_assets::fonts() {
            let buffer = Bytes::new(data);
            for (i, font) in Font::iter(buffer).enumerate() {
                book.push(font.info().clone());
                fonts.push(FontSlot {
                    path: None,
                    index: i as u32,
                    font: OnceLock::from(Some(font)),
                });
            }
        }

        Self {
            book: RwLock::new(book),
            fonts: RwLock::new(fonts),
        }
    }

    /// Add font data (raw bytes). Returns 0 on success, -1 on failure.
    pub fn add_font_data(&self, data: &[u8]) -> i32 {
        let buffer = Bytes::new(data.to_vec());
        let new_fonts: Vec<Font> = Font::iter(buffer).collect();

        if new_fonts.is_empty() {
            return -1;
        }

        let mut book = self.book.write();
        let mut fonts = self.fonts.write();

        for font in new_fonts {
            book.push(font.info().clone());
            fonts.push(FontSlot {
                path: None,
                index: 0,
                font: OnceLock::from(Some(font)),
            });
        }

        0
    }

    /// Scan a directory for font files (.ttf, .otf, .ttc) and load them.
    /// Returns 0 on success, -1 on error (e.g. directory not found).
    pub fn add_font_dir(&self, dir: &Path) -> i32 {
        let entries = match std::fs::read_dir(dir) {
            Ok(e) => e,
            Err(_) => return -1,
        };

        for entry in entries.flatten() {
            let path = entry.path();
            if !path.is_file() {
                continue;
            }

            let ext = path
                .extension()
                .and_then(|e| e.to_str())
                .map(|e| e.to_lowercase());

            match ext.as_deref() {
                Some("ttf") | Some("otf") | Some("ttc") => {}
                _ => continue,
            }

            // Read font data and extract info for the book
            if let Ok(data) = std::fs::read(&path) {
                let buffer = Bytes::new(data);
                let count = ttf_parser::fonts_in_collection(&buffer).unwrap_or(1);

                let mut book = self.book.write();
                let mut fonts = self.fonts.write();

                for index in 0..count {
                    if let Some(info) = FontInfo::new(&buffer, index) {
                        book.push(info);
                        fonts.push(FontSlot {
                            path: Some(path.clone()),
                            index,
                            font: OnceLock::new(),
                        });
                    }
                }
            }
        }

        0
    }

    /// Get a snapshot of the current font book.
    pub fn book(&self) -> FontBook {
        self.book.read().clone()
    }

    /// Get the font at the given index.
    pub fn font(&self, index: usize) -> Option<Font> {
        let fonts = self.fonts.read();
        fonts.get(index)?.get()
    }

    /// Get the number of registered fonts.
    #[allow(dead_code)]
    pub fn font_count(&self) -> usize {
        self.fonts.read().len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_embedded_fonts_loaded() {
        let fm = FontManager::new();
        // typst-assets includes 17 font files, each with at least 1 face
        assert!(
            fm.font_count() > 10,
            "Expected > 10 embedded fonts, got {}",
            fm.font_count()
        );
    }

    #[test]
    fn test_invalid_font_returns_error() {
        let fm = FontManager::new();
        let result = fm.add_font_data(b"not a font");
        assert_eq!(result, -1);
    }

    #[test]
    fn test_missing_dir_returns_error() {
        let fm = FontManager::new();
        let result = fm.add_font_dir(Path::new("/nonexistent/dir/that/does/not/exist"));
        assert_eq!(result, -1);
    }

    #[test]
    fn test_font_at_index() {
        let fm = FontManager::new();
        // Should be able to get the first embedded font
        assert!(fm.font(0).is_some());
        // Out of bounds should return None
        assert!(fm.font(999999).is_none());
    }
}
