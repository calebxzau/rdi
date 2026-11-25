"""Calls rdimedia from a natives jar on the platform it was built for, as the launcher does.

    test-shim.py <natives jar> <windows-x64|linux-x64|macos-arm64> <a short Ogg sound>

The FFmpeg libraries are loaded first, in the order of their dependencies, then rdimedia.
"""
import ctypes
import os
import sys
import tempfile
import zipfile

CLASSIFIERS = {"windows-x64": "windows-x86_64-gpl", "linux-x64": "linux-x86_64-gpl", "macos-arm64": "macosx-arm64-gpl"}
# Each library needs only the ones before it.
ORDER = ("avutil", "swresample", "swscale", "avcodec", "avformat", "rdimedia")
OK, TOO_LONG, BAD_INPUT = 0, 1, 2


def library_key(name):
    base = name.removeprefix("lib")
    for index, wanted in enumerate(ORDER):
        if base.startswith(wanted):
            return index
    raise SystemExit(f"Unexpected library in the jar: {name}")


def main():
    jar_path, platform, sound = sys.argv[1:4]
    classifier = CLASSIFIERS[platform]
    prefix = f"org/bytedeco/ffmpeg/{classifier}/"
    with tempfile.TemporaryDirectory() as directory:
        with zipfile.ZipFile(jar_path) as jar:
            names = [name for name in jar.namelist() if name.startswith(prefix) and "jni" not in os.path.basename(name)]
            for name in names:
                with open(os.path.join(directory, os.path.basename(name)), "wb") as file:
                    file.write(jar.read(name))
        if hasattr(os, "add_dll_directory"):
            os.add_dll_directory(directory)
        loaded = []
        for name in sorted(os.listdir(directory), key=library_key):
            loaded.append(ctypes.CDLL(os.path.join(directory, name)))
            print("Loaded", name)
        shim = loaded[-1]

        shim.rdi_media_abi.restype = ctypes.c_int
        assert shim.rdi_media_abi() == 1, shim.rdi_media_abi()
        shim.rdi_media_free.argtypes = [ctypes.c_void_p]
        shim.rdi_media_free.restype = None
        shim.rdi_ogg_to_opus.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int64, ctypes.c_char_p, ctypes.c_size_t]
        shim.rdi_ogg_to_opus.restype = ctypes.c_int
        shim.rdi_rgba_to_avif.argtypes = [
            ctypes.c_char_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
            ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(ctypes.c_size_t), ctypes.c_char_p, ctypes.c_size_t,
        ]
        shim.rdi_rgba_to_avif.restype = ctypes.c_int
        message = ctypes.create_string_buffer(512)

        def avif(pixels, width, height, high):
            output, size = ctypes.c_void_p(), ctypes.c_size_t()
            result = shim.rdi_rgba_to_avif(pixels, width, height, high, 4, ctypes.byref(output), ctypes.byref(size), message, len(message))
            assert result == OK, (result, message.value)
            data = ctypes.string_at(output, size.value)
            shim.rdi_media_free(output)
            assert data[4:8] == b"ftyp" and b"avif" in data[8:32], data[:32]
            return data

        width, height = 96, 64
        opaque = bytes(channel for y in range(height) for x in range(width) for channel in (x * 2 % 256, y * 3 % 256, (x + y) % 256, 255))
        clear = bytes(channel for y in range(height) for x in range(width) for channel in (x * 2 % 256, y * 3 % 256, (x + y) % 256, x * 255 // (width - 1)))
        low, high, with_alpha = avif(opaque, width, height, 0), avif(opaque, width, height, 1), avif(clear, width, height, 0)
        print(f"AVIF: 4:2:0 {len(low)} bytes, 4:4:4 {len(high)} bytes, with alpha {len(with_alpha)} bytes")
        # The alpha image is a second item of the file.
        assert len(with_alpha) > len(low), (len(with_alpha), len(low))
        assert shim.rdi_rgba_to_avif(opaque, 0, height, 0, 4, ctypes.byref(ctypes.c_void_p()), ctypes.byref(ctypes.c_size_t()), message, len(message)) != OK

        # A folder with a name outside ASCII: the paths are UTF-8 on every platform.
        work = os.path.join(directory, "声音 work")
        os.mkdir(work)
        source = os.path.join(work, "输入.ogg")
        with open(sound, "rb") as file:
            original = file.read()
        with open(source, "wb") as file:
            file.write(original)
        target = os.path.join(work, "输出.ogg")
        result = shim.rdi_ogg_to_opus(source.encode("utf-8"), target.encode("utf-8"), 10_000_000, message, len(message))
        assert result == OK, (result, message.value)
        with open(target, "rb") as file:
            encoded = file.read()
        assert encoded[:4] == b"OggS" and b"OpusHead" in encoded[:64], encoded[:64]
        print(f"Opus: {len(original)} bytes in, {len(encoded)} bytes out")
        # Its own output is a sound it can read again.
        again = os.path.join(work, "again.ogg")
        assert shim.rdi_ogg_to_opus(target.encode("utf-8"), again.encode("utf-8"), 10_000_000, message, len(message)) == OK, message.value
        # No sound fits in no time.
        result = shim.rdi_ogg_to_opus(source.encode("utf-8"), again.encode("utf-8"), 0, message, len(message))
        assert result == TOO_LONG, (result, message.value)
        garbage = os.path.join(work, "garbage.ogg")
        with open(garbage, "wb") as file:
            file.write(b"this is not a sound" * 64)
        result = shim.rdi_ogg_to_opus(garbage.encode("utf-8"), again.encode("utf-8"), 10_000_000, message, len(message))
        assert result == BAD_INPUT, (result, message.value)
        print("Bad input reported as:", message.value.decode("utf-8", "replace"))
        result = shim.rdi_ogg_to_opus(os.path.join(work, "missing.ogg").encode("utf-8"), again.encode("utf-8"), 10_000_000, message, len(message))
        assert result == BAD_INPUT, (result, message.value)
        print("rdimedia OK")
        # Windows cannot remove the folder while the libraries in it are loaded; the files are left
        # to the system's cleanup of the temp folder.
        if os.name == "nt":
            sys.stdout.flush()
            os._exit(0)


if __name__ == "__main__":
    main()
