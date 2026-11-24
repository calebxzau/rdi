//! A temporary folder for tests that is removed when the guard drops, also when the test
//! panics, so test runs do not pile up folders in the system temp directory.
use std::ops::Deref;
use std::path::{Path, PathBuf};

pub struct TestDir(PathBuf);

impl TestDir {
    /// Creates `<temp>/<prefix>-<uuid v7>`.
    pub fn new(prefix: &str) -> Self {
        let path = std::env::temp_dir().join(format!("{prefix}-{}", uuid::Uuid::now_v7()));
        std::fs::create_dir_all(&path).unwrap();
        Self(path)
    }
}

impl Deref for TestDir {
    type Target = Path;
    fn deref(&self) -> &Path {
        &self.0
    }
}

impl AsRef<Path> for TestDir {
    fn as_ref(&self) -> &Path {
        &self.0
    }
}

impl Drop for TestDir {
    fn drop(&mut self) {
        // `remove_dir_all` does not follow symbolic links, so a link inside cannot reach out.
        match std::fs::remove_dir_all(&self.0) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            // A file a test still holds open on Windows; reported, not fatal for the test.
            Err(error) => eprintln!("测试临时目录清理失败 {}: {error}", self.0.display()),
        }
    }
}
