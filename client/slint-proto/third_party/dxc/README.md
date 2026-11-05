# DirectX Shader Compiler

Unmodified `bin/x64/dxcompiler.dll` from the Microsoft release archive `dxc_2026_09_29.zip`.

- Version: 1.9.2609.5 (01b62ad47)
- Authenticode: valid, signed by Microsoft Corporation
- SHA-256: `0689e2fee0a1c9df8662a59680469f24e2a3ad7ce6bbbcc9284f7a83172bfc59`

`build.rs` copies the DLL next to the Windows executable. `dxil.dll` is not needed.

## Licenses

- `dxcompiler.dll`: `LICENSE-LLVM.txt`. Ship that file with the DLL.
- `LICENSE-MS.txt` covers `dxil.dll`, which is not used or shipped here. `LICENSE-MIT.txt` covers `d3d12shader.h`. Both are kept only because they came with the release archive.

The mapping comes from the Microsoft.Direct3D.DXC NuGet pages: 1.9.2609.5 for the LLVM and MIT files, and 1.8.2407.12 for `LICENSE-MS.txt`.
