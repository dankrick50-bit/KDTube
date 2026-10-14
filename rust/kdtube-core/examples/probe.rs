//! Index JSON files and report size, timing and native memory.
//!
//! ```text
//! cargo run --release --example probe -- [--minify] path/to/dir-or-file ...
//! ```
//!
//! `--minify` strips insignificant whitespace first. Recorded fixtures are
//! usually pretty-printed, while the app asks the server for `prettyPrint=false`.
//!
//! "peak" is the high-water mark of the process heap while the document is
//! being loaded and indexed, measured with a counting allocator (so it includes
//! any temporary over-allocation, not just what is kept).

use std::alloc::{GlobalAlloc, Layout, System};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Instant;

use kdtube_core::doc::{Doc, ParseError};

struct Counting;

static CURRENT: AtomicUsize = AtomicUsize::new(0);
static PEAK: AtomicUsize = AtomicUsize::new(0);

unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let p = System.alloc(layout);
        if !p.is_null() {
            let now = CURRENT.fetch_add(layout.size(), Ordering::Relaxed) + layout.size();
            PEAK.fetch_max(now, Ordering::Relaxed);
        }
        p
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        System.dealloc(ptr, layout);
        CURRENT.fetch_sub(layout.size(), Ordering::Relaxed);
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        let p = System.realloc(ptr, layout, new_size);
        if !p.is_null() {
            if new_size >= layout.size() {
                let now = CURRENT.fetch_add(new_size - layout.size(), Ordering::Relaxed)
                    + (new_size - layout.size());
                PEAK.fetch_max(now, Ordering::Relaxed);
            } else {
                CURRENT.fetch_sub(layout.size() - new_size, Ordering::Relaxed);
            }
        }
        p
    }
}

#[global_allocator]
static GLOBAL: Counting = Counting;

fn collect(path: &Path, out: &mut Vec<PathBuf>) {
    if path.is_dir() {
        if let Ok(rd) = std::fs::read_dir(path) {
            for e in rd.flatten() {
                collect(&e.path(), out);
            }
        }
    } else if path.extension().is_some_and(|e| e == "json") {
        out.push(path.to_path_buf());
    }
}

/// Some recorded files start with developer notes (request URL, `//` comments)
/// that the server never sends: drop everything before the first line that
/// starts with `{` or `[`.
fn strip_preamble(bytes: &mut Vec<u8>) {
    let mut line_start = 0;
    while line_start < bytes.len() {
        let mut i = line_start;
        while i < bytes.len() && (bytes[i] == b' ' || bytes[i] == b'\t') {
            i += 1;
        }
        if i < bytes.len() && (bytes[i] == b'{' || bytes[i] == b'[') {
            bytes.drain(..line_start);
            return;
        }
        while i < bytes.len() && bytes[i] != b'\n' {
            i += 1;
        }
        line_start = i + 1;
    }
}

/// Remove insignificant whitespace (what the server sends for `prettyPrint=false`).
fn minify(src: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(src.len());
    let (mut in_string, mut escaped) = (false, false);
    for &b in src {
        if in_string {
            out.push(b);
            if escaped {
                escaped = false;
            } else if b == b'\\' {
                escaped = true;
            } else if b == b'"' {
                in_string = false;
            }
        } else if !matches!(b, b' ' | b'\n' | b'\r' | b'\t') {
            in_string = b == b'"';
            out.push(b);
        }
    }
    out
}

fn main() {
    let mut files = Vec::new();
    let mut minified = false;
    for arg in std::env::args().skip(1) {
        if arg == "--minify" {
            minified = true;
        } else {
            collect(Path::new(&arg), &mut files);
        }
    }
    files.sort();

    println!(
        "{:>9} {:>9} {:>9} {:>9} {:>7} {:>7}  file",
        "json KB", "tape KB", "kept KB", "peak KB", "peak/x", "ms"
    );

    let (mut total_json, mut total_kept, mut total_peak, mut rejected) =
        (0usize, 0usize, 0usize, 0usize);
    let mut worst_ratio = 0f64;

    for file in &files {
        let Ok(mut bytes) = std::fs::read(file) else {
            continue;
        };
        strip_preamble(&mut bytes);
        if minified {
            bytes = minify(&bytes);
        }
        let name = file.file_name().unwrap().to_string_lossy();

        // Load the way the app does: 16 KB chunks appended to a growing buffer.
        let base = CURRENT.load(Ordering::Relaxed);
        PEAK.store(base, Ordering::Relaxed);
        let start = Instant::now();
        let mut doc = Doc::with_capacity(0);
        let mut result = Ok(());
        for chunk in bytes.chunks(16 * 1024) {
            if let Err(e) = doc.append(chunk) {
                result = Err(e);
                break;
            }
        }
        if result.is_ok() {
            result = doc.index(false);
        }
        let ms = start.elapsed().as_secs_f64() * 1000.0;
        let peak = PEAK.load(Ordering::Relaxed) - base;

        match result {
            Ok(()) => {
                let kept = doc.heap_bytes();
                let ratio = peak as f64 / bytes.len().max(1) as f64;
                worst_ratio = worst_ratio.max(ratio);
                total_json += bytes.len();
                total_kept += kept;
                total_peak += peak;
                println!(
                    "{:>9.1} {:>9.1} {:>9.1} {:>9.1} {:>7.2} {:>7.2}  {}",
                    bytes.len() as f64 / 1024.0,
                    (doc.tape_len() * 8) as f64 / 1024.0,
                    kept as f64 / 1024.0,
                    peak as f64 / 1024.0,
                    ratio,
                    ms,
                    name
                );
            }
            Err(e) => {
                rejected += 1;
                let at = match e {
                    ParseError::Syntax(pos) => {
                        let from = pos.saturating_sub(30);
                        let to = (pos + 30).min(bytes.len());
                        format!(
                            " at byte {pos}: ...{}...",
                            String::from_utf8_lossy(&bytes[from..to]).replace(['\n', '\r'], "\\n")
                        )
                    }
                    _ => String::new(),
                };
                println!(
                    "{:>9.1} REJECTED {:?}{}  {}",
                    bytes.len() as f64 / 1024.0,
                    e,
                    at,
                    name
                );
            }
        }
    }

    println!();
    println!(
        "{} files, {} rejected (handled by the Java engine); indexed {:.1} MB of JSON",
        files.len(),
        rejected,
        total_json as f64 / 1048576.0
    );
    if total_json > 0 {
        println!(
            "native memory: kept {:.2}x the JSON size on average, peak {:.2}x on average, {:.2}x worst case",
            total_kept as f64 / total_json as f64,
            total_peak as f64 / total_json as f64,
            worst_ratio
        );
    }
}
