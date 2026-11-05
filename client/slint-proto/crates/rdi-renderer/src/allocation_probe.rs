//! Opt-in attribution of live Rust requests, excluding native driver/runtime allocations.
use std::{
    alloc::{GlobalAlloc, Layout, System},
    sync::atomic::{AtomicU64, Ordering},
};
pub struct Counting;
static LIVE: AtomicU64 = AtomicU64::new(0);
static PEAK: AtomicU64 = AtomicU64::new(0);
fn add(size: u64) {
    let total = LIVE.fetch_add(size, Ordering::Relaxed) + size;
    PEAK.fetch_max(total, Ordering::Relaxed);
}
// The wrapper preserves System's allocation/alignment contract and only counts successful calls.
unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let p = unsafe { System.alloc(layout) };
        if !p.is_null() {
            add(layout.size() as u64);
        }
        p
    }
    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        let p = unsafe { System.alloc_zeroed(layout) };
        if !p.is_null() {
            add(layout.size() as u64);
        }
        p
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        unsafe {
            System.dealloc(ptr, layout);
        }
        LIVE.fetch_sub(layout.size() as u64, Ordering::Relaxed);
    }
    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        let p = unsafe { System.realloc(ptr, layout, size) };
        if !p.is_null() {
            if size >= layout.size() {
                add((size - layout.size()) as u64);
            } else {
                LIVE.fetch_sub((layout.size() - size) as u64, Ordering::Relaxed);
            }
        }
        p
    }
}
pub fn snapshot(phase: &str) {
    let live = LIVE.load(Ordering::Relaxed);
    let peak = PEAK.load(Ordering::Relaxed);
    tracing::info!(
        phase,
        rust_live_bytes = live,
        rust_peak_bytes = peak,
        "Rust allocation snapshot"
    );
}
