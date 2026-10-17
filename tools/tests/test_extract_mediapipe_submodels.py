import io
import pathlib
import tempfile
import unittest
import zipfile
from tools.extract_mediapipe_submodels import extract

def pack(names):
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as z:
        for name, content in names.items():
            z.writestr(name, content)
    return out.getvalue()

class BundleExtractionTest(unittest.TestCase):
    def test_nested_bundle(self):
        model = b"\0" * 4 + b"TFL3" + b"\0" * 1024
        inner = pack({"hand_detector.tflite": model, "hand_landmarks_detector.tflite": model})
        outer = pack({"hand_landmarker.task": inner})
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "gesture.task"
            path.write_bytes(outer)
            dest = pathlib.Path(tmp) / "models"
            result = extract(path, dest)
            self.assertEqual(set(result), {"palm_detection.tflite","hand_landmark.tflite"})
            self.assertTrue((dest / "hand_landmark.tflite").exists())
    def test_missing_model_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = pathlib.Path(tmp) / "gesture.task"
            p.write_bytes(pack({"hand_detector.tflite": b"1234"}))
            with self.assertRaises(ValueError):
                extract(p, pathlib.Path(tmp) / "out")

if __name__ == "__main__":
    unittest.main()
