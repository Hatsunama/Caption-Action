#!/usr/bin/env python3
"""Check Android orientation resource contracts without Gradle or an emulator.

Run against the working tree, or pass --revision REV to test a Git baseline.
These are structural checks, not evidence of Android measurement or rendering.
"""

import argparse
from pathlib import Path
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
RES = "app/src/main/res/"
ANDROID = "{http://schemas.android.com/apk/res/android}"
REVISION = None


def read_resource(relative):
    path = RES + relative
    if REVISION:
        result = subprocess.run(
            ["git", "show", f"{REVISION}:{path}"], cwd=ROOT,
            text=True, capture_output=True, check=False,
        )
        if result.returncode:
            raise FileNotFoundError(path)
        return result.stdout
    return (ROOT / path).read_text(encoding="utf-8")


def attr(element, name):
    return element.get(ANDROID + name)


def views_with_ids(root):
    return {
        attr(view, "id").split("/", 1)[1]: view
        for view in root.iter() if attr(view, "id")
    }


class OrientationResourcesTest(unittest.TestCase):
    def parse(self, relative):
        try:
            return ET.fromstring(read_resource(relative))
        except FileNotFoundError:
            self.fail(f"Missing orientation resource: {relative}")

    def assert_scroll_content(self, root):
        self.assertEqual("ScrollView", root.tag)
        self.assertEqual(1, len(root), "ScrollView must have one direct child")
        self.assertEqual("wrap_content", attr(root[0], "layout_height"))
        for view in root.iter():
            self.assertNotEqual(
                "0dp", attr(view, "layout_height"),
                "Scrollable vertical content must not collapse into weighted zero height",
            )

    def test_all_resource_xml_parses(self):
        if REVISION:
            paths = subprocess.check_output(
                ["git", "ls-tree", "-r", "--name-only", REVISION, "--", RES],
                cwd=ROOT, text=True,
            ).splitlines()
            paths = [path[len(RES):] for path in paths if path.endswith(".xml")]
        else:
            paths = [str(path.relative_to(ROOT / RES)) for path in (ROOT / RES).rglob("*.xml")]
        self.assertTrue(paths)
        for path in paths:
            with self.subTest(path=path):
                self.parse(path)

    def test_home_scroll_has_stable_id_and_contains_actions(self):
        root = self.parse("layout/activity_home.xml")
        scroll = root.find("ScrollView")
        self.assertIsNotNone(scroll)
        self.assertEqual("@+id/homeScroll", attr(scroll, "id"))
        self.assertEqual("true", attr(scroll, "fillViewport"))
        ids = views_with_ids(scroll)
        self.assertTrue({"btnStartLive", "btnMicTranslator", "btnThankYou"} <= ids.keys())

    def test_model_gate_actions_are_in_scrollable_content(self):
        root = self.parse("layout/dialog_model_gate.xml")
        self.assert_scroll_content(root)
        self.assertEqual("@+id/modelGateScroll", attr(root, "id"))
        self.assertTrue({
            "modelGateTitle", "modelGateBody", "modelGroup", "radioBalanced",
            "modelStatus", "downloadOnceHint", "downloadProgress", "downloadPercent",
            "btnDownload", "btnUseAndStart", "btnCancelGate",
        } <= views_with_ids(root).keys())

    def test_permission_page_can_scroll_without_weighted_clipping(self):
        root = self.parse("layout/activity_permission_step.xml")
        self.assert_scroll_content(root)
        self.assertEqual("@+id/permissionScroll", attr(root, "id"))
        self.assertEqual("true", attr(root, "fillViewport"))
        self.assertTrue({
            "permStepLabel", "permTitle", "permBody", "permHint",
            "btnPermPrimary", "btnPermSecondary", "btnPermCancel",
        } <= views_with_ids(root).keys())
        card = root.find(".//com.google.android.material.card.MaterialCardView")
        self.assertEqual("wrap_content", attr(card, "layout_height"))

    def test_mic_landscape_preserves_every_portrait_binding_id(self):
        portrait = self.parse("layout/activity_mic_translator.xml")
        landscape = self.parse("layout-land/activity_mic_translator.xml")
        self.assertEqual(views_with_ids(portrait).keys(), views_with_ids(landscape).keys())
        for key, view in views_with_ids(portrait).items():
            self.assertEqual(view.tag, views_with_ids(landscape)[key].tag, key)

    def test_mic_landscape_gives_transcript_and_controls_separate_scroll_space(self):
        root = self.parse("layout-land/activity_mic_translator.xml")
        ids = views_with_ids(root)
        parents = {child: parent for parent in root.iter() for child in parent}
        transcript = ids["captionScroll"]
        controls = ids["bottomControls"]
        controls_scroll = parents[controls]
        row = parents[transcript]
        self.assertEqual("ScrollView", controls_scroll.tag)
        self.assertIs(row, parents[controls_scroll])
        self.assertEqual("horizontal", attr(row, "orientation"))
        self.assertEqual("0dp", attr(transcript, "layout_width"))
        self.assertEqual("1", attr(transcript, "layout_weight"))
        self.assertEqual("match_parent", attr(transcript, "layout_height"))
        self.assertIsNone(attr(transcript, "layout_marginBottom"))
        self.assertIsNone(attr(transcript, "layout_marginTop"))
        self.assertEqual("wrap_content", attr(controls, "layout_height"))
        self.assertTrue({"btnMic", "pickerFrom", "pickerTo"} <= views_with_ids(controls_scroll).keys())
        self.assertNotIn(ids["btnBack"], tuple(row.iter()))

    def test_activity_roots_retain_system_window_insets(self):
        for relative in (
            "layout/activity_home.xml", "layout/activity_mic_translator.xml",
            "layout-land/activity_mic_translator.xml", "layout/activity_permission_step.xml",
            "layout/activity_thank_you.xml",
        ):
            with self.subTest(layout=relative):
                self.assertEqual("true", attr(self.parse(relative), "fitsSystemWindows"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--revision", help="Read resources from this Git revision instead of disk")
    arguments, remaining = parser.parse_known_args()
    REVISION = arguments.revision
    unittest.main(argv=[sys.argv[0], *remaining], verbosity=2)
