#!/usr/bin/env python3
"""Controlled archive/verifier boundaries, plus real APK verification in CI."""
import importlib.util
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]

class UnsignedArtifactTest(unittest.TestCase):
    def run_check(self, mode, verifier='unsigned'):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / 'fixture.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('AndroidManifest.xml', b'controlled manifest')
                if mode == 'v1': archive.writestr('META-INF/CERT.RSA', b'certificate')
            if mode == 'block':
                data = bytearray(apk.read_bytes())
                end = data.rfind(b'PK\x05\x06')
                offset = struct.unpack_from('<I', data, end + 16)[0]
                block = struct.pack('<Q', 24) + struct.pack('<Q', 24) + b'APK Sig Block 42'
                data[offset:offset] = block
                struct.pack_into('<I', data, end + len(block) + 16, offset + len(block))
                apk.write_bytes(data)
            if mode == 'invalid': apk.write_bytes(b'not an APK')
            if mode == 'idsig': apk.with_suffix('.apk.idsig').write_bytes(b'signature')
            signer = root / 'apksigner'
            outputs = {'unsigned': ('Missing META-INF/MANIFEST.MF', 1), 'signed': ('Signer #1 certificate SHA-256 digest: fixture', 0), 'tool-error': ('java not found', 127), 'malformed': ('Malformed APK', 1)}
            message, code = outputs[verifier]
            signer.write_text('#!/bin/sh\necho "' + message + '" >&2\nexit ' + str(code) + '\n')
            signer.chmod(0o755)
            return subprocess.run(['python3', str(ROOT / 'scripts/verify-unsigned-apks.py'), '--apksigner', str(signer), str(apk)], capture_output=True, text=True)

    def test_unsigned_archive_requires_expected_verifier_failure(self):
        result = self.run_check('unsigned')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('"unsigned": true', result.stdout)

    def test_every_signature_or_ambiguous_failure_is_rejected(self):
        for mode, verifier in [('v1', 'unsigned'), ('block', 'unsigned'), ('idsig', 'unsigned'), ('invalid', 'unsigned'), ('unsigned', 'signed'), ('unsigned', 'tool-error'), ('unsigned', 'malformed')]:
            with self.subTest(mode=mode, verifier=verifier):
                result = self.run_check(mode, verifier)
                self.assertNotEqual(0, result.returncode)

class EmulatorUnsignedBoundaryTest(unittest.TestCase):
    def test_checkpoint_blocks_before_any_device_or_build_command(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('adb', 'gradle'):
                file = root / name
                file.write_text('#!/bin/sh\necho called >> "$CALLS"\nexit 0\n')
                file.chmod(0o755)
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'], CALLS=str(root / 'calls'), ANDROID_SERIAL='emulator-5554', GRADLE_BIN=str(root / 'gradle'))
            result = subprocess.run(['bash', str(ROOT / 'scripts/run-recorder-emulator-check.sh')], env=env, capture_output=True, text=True)
            self.assertEqual(4, result.returncode, result.stderr)
            self.assertIn('C-signed', result.stderr)
            self.assertFalse((root / 'calls').exists())

class UnsignedAabTest(unittest.TestCase):
    def run_check(self, mode, verifier='unsigned'):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / 'fixture.aab'
            with zipfile.ZipFile(bundle, 'w') as archive:
                archive.writestr('BundleConfig.pb', b'fixture')
                archive.writestr('base/manifest/AndroidManifest.xml', b'fixture')
                if mode == 'v1': archive.writestr('META-INF/CERT.SF', b'signature')
                if mode == 'transparency': archive.writestr('META-INF/code_transparency_signed.jwt', b'signed token')
            if mode == 'invalid': bundle.write_bytes(b'not a ZIP')
            signer = root / 'jarsigner'
            message, code = {'unsigned': ('jar is unsigned.', 0), 'no-manifest': ('no manifest.\\njar is unsigned.', 0), 'signed': ('jar verified.', 0), 'tool-error': ('java not found', 127), 'ambiguous': ('treated as unsigned', 0)}[verifier]
            signer.write_text('#!/bin/sh\necho "' + message + '"\nexit ' + str(code) + '\n')
            signer.chmod(0o755)
            return subprocess.run(['python3', str(ROOT / 'scripts/verify-unsigned-aabs.py'), '--jarsigner', str(signer), str(bundle)], capture_output=True, text=True)

    def test_unsigned_bundle_requires_structure_and_unsigned_verifier_result(self):
        for verifier in ('unsigned', 'no-manifest'):
            result = self.run_check('unsigned', verifier)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('"unsigned": true', result.stdout)

    def test_signed_or_ambiguous_bundle_is_rejected(self):
        for mode, verifier in [('v1', 'unsigned'), ('transparency', 'unsigned'), ('invalid', 'unsigned'), ('unsigned', 'signed'), ('unsigned', 'tool-error'), ('unsigned', 'ambiguous')]:
            with self.subTest(mode=mode, verifier=verifier):
                self.assertNotEqual(0, self.run_check(mode, verifier).returncode)

if __name__ == '__main__': unittest.main(verbosity=2)
