"""Least-privilege contract for gesture-only accessibility on OriginOS."""
import pathlib
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
XML = ROOT / "app/src/main/res/xml/accessibility_service_config.xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"

class AccessibilityContractTest(unittest.TestCase):
    def test_gesture_capability_without_unneeded_screen_reading(self):
        root = ET.parse(XML).getroot()
        self.assertEqual(root.get(ANDROID + "canPerformGestures"), "true")
        self.assertEqual(root.get(ANDROID + "canRetrieveWindowContent"), "false")
        self.assertNotIn("flagRetrieveInteractiveWindows", root.get(ANDROID + "accessibilityFlags", ""))
        self.assertNotEqual(root.get(ANDROID + "accessibilityEventTypes"), "typeAllMask")

if __name__ == "__main__":
    unittest.main()
