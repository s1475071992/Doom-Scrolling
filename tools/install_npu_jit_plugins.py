#!/usr/bin/env python3
"""Install official LiteRT NPU JIT plugin libraries into an experimental APK.
Currently targets MediaTek and Qualcomm v75 / Snapdragon 8 Gen 3 ONLY.
No QNN vendor runtime/HTP skel is bundled by this script.
"""
import argparse
import pathlib
import zipfile

VENDORS = {
    "mediatek": ("mediatek_runtime", "MediaTek"),
    "qualcomm-v75": ("qualcomm_runtime_v75", "Qualcomm"),
}

def install(archive: pathlib.Path, directory: pathlib.Path):
    directory.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        names = z.namelist()
        installed = []
        for vendor, (prefix, ident) in VENDORS.items():
            for lib in (f"libLiteRtDispatch_{ident}.so",
                        f"libLiteRtCompilerPlugin_{ident}.so"):
                found = [name for name in names
                         if name.startswith(prefix + "/")
                         and "/arm64-v8a/" in name and name.endswith("/" + lib)]
                if len(found) != 1:
                    raise ValueError(f"{vendor}: missing or ambiguous {lib}: {found}")
                contents = z.read(found[0])
                if len(contents) < 10_000 or contents[:4] != bytes([127, 69, 76, 70]):
                    raise ValueError(f"{found[0]}: invalid ELF")
                (directory / lib).write_bytes(contents)
                installed.append((vendor, lib, len(contents)))
        return installed

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("archive", type=pathlib.Path)
    p.add_argument("output", type=pathlib.Path)
    a = p.parse_args()
    for entry in install(a.archive, a.output):
        print("%s -> %s (%s bytes)" % entry)
