//! Process-wide cache of compiled paths.
//!
//! The app has a few hundred distinct `@JsonPath` strings that are evaluated
//! tens of thousands of times per response. Java interns each string once and
//! afterwards passes a small integer handle, so no string crosses the JNI
//! boundary on the hot path.

use std::collections::HashMap;
use std::sync::{Arc, OnceLock, RwLock};

use crate::path::Path;

/// Annotations are a fixed set; this only guards against a caller that feeds
/// dynamically generated paths.
const MAX_PATHS: usize = 65_536;

#[derive(Default)]
struct Registry {
    paths: Vec<Arc<Path>>,
    by_src: HashMap<String, u32>,
}

fn registry() -> &'static RwLock<Registry> {
    static REG: OnceLock<RwLock<Registry>> = OnceLock::new();
    REG.get_or_init(|| RwLock::new(Registry::default()))
}

/// Compile `src` (or find it) and return a handle `>= 1`.
/// `None` means the path is not supported by the native engine.
pub fn intern(src: &str) -> Option<u32> {
    {
        let r = registry().read().unwrap_or_else(|e| e.into_inner());
        if let Some(&h) = r.by_src.get(src) {
            return Some(h);
        }
    }
    let path = Path::compile(src).ok()?;
    let mut w = registry().write().unwrap_or_else(|e| e.into_inner());
    if let Some(&h) = w.by_src.get(src) {
        return Some(h);
    }
    if w.paths.len() >= MAX_PATHS {
        return None;
    }
    w.paths.push(Arc::new(path));
    let h = w.paths.len() as u32;
    w.by_src.insert(src.to_string(), h);
    Some(h)
}

pub fn get(handle: u32) -> Option<Arc<Path>> {
    if handle == 0 {
        return None;
    }
    let r = registry().read().unwrap_or_else(|e| e.into_inner());
    r.paths.get(handle as usize - 1).cloned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn interning_is_stable_and_rejects_unsupported() {
        let a = intern("$.registry.test[0]").unwrap();
        let b = intern("$.registry.test[0]").unwrap();
        assert_eq!(a, b);
        assert!(a >= 1);
        assert_eq!(get(a).unwrap().tokens.len(), 3);
        assert!(intern("$..deep").is_none());
        assert!(get(0).is_none());
        assert!(get(u32::MAX).is_none());
    }
}
