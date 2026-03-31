// Package management

use std::ffi::{CStr, CString, c_char, c_int};
use std::path::PathBuf;

use ecow::eco_format;
use typst::diag::{PackageError, PackageResult};
use typst::syntax::package::PackageSpec;

/// Callback for resolving a package to an on-disk directory.
///
/// Called from Rust with package coordinates. The Java side handles
/// downloading, caching, and unpacking. Returns a path to the
/// unpacked package directory.
///
/// - `namespace`, `name`, `version`: null-terminated package coordinates
/// - `out_path`: on success, set to a null-terminated path string
///
/// Returns: 0 = success, 1 = not found, -1 = other error.
pub type ResolveFn = extern "C" fn(
    namespace: *const c_char,
    name: *const c_char,
    version: *const c_char,
    out_path: *mut *const c_char,
) -> c_int;

/// Resolve a Typst package to an on-disk directory via the host callback.
pub fn resolve_package(
    spec: &PackageSpec,
    resolve: ResolveFn,
) -> PackageResult<PathBuf> {
    let c_ns = CString::new(spec.namespace.as_str())
        .map_err(|_| PackageError::Other(Some(eco_format!("invalid namespace"))))?;
    let c_name = CString::new(spec.name.as_str())
        .map_err(|_| PackageError::Other(Some(eco_format!("invalid name"))))?;
    let c_ver = CString::new(spec.version.to_string())
        .map_err(|_| PackageError::Other(Some(eco_format!("invalid version"))))?;

    let mut path_ptr: *const c_char = std::ptr::null();

    let rc = resolve(
        c_ns.as_ptr(), c_name.as_ptr(), c_ver.as_ptr(),
        &mut path_ptr,
    );

    match rc {
        0 => {
            let path_str = unsafe { CStr::from_ptr(path_ptr) }
                .to_str()
                .map_err(|_| PackageError::Other(Some(eco_format!("invalid path"))))?;
            Ok(PathBuf::from(path_str))
        }
        1 => Err(PackageError::NotFound(spec.clone())),
        _ => Err(PackageError::NetworkFailed(Some(eco_format!("package resolve failed")))),
    }
}

#[cfg(test)]
pub mod tests {
    use super::*;
    use std::fs;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::sync::atomic::{AtomicU16, Ordering};
    use std::thread;
    use ecow::EcoString;
    use typst::syntax::package::PackageVersion;

    pub static TEST_SERVER_PORT: AtomicU16 = AtomicU16::new(0);

    // Thread-local storage for the resolved path string (keeps it alive for Rust to read).
    thread_local! {
        static RESOLVED_PATH: std::cell::RefCell<Option<CString>> = const { std::cell::RefCell::new(None) };
    }

    /// Test resolver that downloads from a local HTTP server, unpacks tar.gz, and returns the path.
    pub extern "C" fn test_resolve(
        namespace: *const c_char,
        name: *const c_char,
        version: *const c_char,
        out_path: *mut *const c_char,
    ) -> c_int {
        let ns = unsafe { CStr::from_ptr(namespace) }.to_str().unwrap();
        let n = unsafe { CStr::from_ptr(name) }.to_str().unwrap();
        let v = unsafe { CStr::from_ptr(version) }.to_str().unwrap();

        // Check cache
        let cache_dir = dirs::cache_dir().unwrap().join("typst/packages");
        let package_dir = cache_dir.join(format!("{ns}/{n}/{v}"));
        if package_dir.exists() {
            let path = CString::new(package_dir.to_str().unwrap()).unwrap();
            unsafe { *out_path = path.as_ptr(); }
            RESOLVED_PATH.with(|p| *p.borrow_mut() = Some(path));
            return 0;
        }

        // Download from test server
        let port = TEST_SERVER_PORT.load(Ordering::Relaxed);
        let host = format!("127.0.0.1:{}", port);
        let url_path = format!("{}/{}-{}.tar.gz", ns, n, v);

        let mut stream = match std::net::TcpStream::connect(&host) {
            Ok(s) => s,
            Err(_) => return -1,
        };

        let request = format!("GET /{url_path} HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n");
        if stream.write_all(request.as_bytes()).is_err() { return -1; }

        let mut response = Vec::new();
        if stream.read_to_end(&mut response).is_err() { return -1; }

        let status_end = response.iter().position(|&b| b == b'\r').unwrap_or(0);
        let status_line = std::str::from_utf8(&response[..status_end]).unwrap_or("");
        if status_line.contains("404") { return 1; }
        if !status_line.contains("200") { return -1; }

        let header_end = response.windows(4).position(|w| w == b"\r\n\r\n")
            .map(|p| p + 4).unwrap_or(response.len());
        let data = &response[header_end..];

        // Unpack tar.gz
        fs::create_dir_all(&package_dir).unwrap();
        let decompressed = flate2::read::GzDecoder::new(data);
        if tar::Archive::new(decompressed).unpack(&package_dir).is_err() {
            fs::remove_dir_all(&package_dir).ok();
            return -1;
        }

        let path = CString::new(package_dir.to_str().unwrap()).unwrap();
        unsafe { *out_path = path.as_ptr(); }
        RESOLVED_PATH.with(|p| *p.borrow_mut() = Some(path));
        0
    }

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

    fn start_mini_registry(tar_gz: Vec<u8>) -> (u16, thread::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();

        let handle = thread::spawn(move || {
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

        (port, handle)
    }

    pub fn cleanup_test_package_cache() {
        if let Some(cache_dir) = dirs::cache_dir() {
            let pkg_dir = cache_dir.join("typst/packages/test-local/test-hello/0.1.0");
            fs::remove_dir_all(&pkg_dir).ok();
        }
    }

    #[test]
    fn test_resolve_from_custom_registry() {
        cleanup_test_package_cache();

        let tar_gz = create_test_package_tar_gz();
        let (port, server) = start_mini_registry(tar_gz);
        TEST_SERVER_PORT.store(port, Ordering::Relaxed);

        let spec = PackageSpec {
            namespace: EcoString::from("test-local"),
            name: EcoString::from("test-hello"),
            version: PackageVersion { major: 0, minor: 1, patch: 0 },
        };

        let result = resolve_package(&spec, test_resolve);
        assert!(result.is_ok(), "resolve should succeed: {:?}", result.err());

        let pkg_dir = result.unwrap();
        assert!(pkg_dir.join("typst.toml").exists());
        assert!(pkg_dir.join("lib.typ").exists());

        cleanup_test_package_cache();
        server.join().unwrap();
    }

    #[test]
    fn test_resolve_404_returns_not_found() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        TEST_SERVER_PORT.store(port, Ordering::Relaxed);

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

        let result = resolve_package(&spec, test_resolve);
        assert!(result.is_err());
        assert!(matches!(result.unwrap_err(), PackageError::NotFound(_)));

        handle.join().unwrap();
    }
}
