//! Print the function body spans of a JavaScript file (see `kdtube_core::jsfn`).
//!
//! ```text
//! cargo run --release --example jsspans -- player.js > spans.txt
//! ```
//!
//! The spans go to stdout, time and peak native memory to stderr.

use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Instant;

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
}

#[global_allocator]
static GLOBAL: Counting = Counting;

fn main() {
    let path = std::env::args().nth(1).expect("usage: jsspans <file.js>");
    let source = std::fs::read_to_string(&path).expect("can't read the file");

    let base = CURRENT.load(Ordering::Relaxed);
    PEAK.store(base, Ordering::Relaxed);
    let start = Instant::now();
    let text = kdtube_core::jsfn::function_body_spans_text(&source);
    let ms = start.elapsed().as_secs_f64() * 1000.0;
    let peak = PEAK.load(Ordering::Relaxed) - base;

    match text {
        Some(text) => {
            eprintln!(
                "{}: {:.1} KB of js, {} function bodies, {:.1} ms, peak native memory {:.1} MB",
                path,
                source.len() as f64 / 1024.0,
                if text.is_empty() {
                    0
                } else {
                    text.split(',').count() / 2
                },
                ms,
                peak as f64 / 1048576.0
            );
            println!("{text}");
        }
        None => {
            eprintln!("{path}: can't be parsed as a script");
            std::process::exit(1);
        }
    }
}
