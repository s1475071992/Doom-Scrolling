#!/usr/bin/env python3
"""Extract palm/landmark TFLite submodels from the official nested MediaPipe .task.
For NPU experiments only; extraction does not make MediaPipe Tasks use NPU.
"""
import argparse
import io
import pathlib
import zipfile

NAMES = {
    "palm_detection.tflite": ("hand_detector.tflite", "palm_detection.tflite"),
    "hand_landmark.tflite": ("hand_landmarks_detector.tflite", "hand_landmark.tflite"),
}

def iter_models(data: bytes, prefix: str = "", depth: int = 0):
    if depth > 5 or not zipfile.is_zipfile(io.BytesIO(data)):
        return
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for member in archive.infolist():
            if member.is_dir() or member.file_size > 90_000_000:
                continue
            chunk = archive.read(member)
            name = prefix + member.filename
            if member.filename.lower().endswith(".tflite"):
                yield name, chunk
            elif member.filename.lower().endswith((".task", ".zip")):
                yield from iter_models(chunk, name + "/", depth + 1)

def extract(bundle: pathlib.Path, dest: pathlib.Path):
    if not bundle.is_file():
        raise FileNotFoundError(bundle)
    model_files = list(iter_models(bundle.read_bytes()))
    result = {}
    for target, candidates in NAMES.items():
        matches = [(n, b) for n, b in model_files
                   if pathlib.PurePosixPath(n).name in candidates]
        if len(matches) != 1:
            found = [n for n, _ in model_files]
            raise ValueError(f"{target}: expected one matching submodel; got "
                             f"{[n for n, _ in matches]}. All: {found}")
        origin, contents = matches[0]
        if len(contents) < 1024 or contents[4:8] != b"TFL3":
            raise ValueError(f"{origin}: not a valid TFLite flatbuffer")
        dest.mkdir(parents=True, exist_ok=True)
        (dest / target).write_bytes(contents)
        print(f"{origin} -> {target} ({len(contents)} bytes)")
        result[target] = contents
    return result

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("task", type=pathlib.Path)
    p.add_argument("destination", type=pathlib.Path)
    a = p.parse_args()
    extract(a.task, a.destination)
