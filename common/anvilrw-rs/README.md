# rdi-anvilrw

Rust port of the save import part of `common/anvilrw` (Kotlin package `calebxzau.rdi.anvilrw.remap`):
strict region and NBT reading, byte-level player UUID replacement, save snapshots and `session.lock`,
the sync chunk list, the player scan, the two-player identity mapping and the marking copy identity.

It is a standalone crate, like `common/session-resume-rs`, so the launcher (`client/slint-proto`) and
a future Rust server can both depend on it by path. All I/O is synchronous; run it on a blocking thread
from async code. The server-only parts of the Kotlin module (`AnvilReader`, `Chunk`, builders) are not
ported.

## Tests

```powershell
cargo test                                   # unit tests and Kotlin parity tests
cargo clippy --all-targets
cargo-zigbuild check --target aarch64-apple-darwin --target-dir target\zig-check
```

`tests/parity.rs` compares every result with reference data that Kotlin wrote to
`tests/fixtures/kotlin`: NBT and text patching byte for byte, region rewrites chunk by chunk, whole
save remaps file by file, scans, marking roles and the marking identity preparation. Compressed bytes
are compared through their content, because Java's zlib and Rust's miniz produce different (equally
valid) gzip and zlib bytes; chunks that Kotlin copied unchanged must be byte-for-byte copies in Rust.

To regenerate the reference data after a Kotlin change (the folder must be empty or missing):

```powershell
cd server\master
$env:RDI_ANVILRW_RS_FIXTURES = "<repo>\common\anvilrw-rs\tests\fixtures\kotlin"
.\gradlew.bat :anvilrw:test --tests calebxzau.rdi.anvilrw.remap.RustParityFixtureTest --rerun
```

`tests/sample_save.rs` compares a real save when `RDI_SAMPLE_SAVE` and `RDI_SAMPLE_KOTLIN_OUT` are
set (see the file); run it with `--release`.

## Differences from Kotlin

- A remap that fails because the target folder is not empty leaves that folder alone. Kotlin empties it.
- Region parallelism uses a fixed 1 GiB memory budget instead of a third of the JVM heap.
- Strings with unpaired UTF-16 surrogates (possible in Java) decode with U+FFFD.
- File names that are not valid Unicode fail the snapshot with an I/O error.
- On Unix, `session.lock` uses `fcntl` locks like Java, plus an in-process registry, because `fcntl`
  locks do not conflict within one process.
