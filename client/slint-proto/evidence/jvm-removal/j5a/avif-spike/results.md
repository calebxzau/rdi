# AVIF spike results (2026-11-22, WSL x86_64 release builds unless stated)

## Pack icon formats on the server

`icon_formats.py` signed in with the test account, paged `/modpack/search` and fetched every
distinct icon URL (65). Magic-byte sniffing: png 29, jpeg 23, webp 10, **avif 2**, gif 1.
Both AVIF icons are on `i0.hdslb.com`, 8-bit 4:2:0, full range, BT.601 matrix
(672x378 and 508x286). The icon files are third-party images and are not committed.
`/host/list/*` returned no rows for this account, so room icons were not sampled separately
(rooms use the pack's `iconUrl`).

## Pixel comparison (Rust vs FFmpeg 6 CLI, same files)

| File | vs FFmpeg with the file's own range (full) | vs FFmpeg forced to limited range (what Kotlin's `sws_getContext` call does, inferred) |
|---|---|---|
| server icon 672x378 | mean 0.65, max 2 | mean 8.99, max 20 |
| server icon 508x286 | mean 0.38, max 2 | mean 7.21, max 20 |
| `assets/sponsor.avif` 256x256, 10-bit limited | vs FFmpeg 8-bit path: max 4 | vs FFmpeg direct 10-bit swscale: mean 1.73, max 48 (saturated green) |

The Kotlin `FfmpegAvifDecoder` itself was not run; its column is inferred from the
`sws_getContext` call without colorspace details.

## Decoders

| Crate | License | Corrupt-input fuzz (truncations every 7 bytes + 3000 random 1-4 bit flips per file, 4 files) | Decode time (672x378) |
|---|---|---|---|
| `rav1d =1.1.0`, no `asm` | BSD-2 | **aborts the process**: `decode.rs:4997` `unwrap()` on `None` inside an `extern "C"` function ("panic in a function that cannot unwind"); hit within 65-3610 cases per file | 34 ms |
| `rav1d-safe =0.6.0`, no asm features | AGPL-3.0 (owner: GPLv3 project, GPL libraries allowed) | 0 panics in 16,985 cases (ok 517, errors 16,468) | 10.8 ms |

`avif-parse =2.1.0` (MPL-2.0) panicked only on a `debug_assert` (truncated box) in debug
builds; release builds return an error. No release-mode panic was seen in either run.

Chosen: `avif-parse =2.1.0` + `rav1d-safe =0.6.0`, wired into `src/images.rs` (`src/avif.rs`).
Release binary (Windows, thin LTO): 36,343,808 → 39,335,424 bytes (+2,991,616, about 2.85MiB).
`cargo-zigbuild check --release` for aarch64-apple-darwin and x86_64-unknown-linux-gnu passed;
no C compiler, NASM or CMake step runs (both crates' `cc`/`nasm-rs` build dependencies are only
used by assembly features, which are off).
