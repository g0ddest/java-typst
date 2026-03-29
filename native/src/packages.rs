// Package management

use std::fs;
use std::io::Read;
use std::path::PathBuf;

use ecow::eco_format;
use typst::diag::{PackageError, PackageResult};
use typst::syntax::package::PackageSpec;

/// The default Typst registry URL.
const DEFAULT_REGISTRY: &str = "https://packages.typst.org";

/// Download and unpack a Typst package, caching it on disk.
/// Returns the path to the unpacked package directory.
pub fn download_package(spec: &PackageSpec) -> PackageResult<PathBuf> {
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
        DEFAULT_REGISTRY, spec.namespace, spec.name, spec.version
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
