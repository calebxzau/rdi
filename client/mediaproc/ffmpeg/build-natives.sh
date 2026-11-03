#!/usr/bin/env bash
# Cross-builds the slim FFmpeg natives jar for one platform with Zig, from Linux.
#
#   build-natives.sh <windows-x64|linux-x64|macos-arm64> <work dir> <output dir>
#
# Needs on PATH: zig, nasm, meson, ninja, cmake, make, pkg-config, python3, llvm-nm, llvm-readobj.
#
# FFmpeg is built in its LGPL configuration, with its third-party libraries linked statically.
# Only the av*/sw* libraries are ours: the JNI glue and the Java API stay those of bytedeco,
# so the versions below must keep the library names the glue expects.
set -euo pipefail

FFMPEG_VERSION=8.0.1
FFMPEG_SHA256=05ee0b03119b45c0bdb4df654b96802e909e0a752f72e4fe3794f487229e5a41
DAV1D_VERSION=1.5.1
DAV1D_SHA256=401813f1f89fa8fd4295805aa5284d9aed9bc7fc1fdbe554af4292f64cbabe21
AOM_VERSION=3.12.1
AOM_SHA256=9e9775180dec7dfd61a79e00bda3809d43891aee6b2e331ff7f26986207ea22e
OPUS_VERSION=1.5.2
OPUS_SHA256=65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1
BYTEDECO_VERSION=8.0.1-1.5.13

PLATFORM=${1:?platform}
WORK_ROOT=$(mkdir -p "${2:?work dir}" && cd "$2" && pwd)
OUTPUT_DIR=$(mkdir -p "${3:?output dir}" && cd "$3" && pwd)
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

case "$PLATFORM" in
    windows-x64)
        ZIG_TARGET=x86_64-windows-gnu
        CLASSIFIER=windows-x86_64-gpl
        BYTEDECO_SHA256=85cc3c16b91ea6d207fedf7577be966f42767ed7f565eb63ae05cfc79860c9af
        FFMPEG_OS=mingw32 FFMPEG_ARCH=x86_64
        CMAKE_SYSTEM=Windows CMAKE_CPU=AMD64 AOM_CPU=x86_64
        MESON_SYSTEM=windows MESON_FAMILY=x86_64 MESON_CPU=x86_64
        ;;
    linux-x64)
        # The same glibc floor as the local proxy.
        ZIG_TARGET=x86_64-linux-gnu.2.35
        CLASSIFIER=linux-x86_64-gpl
        BYTEDECO_SHA256=cf604f955fc62fc2e759beb29528e0cc6cbf9660c2e244fc79de3c40f8b078b0
        FFMPEG_OS=linux FFMPEG_ARCH=x86_64
        CMAKE_SYSTEM=Linux CMAKE_CPU=x86_64 AOM_CPU=x86_64
        MESON_SYSTEM=linux MESON_FAMILY=x86_64 MESON_CPU=x86_64
        ;;
    macos-arm64)
        ZIG_TARGET=aarch64-macos.11.0
        CLASSIFIER=macosx-arm64-gpl
        BYTEDECO_SHA256=33c3facb233bd46b41fdf821660d4d246e234ff4c94f6c9b02bba93fc3aa696c
        FFMPEG_OS=darwin FFMPEG_ARCH=aarch64
        CMAKE_SYSTEM=Darwin CMAKE_CPU=arm64 AOM_CPU=arm64
        MESON_SYSTEM=darwin MESON_FAMILY=aarch64 MESON_CPU=aarch64
        ;;
    *)
        echo "Unknown platform: $PLATFORM" >&2
        exit 2
        ;;
esac

JOBS=$(nproc)
DOWNLOADS=$WORK_ROOT/downloads
TARGET_ROOT=$WORK_ROOT/$PLATFORM
TOOLS=$TARGET_ROOT/tools
SOURCES=$TARGET_ROOT/src
PREFIX=$TARGET_ROOT/prefix
mkdir -p "$DOWNLOADS" "$TOOLS"
# The libraries only change with their versions; keep them while working on the FFmpeg step.
DEPENDENCIES_DONE=$PREFIX/.dependencies-$DAV1D_VERSION-$OPUS_VERSION-$AOM_VERSION
if [ ! -f "$DEPENDENCIES_DONE" ]; then
    rm -rf "$SOURCES" "$PREFIX"
fi
rm -rf "$SOURCES/ffmpeg-$FFMPEG_VERSION" "$SOURCES/ffmpeg-build"
mkdir -p "$SOURCES" "$PREFIX"

fetch() { # <url> <sha256> -> path on stdout
    local file=$DOWNLOADS/$(basename "$1")
    [ -f "$file" ] || curl -fsSL --retry 3 -o "$file.part" "$1" && { [ ! -f "$file.part" ] || mv "$file.part" "$file"; }
    echo "$2  $file" | sha256sum -c --quiet - >&2
    echo "$file"
}

unpack() { # <url> <sha256> <directory name>
    tar xf "$(fetch "$1" "$2")" -C "$SOURCES"
    [ -d "$SOURCES/$3" ] || { echo "Archive did not contain $3" >&2; exit 1; }
}

# ---- Zig as the C toolchain ----
# zig cc rejects some linker flags FFmpeg sets for executables, and we build no executables.
# On macOS it also rejects two flags that only matter on old systems.
for tool in cc c++; do
    cat > "$TOOLS/$tool" <<EOF
#!/usr/bin/env bash
args=()
declare -A seen_libraries
for arg in "\$@"; do
    case "\$arg" in
        # FFmpeg names some of its own libraries twice. zig's linker records both on macOS, and dyld
        # refuses to load a library with a duplicate dependency.
        -lav*|-lsw*)
            [ -z "\${seen_libraries[\$arg]:-}" ] && args+=("\$arg")
            seen_libraries[\$arg]=1
            ;;
        -Wl,--pic-executable,-e,mainCRTStartup|-Wl,--image-base,*) ;;
        -Wl,-dynamic,-search_paths_first|-Wl,-single_module|-Wl,-read_only_relocs,suppress) ;;
        # zig wants a full version, the form the JNI glue was linked against: 60.0.0, not 60.
        *-compatibility_version,*) args+=("\$arg.0.0") ;;
        *) args+=("\$arg") ;;
    esac
done
exec zig $tool -target $ZIG_TARGET -fno-sanitize=undefined "\${args[@]}"
EOF
    chmod +x "$TOOLS/$tool"
done
for tool in ar ranlib; do
    printf '#!/bin/sh\nexec zig %s "$@"\n' "$tool" > "$TOOLS/$tool"
    chmod +x "$TOOLS/$tool"
done

cat > "$TOOLS/cmake-toolchain.cmake" <<EOF
set(CMAKE_SYSTEM_NAME $CMAKE_SYSTEM)
set(CMAKE_SYSTEM_PROCESSOR $CMAKE_CPU)
set(CMAKE_C_COMPILER $TOOLS/cc)
set(CMAKE_CXX_COMPILER $TOOLS/c++)
set(CMAKE_AR $TOOLS/ar)
set(CMAKE_RANLIB $TOOLS/ranlib)
set(CMAKE_ASM_COMPILER $TOOLS/cc)
# CMake wants this on macOS, but only uses it for shared libraries.
set(CMAKE_INSTALL_NAME_TOOL $(command -v true))
set(CMAKE_ASM_NASM_COMPILER $(command -v nasm))
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
EOF

cat > "$TOOLS/meson-cross.txt" <<EOF
[binaries]
c = '$TOOLS/cc'
cpp = '$TOOLS/c++'
ar = '$TOOLS/ar'
ranlib = '$TOOLS/ranlib'
nasm = '$(command -v nasm)'

[host_machine]
system = '$MESON_SYSTEM'
cpu_family = '$MESON_FAMILY'
cpu = '$MESON_CPU'
endian = 'little'
EOF

export PKG_CONFIG_LIBDIR=$PREFIX/lib/pkgconfig
export CFLAGS="-O2 -fPIC"
export CXXFLAGS="-O2 -fPIC"

if [ ! -f "$DEPENDENCIES_DONE" ]; then
# ---- dav1d: the AV1 decoder ----
unpack "https://downloads.videolan.org/pub/videolan/dav1d/$DAV1D_VERSION/dav1d-$DAV1D_VERSION.tar.xz" \
    "$DAV1D_SHA256" "dav1d-$DAV1D_VERSION"
meson setup "$SOURCES/dav1d-build" "$SOURCES/dav1d-$DAV1D_VERSION" \
    --cross-file "$TOOLS/meson-cross.txt" --prefix "$PREFIX" --libdir lib --buildtype release \
    --default-library static -Denable_tools=false -Denable_tests=false -Dlogging=false
ninja -C "$SOURCES/dav1d-build" -j "$JOBS" install

# ---- libopus: the Opus encoder ----
unpack "https://downloads.xiph.org/releases/opus/opus-$OPUS_VERSION.tar.gz" "$OPUS_SHA256" "opus-$OPUS_VERSION"
cmake -G Ninja -S "$SOURCES/opus-$OPUS_VERSION" -B "$SOURCES/opus-build" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLS/cmake-toolchain.cmake" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_INSTALL_LIBDIR=lib -DBUILD_SHARED_LIBS=OFF \
    -DOPUS_BUILD_PROGRAMS=OFF -DOPUS_BUILD_TESTING=OFF -DOPUS_FORTIFY_SOURCE=OFF -DOPUS_STACK_PROTECTOR=OFF
ninja -C "$SOURCES/opus-build" -j "$JOBS" install

# ---- libaom: the AV1 encoder only ----
# The encoder writes 10-bit images, so high bit depth support stays.
unpack "https://storage.googleapis.com/aom-releases/libaom-$AOM_VERSION.tar.gz" "$AOM_SHA256" "libaom-$AOM_VERSION"
cmake -G Ninja -S "$SOURCES/libaom-$AOM_VERSION" -B "$SOURCES/aom-build" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLS/cmake-toolchain.cmake" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_INSTALL_LIBDIR=lib -DBUILD_SHARED_LIBS=OFF \
    -DAOM_TARGET_CPU="$AOM_CPU" -DCONFIG_AV1_DECODER=0 -DCONFIG_RUNTIME_CPU_DETECT=1 \
    -DCONFIG_WEBM_IO=0 -DCONFIG_LIBYUV=0 \
    -DENABLE_DOCS=0 -DENABLE_EXAMPLES=0 -DENABLE_TESTS=0 -DENABLE_TOOLS=0
ninja -C "$SOURCES/aom-build" -j "$JOBS" install
touch "$DEPENDENCIES_DONE"
fi

# ---- FFmpeg ----
unpack "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" "$FFMPEG_SHA256" "ffmpeg-$FFMPEG_VERSION"
FFMPEG_CONFIG=(
    --prefix="$PREFIX"
    --enable-cross-compile --target-os="$FFMPEG_OS" --arch="$FFMPEG_ARCH"
    --cc="$TOOLS/cc" --cxx="$TOOLS/c++" --ld="$TOOLS/c++" --ar="$TOOLS/ar" --ranlib="$TOOLS/ranlib"
    --nm="llvm-nm -g" --pkg-config=pkg-config --x86asmexe=nasm
    --disable-everything --disable-programs --disable-doc --disable-network --disable-autodetect
    --disable-avdevice --disable-avfilter --disable-debug --disable-stripping
    --enable-shared --disable-static --enable-pic
    --enable-libdav1d --enable-libaom --enable-libopus
    --enable-decoder=libdav1d,opus,vorbis,flac
    --enable-encoder=libaom_av1,libopus
    --enable-demuxer=ogg,mov
    --enable-muxer=ogg,avif
    --enable-parser=av1,opus,vorbis,flac
    --enable-protocol=file
    --enable-bsf=av1_metadata,av1_frame_merge,av1_frame_split
)
if [ "$PLATFORM" = macos-arm64 ]; then
    # The game loads these directly, so each must find the others next to it without help from the glue.
    FFMPEG_CONFIG+=(--install-name-dir=@rpath "--extra-ldflags=-s -Wl,-rpath,@loader_path/.")
else
    FFMPEG_CONFIG+=(--extra-ldflags=-s)
fi
mkdir -p "$SOURCES/ffmpeg-build"
(
    cd "$SOURCES/ffmpeg-build"
    # configure takes its own flags; the CFLAGS above are only for the libraries.
    env -u CFLAGS -u CXXFLAGS "$SOURCES/ffmpeg-$FFMPEG_VERSION/configure" "${FFMPEG_CONFIG[@]}"
    # Zig's glibc stubs still link sysctl, though glibc removed it and its header. Linux does not use it.
    sed -i 's/^#define HAVE_SYSCTL 1$/#define HAVE_SYSCTL 0/' config.h
    make -j "$JOBS"
    # SLIB_INSTALL_EXTRA_SHLIB is the MSVC import library on Windows, which we neither build nor need.
    make install SLIB_INSTALL_EXTRA_SHLIB=
)

# ---- Package ----
BYTEDECO_JAR=$(fetch \
    "https://repo1.maven.org/maven2/org/bytedeco/ffmpeg/$BYTEDECO_VERSION/ffmpeg-$BYTEDECO_VERSION-$CLASSIFIER.jar" \
    "$BYTEDECO_SHA256")
python3 -I "$SCRIPT_DIR/pack-natives.py" \
    --classifier "$CLASSIFIER" \
    --prefix "$PREFIX" \
    --bytedeco-jar "$BYTEDECO_JAR" \
    --sources "$SOURCES" \
    --config="$(printf '%s\n' "${FFMPEG_CONFIG[@]}" | sed "s#$WORK_ROOT#<work>#g")" \
    --versions="FFmpeg $FFMPEG_VERSION, dav1d $DAV1D_VERSION, libaom $AOM_VERSION, libopus $OPUS_VERSION, JNI glue bytedeco $BYTEDECO_VERSION" \
    --output "$OUTPUT_DIR/ffmpeg-$FFMPEG_VERSION-rdi1-$CLASSIFIER.jar"
python3 -I "$SCRIPT_DIR/check-natives.py" "$OUTPUT_DIR/ffmpeg-$FFMPEG_VERSION-rdi1-$CLASSIFIER.jar" "$CLASSIFIER"
