#!/usr/bin/env python3
"""Negative controls for actual QA identity verifier, without Gradle or device access."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('qa', Path(__file__).with_name('verify-qa-identity.py'))
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)


def app():
    return ET.fromstring(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{qa.QA}"><permission android:name="{qa.QA}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"/><uses-permission android:name="{qa.QA}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"/><application android:name="{qa.BASE}.CaptionActionApp"><activity android:name="{qa.BASE}.ui.home.HomeActivity"/><provider android:authorities="{qa.QA}.mlkitinitprovider"/><provider android:authorities="{qa.QA}.androidx-startup"/></application></manifest>''')


def test_manifest():
    return ET.fromstring(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{qa.QA}.test"><instrumentation android:name="androidx.test.runner.AndroidJUnitRunner" android:targetPackage="{qa.QA}"/><application/></manifest>''')


class QaIdentityTest(unittest.TestCase):
    def test_valid_app_and_test(self):
        self.assertEqual(qa.QA, qa.validate_manifest(app())['package'])
        self.assertEqual(qa.QA, qa.validate_manifest(test_manifest(), True)['targetPackage'])

    def test_stale_debug_and_production_apks_rejected(self):
        for package in (qa.BASE, qa.BASE + '.debug'):
            root = app(); root.set('package', package)
            with self.assertRaises(ValueError): qa.validate_manifest(root)

    def test_wrong_instrumentation_target_rejected(self):
        root = test_manifest(); root.find('instrumentation').set(qa.NS + 'targetPackage', qa.BASE + '.debug')
        with self.assertRaises(ValueError): qa.validate_manifest(root, True)

    def test_authority_collision_rejected(self):
        for value in (qa.BASE + '.debug.mlkitinitprovider', qa.BASE + '.mlkitinitprovider', qa.QA + '.androidx-startup'):
            root = app(); root.find('.//provider').set(qa.NS + 'authorities', value)
            with self.assertRaises(ValueError): qa.validate_manifest(root)

    def test_shared_uid_and_namespace_change_rejected(self):
        root = app(); root.set(qa.NS + 'sharedUserId', qa.BASE)
        with self.assertRaises(ValueError): qa.validate_manifest(root)
        root = app(); root.find('application').set(qa.NS + 'name', qa.QA + '.CaptionActionApp')
        with self.assertRaises(ValueError): qa.validate_manifest(root)

    def test_receiver_permission_collision_rejected(self):
        root = app(); root.find('permission').set(qa.NS + 'name', qa.BASE + '.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION')
        with self.assertRaises(ValueError): qa.validate_manifest(root)

    def test_absent_ambiguous_and_stale_buildconfig_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            p = Path(folder)/'BuildConfig.java'
            p.write_text(f'package {qa.BASE};\npublic static final String APPLICATION_ID = "{qa.QA}";')
            qa.validate_build_config([p])
            for paths in ([], [p,p]):
                with self.assertRaises(ValueError): qa.validate_build_config(paths)
            p.write_text(p.read_text().replace(qa.QA, qa.BASE + '.debug'))
            with self.assertRaises(ValueError): qa.validate_build_config([p])

    def test_nonquoted_shared_uid_in_packaged_manifest_rejected(self):
        for value in ('@0x7f120000', '(type 0x10)0x00002710', '"com.example.shared"'):
            text = 'E: manifest\n  A: package="com.hatsunama.captionaction.qa"\n  A: android:sharedUserId(0x0101000b)=' + value + '\n'
            with self.assertRaises(ValueError): qa.xmltree(text)

    def test_missing_or_ambiguous_decoded_manifest_rejected(self):
        for text in ('', 'E: manifest\nE: manifest\n'):
            with self.assertRaises(ValueError): qa.xmltree(text)


if __name__ == '__main__': unittest.main()
