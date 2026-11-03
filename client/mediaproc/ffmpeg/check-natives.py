"""Checks a slim FFmpeg natives jar without loading it, so that any host can check any platform.

- Every library must need only other libraries in the jar, or ones every player's system has.
- Every symbol the JNI glue imports from FFmpeg must be exported by the matching library.
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile

SYSTEM_LIBRARIES = {
    "windows": re.compile(
        r"(kernel32|user32|advapi32|bcrypt|ws2_32|shell32|ole32|msvcrt|ucrtbase|ntdll"
        r"|api-ms-win-[\w-]+|vcruntime\d+(_\d+)?|msvcp\d+(_\w+)?)\.dll",
        re.IGNORECASE,
    ),
    # Part of glibc, or what the JDK and the game already need.
    "linux": re.compile(r"(libc|libm|libdl|libpthread|librt|libmvec|ld-linux-x86-64)\.so\.\d+"),
    "macosx": re.compile(r"/usr/lib/lib(System|c\+\+|c\+\+abi|objc)\.[\w.]*dylib"),
}
# bytedeco builds its glue in C++. Only the glue may need these: FFmpeg itself must not add a dependency.
GLUE_SYSTEM_LIBRARIES = re.compile(r"libstdc\+\+\.so\.6|libgcc_s\.so\.1")


def run(*command):
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout


def needed_libraries(path, family):
    output = run("llvm-readobj", "--needed-libs", path)
    if family == "windows":
        # --needed-libs does not read PE imports.
        output = run("llvm-readobj", "--coff-imports", path)
        return re.findall(r"^\s*Name: (\S+\.dll)$", output, re.MULTILINE | re.IGNORECASE)
    return [line.strip() for line in output.splitlines() if re.match(r"\s+\S+\.(so|dylib)|\s+\S+\.so\.|\s+/", line)]


def imports_by_library(path, family):
    """The glue's FFmpeg imports as {library name: symbols}, or under None when the format does not say."""
    if family == "windows":
        imports = {}
        library = None
        for line in run("llvm-readobj", "--coff-imports", path).splitlines():
            if match := re.match(r"\s*Name: (\S+\.dll)$", line, re.IGNORECASE):
                library = match.group(1)
                imports[library] = set()
            elif library and (match := re.match(r"\s*Symbol: (\S+)", line)):
                imports[library].add(match.group(1))
        return imports
    return {None: set(run("llvm-nm", "--undefined-only", "--format=just-symbols", path).split())}


def exported_symbols(path, family):
    if family == "windows":
        return set(re.findall(r"^\s*Name: (\S+)$", run("llvm-readobj", "--coff-exports", path), re.MULTILINE))
    if family == "macosx":
        return set(run("llvm-nm", "-gU", "--format=just-symbols", path).split())
    return {
        symbol.split("@")[0]
        for symbol in run("llvm-nm", "-D", "--defined-only", "--format=just-symbols", path).split()
    }


def mach_o_problems(path, name):
    """What dyld would refuse, or fail to resolve, when a library is loaded directly by its path."""
    problems = []
    used = re.findall(r"^\s+(\S+) \(compatibility", run("llvm-objdump", "--macho", "--dylibs-used", path), re.MULTILINE)
    for library in sorted({library for library in used if used.count(library) > 1}):
        problems.append(f"{name} links {library} more than once, which dyld refuses")
    headers = run("llvm-objdump", "--macho", "--all-headers", path)
    if any(library.startswith("@rpath/") and library != f"@rpath/{name}" for library in used) and "@loader_path/." not in headers:
        problems.append(f"{name} has no @loader_path/. rpath, so it cannot find the libraries next to it")
    return problems


def is_ffmpeg_symbol(symbol):
    return re.match(r"_?(av|avcodec|avformat|avio|swr|sws|avutil|swresample|swscale)_", symbol) is not None


def main():
    jar_path, classifier = sys.argv[1:3]
    family = classifier.split("-")[0]
    failures = []
    with tempfile.TemporaryDirectory() as directory, zipfile.ZipFile(jar_path) as jar:
        prefix = f"org/bytedeco/ffmpeg/{classifier}/"
        names = [name for name in jar.namelist() if name.startswith(prefix)]
        for name in names:
            jar.extract(name, directory)
        native_dir = os.path.join(directory, *prefix.split("/"))
        files = sorted(os.listdir(native_dir))
        print("Libraries:", ", ".join(f"{name} {os.path.getsize(os.path.join(native_dir, name)) // 1024}KiB" for name in files))

        if family == "macosx":
            for name in files:
                failures.extend(mach_o_problems(os.path.join(native_dir, name), name))

        for name in files:
            for needed in needed_libraries(os.path.join(native_dir, name), family):
                base = needed.removeprefix("@rpath/")
                if base in files or SYSTEM_LIBRARIES[family].fullmatch(needed):
                    continue
                if "jni" in name and GLUE_SYSTEM_LIBRARIES.fullmatch(needed):
                    continue
                failures.append(f"{name} needs {needed}, which is neither in the jar nor a system library")

        ffmpeg_files = [name for name in files if "jni" not in name]
        exports = {name: exported_symbols(os.path.join(native_dir, name), family) for name in ffmpeg_files}
        all_exports = set().union(*exports.values())
        for name in files:
            if "jni" not in name:
                continue
            for library, symbols in imports_by_library(os.path.join(native_dir, name), family).items():
                if library is not None and library not in exports:
                    continue
                available = exports[library] if library is not None else all_exports
                for symbol in sorted(symbols):
                    if is_ffmpeg_symbol(symbol) and symbol.split("@")[0] not in available:
                        failures.append(f"{name} imports {symbol}, which no FFmpeg library in the jar exports")

    if failures:
        print("\n".join(failures))
        raise SystemExit(f"{len(failures)} problems in {jar_path}")
    print(f"{jar_path}: dependencies and symbols OK")


if __name__ == "__main__":
    main()
