// Package management

use std::fs;
use std::io::Read;
use std::path::PathBuf;

use ecow::eco_format;
use typst::diag::{PackageError, PackageResult};
use typst::syntax::package::PackageSpec;

/// The default Typst registry URL.
pub const DEFAULT_REGISTRY: &str = "https://packages.typst.org";

/// Download and unpack a Typst package, caching it on disk.
/// Returns the path to the unpacked package directory.
///
/// `registry` overrides the default registry URL when provided.
pub fn download_package(spec: &PackageSpec, registry: &str) -> PackageResult<PathBuf> {
    let subdir = format!("{}/{}/{}", spec.namespace, spec.name, spec.version);

    // Determine cache directory
    let cache_dir = dirs::cache_dir()
        .ok_or_else(|| PackageError::Other(Some(eco_format!("no cache directory found"))))?
        .join("typst/packages");

    let package_dir = cache_dir.join(&subdir);

    // If already cached, return immediately
    if package_dir.exists() {
        return Ok(package_dir);
    }

    // Download the tarball
    let url = format!(
        "{}/{}/{}-{}.tar.gz",
        registry, spec.namespace, spec.name, spec.version
    );

    let response = ureq::get(&url).call().map_err(|err| match err {
        ureq::Error::Status(404, _) => PackageError::NotFound(spec.clone()),
        other => PackageError::NetworkFailed(Some(eco_format!("{other}"))),
    })?;

    let mut data = Vec::new();
    response
        .into_reader()
        .read_to_end(&mut data)
        .map_err(|err| PackageError::NetworkFailed(Some(eco_format!("{err}"))))?;

    // Create the package directory
    fs::create_dir_all(&package_dir)
        .map_err(|err| PackageError::Other(Some(eco_format!("{err}"))))?;

    // Decompress and unpack
    let decompressed = flate2::read::GzDecoder::new(data.as_slice());
    tar::Archive::new(decompressed)
        .unpack(&package_dir)
        .map_err(|err| {
            fs::remove_dir_all(&package_dir).ok();
            PackageError::MalformedArchive(Some(eco_format!("{err}")))
        })?;

    Ok(package_dir)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::net::TcpListener;
    use std::thread;
    use ecow::EcoString;
    use typst::syntax::package::PackageVersion;

    /// Create a minimal Typst package as a tar.gz archive in memory.
    fn create_test_package_tar_gz() -> Vec<u8> {
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
        encoder.finish().unwrap()
    }

    /// Start a one-shot HTTP server that serves the given bytes on any request.
    /// Returns (port, join_handle).
    fn start_mini_registry(tar_gz: Vec<u8>) -> (u16, thread::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();

        let handle = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            // Read request headers (we don't care about contents)
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

        (port, handle)
    }

    /// Clean up the cached package directory for the test.
    fn cleanup_test_package_cache() {
        if let Some(cache_dir) = dirs::cache_dir() {
            let pkg_dir = cache_dir.join("typst/packages/test-local/test-hello/0.1.0");
            fs::remove_dir_all(&pkg_dir).ok();
        }
    }

    #[test]
    fn test_download_from_custom_registry() {
        cleanup_test_package_cache();

        let tar_gz = create_test_package_tar_gz();
        let (port, server) = start_mini_registry(tar_gz);
        let registry = format!("http://127.0.0.1:{}", port);

        let spec = PackageSpec {
            namespace: EcoString::from("test-local"),
            name: EcoString::from("test-hello"),
            version: PackageVersion { major: 0, minor: 1, patch: 0 },
        };

        let result = download_package(&spec, &registry);
        assert!(result.is_ok(), "download should succeed: {:?}", result.err());

        let pkg_dir = result.unwrap();
        assert!(pkg_dir.join("typst.toml").exists(), "typst.toml should exist");
        assert!(pkg_dir.join("lib.typ").exists(), "lib.typ should exist");

        let lib_content = fs::read_to_string(pkg_dir.join("lib.typ")).unwrap();
        assert!(lib_content.contains("greet"), "lib.typ should contain greet function");

        cleanup_test_package_cache();
        server.join().unwrap();
    }

    #[test]
    fn test_custom_registry_404_returns_not_found() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let registry = format!("http://127.0.0.1:{}", port);

        let handle = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut buf = [0u8; 4096];
            let _ = stream.read(&mut buf);

            let body = b"Not Found";
            let response = format!(
                "HTTP/1.1 404 Not Found\r\nContent-Length: {}\r\n\r\n",
                body.len()
            );
            stream.write_all(response.as_bytes()).unwrap();
            stream.write_all(body).unwrap();
            stream.flush().unwrap();
        });

        cleanup_test_package_cache();

        let spec = PackageSpec {
            namespace: EcoString::from("test-local"),
            name: EcoString::from("test-hello"),
            version: PackageVersion { major: 0, minor: 1, patch: 0 },
        };

        let result = download_package(&spec, &registry);
        assert!(result.is_err(), "should fail with 404");
        assert!(matches!(result.unwrap_err(), PackageError::NotFound(_)));

        handle.join().unwrap();
    }
}
