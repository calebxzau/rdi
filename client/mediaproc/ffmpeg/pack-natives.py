"""Packs the slim FFmpeg libraries with bytedeco's JNI glue, in the layout of bytedeco's natives jar."""
import argparse
import glob
import os
import re
import zipfile

# JNI libraries of the FFmpeg parts we load; avfilter and avdevice are not built.
GLUE = ("jniavutil", "jniavcodec", "jniavformat", "jniswresample", "jniswscale")
MODULE_INFO = "META-INF/versions/9/module-info.class"
# Fixed, so that the same input gives the same jar.
ENTRY_TIME = (2026, 1, 1, 0, 0, 0)


def built_libraries(prefix, classifier):
    if classifier.startswith("windows-"):
        return glob.glob(os.path.join(prefix, "bin", "*.dll"))
    pattern = "*.so.*" if classifier.startswith("linux-") else "*.dylib"
    libraries = []
    for path in glob.glob(os.path.join(prefix, "lib", pattern)):
        name = os.path.basename(path)
        # libavutil.so.60 and libavutil.60.dylib are what the glue links; the rest are links to them.
        if re.fullmatch(r"lib\w+\.so\.\d+", name) or re.fullmatch(r"lib\w+\.\d+\.dylib", name):
            libraries.append(path)
    return libraries


def license_files(sources):
    wanted = {
        "ffmpeg-*": ["COPYING.LGPLv2.1", "LICENSE.md"],
        "dav1d-*": ["COPYING"],
        "libaom-*": ["LICENSE", "PATENTS"],
        "opus-*": ["COPYING"],
    }
    for pattern, names in wanted.items():
        for directory in glob.glob(os.path.join(sources, pattern)):
            if not os.path.isdir(directory) or directory.endswith("-build"):
                continue
            for name in names:
                path = os.path.join(directory, name)
                if not os.path.isfile(path):
                    raise SystemExit(f"Missing license file: {path}")
                yield f"{os.path.basename(directory)}/{name}", path


def add(jar, name, data, executable=False):
    entry = zipfile.ZipInfo(name, ENTRY_TIME)
    entry.compress_type = zipfile.ZIP_DEFLATED
    entry.external_attr = (0o755 if executable else 0o644) << 16
    jar.writestr(entry, data, compresslevel=9)


def main():
    parser = argparse.ArgumentParser()
    for option in ("classifier", "prefix", "bytedeco-jar", "sources", "config", "versions", "output"):
        parser.add_argument(f"--{option}", required=True)
    args = parser.parse_args()

    native_dir = f"org/bytedeco/ffmpeg/{args.classifier}/"
    libraries = built_libraries(args.prefix, args.classifier)
    if len(libraries) != 5:
        raise SystemExit(f"Expected 5 FFmpeg libraries, found: {sorted(libraries)}")

    with zipfile.ZipFile(args.bytedeco_jar) as bytedeco, zipfile.ZipFile(args.output, "w") as jar:
        add(jar, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n")
        add(jar, "META-INF/rdi-ffmpeg/VERSIONS.txt", args.versions + "\n")
        add(jar, "META-INF/rdi-ffmpeg/configure.txt", args.config + "\n")
        for name, path in sorted(license_files(args.sources)):
            with open(path, "rb") as file:
                add(jar, f"META-INF/rdi-ffmpeg/licenses/{name}", file.read())

        # Keeps the module name of bytedeco's jar. Without it the file name would give the module "ffmpeg".
        add(jar, MODULE_INFO, bytedeco.read(MODULE_INFO))

        for path in sorted(libraries):
            with open(path, "rb") as file:
                add(jar, native_dir + os.path.basename(path), file.read(), executable=True)

        glue = [
            name for name in bytedeco.namelist()
            if name.startswith(native_dir)
            and os.path.basename(name).removeprefix("lib").split(".")[0] in GLUE
        ]
        if len(glue) != len(GLUE):
            raise SystemExit(f"Expected {len(GLUE)} JNI libraries, found: {glue}")
        for name in sorted(glue):
            add(jar, name, bytedeco.read(name), executable=True)

    print(f"{args.output}: {os.path.getsize(args.output)} bytes")


if __name__ == "__main__":
    main()
