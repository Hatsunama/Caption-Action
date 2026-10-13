"""Entirely offline tests: synthetic ZIPs, fixtures and mocked verify commands.

No signer, emulator, SDK, build, installation or network command is executed.
The real frozen unsigned APK/source may optionally be read for their known hashes;
no locally signed APK is ever read. Synthetic identities are scoped mock patches.
"""
from __future__ import annotations

from contextlib import ExitStack
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import warnings
import zipfile

import artifact_gate as gate

CERT = "4" * 64
OTHER_CERT = "5" * 64
OUTPUT = "\n".join([
    "Verifies",
    "Verified using v1 scheme (JAR signing): false",
    "Verified using v2 scheme (APK Signature Scheme v2): false",
    "Verified using v3 scheme (APK Signature Scheme v3): true",
    "Verified using v3.1 scheme (APK Signature Scheme v3.1): false",
    "Verified using v4 scheme (APK Signature Scheme v4): false",
    "Verified for SourceStamp: false",
    "Number of signers: 1",
    "Signer #1 certificate DN: CN=Synthetic Offline Fixture",
    "Signer #1 certificate SHA-256 digest: " + CERT,
    "Signer #1 certificate SHA-1 digest: " + "1" * 40,
    "Signer #1 certificate MD5 digest: " + "2" * 32,
    "Signer #1 key algorithm: RSA",
    "Signer #1 key size (bits): 2048",
    "Signer #1 public key SHA-256 digest: " + "3" * 64,
]) + "\n"


def sha(blob):
    return hashlib.sha256(blob).hexdigest()


def make_zip(entries=None, *, extra=b"", mode=None, compression=zipfile.ZIP_STORED):
    if entries is None:
        entries = [("classes.dex", b"synthetic-dex"), ("AndroidManifest.xml", b"synthetic-manifest")]
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", compression=compression) as archive:
        for name, data in entries:
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 13, 0, 0, 0))
            info.compress_type = compression
            info.extra = extra
            if mode is not None:
                info.external_attr = mode << 16
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                archive.writestr(info, data)
    return stream.getvalue()


def signing_block(*pairs):
    if not pairs:
        pairs = ((0xF05368C0, b"synthetic-signature-never-cryptographically-accepted"),)
    body = b"".join(struct.pack("<QI", len(data) + 4, block_id) + data for block_id, data in pairs)
    size = len(body) + 24
    return struct.pack("<Q", size) + body + struct.pack("<Q", size) + b"APK Sig Block 42"


def add_signing_block(blob, block=None, padding=b""):
    block = signing_block() if block is None else block
    end = len(blob) - 22
    cd_offset = struct.unpack_from("<I", blob, end + 16)[0]
    result = bytearray(blob[:cd_offset] + padding + block + blob[cd_offset:])
    added = len(padding) + len(block)
    struct.pack_into("<I", result, end + added + 16, cd_offset + added)
    return bytes(result)


def insert_gap(blob, index, gap):
    """Insert an unindexed region before one entry, fixing central-directory offsets."""
    with zipfile.ZipFile(io.BytesIO(blob)) as archive:
        offset = archive.infolist()[index].header_offset
    old_end = len(blob) - 22
    old_cd = struct.unpack_from("<I", blob, old_end + 16)[0]
    result = bytearray(blob[:offset] + gap + blob[offset:])
    end, pos = old_end + len(gap), old_cd + len(gap)
    struct.pack_into("<I", result, end + 16, pos)
    while pos < end:
        if result[pos:pos + 4] != b"PK\x01\x02":
            raise AssertionError("fixture central directory invalid")
        name_n, extra_n, comment_n = struct.unpack_from("<HHH", result, pos + 28)
        local_off = struct.unpack_from("<I", result, pos + 42)[0]
        if local_off >= offset:
            struct.pack_into("<I", result, pos + 42, local_off + len(gap))
        pos += 46 + name_n + extra_n + comment_n
    return bytes(result)


class SyntheticGateFixture(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.temp = self.stack.enter_context(tempfile.TemporaryDirectory(prefix="caption-artifact-gate-"))
        self.root = Path(self.temp)
        self.unsigned = make_zip()
        self.signed = add_signing_block(self.unsigned)
        self.source = b"synthetic source archive bytes"
        self.launcher = b"#!/bin/sh\n# Never executed in these offline tests.\nexit 99\n"
        self.jar = b"synthetic jar bytes, not executable"
        self.tool_dir = self.root / "sdk" / "build-tools" / "36.0.0"
        (self.tool_dir / "lib").mkdir(parents=True)
        (self.tool_dir / "apksigner").write_bytes(self.launcher)
        (self.tool_dir / "apksigner").chmod(0o755)
        (self.tool_dir / "lib" / "apksigner.jar").write_bytes(self.jar)
        self.paths = {"signed_apk": self.root / "signed.apk", "unsigned_apk": self.root / "unsigned.apk",
                      "source_archive": self.root / "source.tar.gz", "receipt_path": self.root / "receipt.json"}
        self.paths["signed_apk"].write_bytes(self.signed)
        self.paths["unsigned_apk"].write_bytes(self.unsigned)
        self.paths["source_archive"].write_bytes(self.source)
        self.identity = {
            "schema_version": 1, "delivery_parent": gate.DELIVERY_PARENT,
            "unsigned_sha256": sha(self.unsigned), "source_sha256": sha(self.source),
            "signed_sha256": sha(self.signed), "signed_size": len(self.signed),
            "receipt_sha256": "0" * 64, "signer_cert_sha256": CERT,
            "build_tools_version": "36.0.0", "apksigner_sha256": sha(self.launcher),
            "apksigner_jar_sha256": sha(self.jar), **gate.PACKAGE_FACTS,
        }
        records = gate.verify_payload(self.unsigned, self.signed)[0]
        self.receipt = {
            "unsigned_artifact_commit": gate.UNSIGNED_DELIVERY_PARENT,
            "unsigned_sha256": sha(self.unsigned),
            "source_archive_sha256_expected_from_review_receipt": sha(self.source),
            "source_archive_independently_verified": False,
            "signed_sha256": sha(self.signed), "signed_bytes": len(self.signed),
            "certificate_sha256": CERT, "signature_verified": True,
            "signature_scheme_v3": True, "unsigned_payload_entries": len(records),
            "all_unsigned_payload_entries_unchanged": True,
            "package": gate.PACKAGE_FACTS["package_name"],
            **{key: gate.PACKAGE_FACTS[key] for key in ("version_code", "version_name", "min_sdk", "target_sdk")},
            "permissions": list(gate.PACKAGE_PERMISSIONS),
            "runtime_executed": False, "signing_key_created_or_exported": False,
        }
        self.write_receipt()
        self.stack.enter_context(patch.object(gate, "UNSIGNED_SHA256", sha(self.unsigned)))
        self.stack.enter_context(patch.object(gate, "SOURCE_SHA256", sha(self.source)))
        self.stack.enter_context(patch.object(gate, "SIGNED_IDENTITY", self.identity))
        self.runner = Mock(return_value=SimpleNamespace(returncode=0, stdout=OUTPUT, stderr=""))

    def write_receipt(self, raw=None):
        raw = json.dumps(self.receipt, sort_keys=True).encode() if raw is None else raw
        self.paths["receipt_path"].write_bytes(raw)
        self.identity["receipt_sha256"] = sha(raw)

    def verify(self, **overrides):
        args = dict(delivery_parent=gate.DELIVERY_PARENT, **self.paths,
                    android_sdk_root=self.root / "sdk", runner=self.runner)
        args.update(overrides)
        return gate.verify_artifacts(**args)

    def test_positive_synthetic_gate_holds_descriptor_and_bounded_read_only_command(self):
        with self.verify() as artifact:
            self.assertEqual(Path(artifact.apk_path).read_bytes(), self.signed)
            artifact.recheck()
            self.assertEqual(artifact.identity["signer_cert_sha256"], CERT)
            self.assertEqual(len(artifact.payload_entries), 2)
            argv = self.runner.call_args.args[0]
            self.assertEqual(argv, [str(self.tool_dir / "apksigner"), "verify", "--verbose", "--print-certs",
                                    "-Werr", artifact.apk_path])
            self.assertEqual(self.runner.call_args.kwargs,
                             {"timeout": 30.0, "max_output_bytes": 65536})
            self.assertNotIn("sign", argv)
        with self.assertRaises(gate.ArtifactGateError):
            _ = artifact.apk_path

    def test_absent_identity_fails_before_file_read_or_runner(self):
        with patch.object(gate, "SIGNED_IDENTITY", None), patch.object(gate, "CheckedFile") as reader:
            with self.assertRaisesRegex(gate.ArtifactGateError, "no exact approved"):
                self.verify()
            reader.assert_not_called()
        self.runner.assert_not_called()

    def test_wrong_parent_fails_before_files(self):
        with patch.object(gate, "CheckedFile") as reader:
            with self.assertRaises(gate.ArtifactGateError):
                self.verify(delivery_parent="0" * 40)
            reader.assert_not_called()

    def test_incomplete_identity(self):
        del self.identity["receipt_sha256"]
        with self.assertRaises(gate.ArtifactGateError):
            self.verify()
        self.runner.assert_not_called()

    def test_unknown_identity_key(self):
        self.identity["approval_from_receipt"] = True
        with self.assertRaises(gate.ArtifactGateError):
            self.verify()

    def test_fixed_identity_fields_and_types(self):
        for key, value in [("package_name", "com.other.app"), ("version_code", True), ("min_sdk", 28),
                           ("target_sdk", 35), ("version_name", "0.2"), ("unsigned_sha256", "a" * 64),
                           ("source_sha256", "a" * 64), ("delivery_parent", "0" * 40),
                           ("signed_size", True), ("signed_size", 0), ("build_tools_version", "../36.0.0"),
                           ("signer_cert_sha256", ""), ("signed_sha256", sha(self.unsigned))]:
            with self.subTest(key=key, value=value):
                previous = self.identity[key]
                self.identity[key] = value
                try:
                    with self.assertRaises(gate.ArtifactGateError):
                        self.verify()
                finally:
                    self.identity[key] = previous
        self.runner.assert_not_called()

    def test_wrong_artifact_hashes(self):
        for key in self.paths:
            with self.subTest(key=key):
                old = self.paths[key].read_bytes()
                self.paths[key].write_bytes(old + b"tampered")
                try:
                    with self.assertRaises(gate.ArtifactGateError):
                        self.verify()
                finally:
                    self.paths[key].write_bytes(old)
        self.runner.assert_not_called()

    def test_wrong_signed_size(self):
        self.identity["signed_size"] += 1
        with self.assertRaisesRegex(gate.ArtifactGateError, "bytes/size"):
            self.verify()

    def test_receipt_wrong_bound_facts_even_when_receipt_digest_matches(self):
        original = copy.deepcopy(self.receipt)
        for key, value in [("certificate_sha256", OTHER_CERT), ("unsigned_sha256", "0" * 64),
                           ("source_archive_sha256_expected_from_review_receipt", "0" * 64),
                           ("signed_sha256", "0" * 64), ("signed_bytes", 1),
                           ("package", "other.package"), ("version_code", True),
                           ("unsigned_artifact_commit", gate.DELIVERY_PARENT),
                           ("permissions", ["android.permission.INTERNET"]),
                           ("min_sdk", 28), ("target_sdk", 35), ("version_name", "0.2"),
                           ("source_archive_independently_verified", True),
                           ("signature_verified", False), ("signature_scheme_v3", False),
                           ("all_unsigned_payload_entries_unchanged", False),
                           ("runtime_executed", True), ("signing_key_created_or_exported", True)]:
            with self.subTest(key=key):
                self.receipt = copy.deepcopy(original)
                self.receipt[key] = value
                self.write_receipt()
                with self.assertRaises(gate.ArtifactGateError):
                    self.verify()
        self.runner.assert_not_called()

    def test_receipt_wrong_payload(self):
        self.receipt["unsigned_payload_entries"] += 1
        self.write_receipt()
        with self.assertRaisesRegex(gate.ArtifactGateError, "unsigned_payload_entries"):
            self.verify()

    def test_receipt_rejects_invented_raw_output_and_payload_fields(self):
        for key, value in [("apksigner_stdout", OUTPUT), ("payload_entries", []),
                           ("apksigner_stderr", ""), ("apksigner_returncode", 0),
                           ("build_tools_version", "36.0.0"), ("signature_scheme_v2", False)]:
            self.receipt[key] = value
            self.write_receipt()
            try:
                with self.subTest(key=key), self.assertRaisesRegex(gate.ArtifactGateError, "schema"):
                    self.verify()
            finally:
                del self.receipt[key]
        self.runner.assert_not_called()

    def test_receipt_requires_exact_types_and_all_fields(self):
        original = copy.deepcopy(self.receipt)
        for key, value in original.items():
            if type(value) is bool:
                wrong = int(value)
            elif type(value) is int:
                wrong = float(value)
            elif type(value) is list:
                wrong = {str(i): item for i, item in enumerate(value)}
            else:
                wrong = None
            with self.subTest(key=key):
                for operation in ("wrong_type", "missing"):
                    self.receipt = copy.deepcopy(original)
                    if operation == "wrong_type":
                        self.receipt[key] = wrong
                    else:
                        del self.receipt[key]
                    self.write_receipt()
                    with self.assertRaises(gate.ArtifactGateError):
                        self.verify()
        self.runner.assert_not_called()

    def test_receipt_duplicate_key(self):
        self.write_receipt(b'{"schema_version":1,"schema_version":1}')
        with self.assertRaisesRegex(gate.ArtifactGateError, "duplicate JSON"):
            self.verify()

    def test_receipt_invalid_json(self):
        for raw in (b"not-json", b"\xff", b'{"value":NaN}', b"[]"):
            with self.subTest(raw=raw):
                self.write_receipt(raw)
                with self.assertRaises(gate.ArtifactGateError):
                    self.verify()

    def test_receipt_unexpected_key(self):
        self.receipt["extra"] = "not approved"
        self.write_receipt()
        with self.assertRaisesRegex(gate.ArtifactGateError, "schema"):
            self.verify()

    def test_wrong_signer_live_output(self):
        self.runner.return_value.stdout = OUTPUT.replace(CERT, OTHER_CERT)
        with self.assertRaisesRegex(gate.ArtifactGateError, "live APK signer"):
            self.verify()

    def test_live_scheme_disagrees_with_receipt(self):
        self.runner.return_value.stdout = OUTPUT.replace("v3): true", "v3): false").replace("v2): false", "v2): true")
        with self.assertRaisesRegex(gate.ArtifactGateError, "disagrees"):
            self.verify()

    def test_signing_structures_must_match_reported_schemes(self):
        self.runner.return_value.stdout = OUTPUT.replace("v2): false", "v2): true")
        with self.assertRaisesRegex(gate.ArtifactGateError, "blocks/verification"):
            self.verify()

    def test_receipt_does_not_infer_unreported_schemes(self):
        both = signing_block((0x7109871A, b"v2-signature"), (0xF05368C0, b"v3-signature"))
        self.signed = add_signing_block(self.unsigned, both)
        self.paths["signed_apk"].write_bytes(self.signed)
        self.identity.update(signed_sha256=sha(self.signed), signed_size=len(self.signed))
        self.receipt.update(signed_sha256=sha(self.signed), signed_bytes=len(self.signed))
        self.write_receipt()
        with self.verify() as verified:
            self.assertFalse(verified.verification["schemes"]["v2"])
            self.assertEqual(verified.verification["legacy_schemes_not_relied_upon"], ["v2"])
        self.runner.return_value.stdout = OUTPUT.replace("v2): false", "v2): true")
        with self.verify() as verified:
            self.assertTrue(verified.verification["schemes"]["v2"])
            self.assertTrue(verified.verification["schemes"]["v3"])

    def test_min_sdk_29_default_v3_verification_with_unused_v1_v2_is_accepted(self):
        entries = [("classes.dex", b"synthetic-dex"), ("AndroidManifest.xml", b"synthetic-manifest"),
                   ("META-INF/MANIFEST.MF", b"manifest"), ("META-INF/CERT.SF", b"signature"),
                   ("META-INF/CERT.RSA", b"signed")]
        blocks = signing_block((0x7109871A, b"v2-signature"), (0xF05368C0, b"v3-signature"))
        self.signed = add_signing_block(make_zip(entries), blocks)
        self.paths["signed_apk"].write_bytes(self.signed)
        self.identity.update(signed_sha256=sha(self.signed), signed_size=len(self.signed))
        self.receipt.update(signed_sha256=sha(self.signed), signed_bytes=len(self.signed))
        self.write_receipt()
        with self.verify() as verified:
            self.assertEqual(verified.verification["required_scheme"], "v3")
            self.assertEqual(verified.verification["verified_for_min_sdk"], 29)
            self.assertEqual(verified.verification["legacy_schemes_not_relied_upon"], ["v1", "v2"])
            self.assertFalse(verified.verification["schemes"]["v1"])
            self.assertFalse(verified.verification["schemes"]["v2"])
            self.assertTrue(verified.verification["schemes"]["v3"])
        # Exact-blob and clean live-verifier checks still apply to these legacy bytes.
        self.paths["signed_apk"].write_bytes(self.signed.replace(b"v2-signature", b"v2-tampered!"))
        with self.assertRaisesRegex(gate.ArtifactGateError, "bytes/size"):
            self.verify()
        self.paths["signed_apk"].write_bytes(self.signed)
        self.runner.return_value.stdout = OUTPUT.replace(CERT, OTHER_CERT)
        with self.assertRaisesRegex(gate.ArtifactGateError, "live APK signer"):
            self.verify()
        self.runner.return_value.stdout = OUTPUT
        self.runner.return_value.returncode = 1
        with self.assertRaisesRegex(gate.ArtifactGateError, "apksigner failed"):
            self.verify()

    def test_required_v3_and_no_rotation_even_if_verifier_claims_success(self):
        for block in (signing_block((0x7109871A, b"v2-only")),
                      signing_block((0xF05368C0, b"v3"), (0x1B93AD61, b"v3.1-rotation"))):
            with self.subTest(block=block):
                self.signed = add_signing_block(self.unsigned, block)
                self.paths["signed_apk"].write_bytes(self.signed)
                self.identity.update(signed_sha256=sha(self.signed), signed_size=len(self.signed))
                self.receipt.update(signed_sha256=sha(self.signed), signed_bytes=len(self.signed))
                self.write_receipt()
                with self.assertRaisesRegex(gate.ArtifactGateError, "blocks/verification"):
                    self.verify()

    def test_live_v1_claim_still_requires_complete_v1_structures(self):
        self.runner.return_value.stdout = OUTPUT.replace("signing): false", "signing): true")
        with self.assertRaisesRegex(gate.ArtifactGateError, "v1 structures"):
            self.verify()

    def test_apksigner_nonzero_stderr_timeout_and_bad_output(self):
        cases = [SimpleNamespace(returncode=1, stdout=OUTPUT, stderr=""),
                 SimpleNamespace(returncode=0, stdout=OUTPUT, stderr="WARNING: test"),
                 SimpleNamespace(returncode=0, stdout="", stderr="")]
        for result in cases:
            self.runner.return_value = result
            with self.subTest(result=result), self.assertRaises(gate.ArtifactGateError):
                self.verify()
        self.runner.side_effect = TimeoutError("deadline")
        with self.assertRaisesRegex(gate.ArtifactGateError, "did not complete"):
            self.verify()

    def test_runner_invalid_result(self):
        self.runner.return_value = None
        with self.assertRaisesRegex(gate.ArtifactGateError, "invalid verification result"):
            self.verify()

    def test_sdk_pins_are_required(self):
        for target in (self.tool_dir / "apksigner", self.tool_dir / "lib" / "apksigner.jar"):
            with self.subTest(target=target):
                original = target.read_bytes()
                target.write_bytes(b"wrong official tool bytes")
                with self.assertRaisesRegex(gate.ArtifactGateError, "verifier hash"):
                    self.verify()
                target.write_bytes(original)
        self.runner.assert_not_called()

    def test_sdk_missing_not_downloaded(self):
        (self.tool_dir / "apksigner").unlink()
        with self.assertRaises(gate.ArtifactGateError):
            self.verify()
        self.runner.assert_not_called()

    def test_sdk_launcher_not_executable(self):
        (self.tool_dir / "apksigner").chmod(0o644)
        with self.assertRaisesRegex(gate.ArtifactGateError, "not executable"):
            self.verify()

    def test_leaf_symlink_rejected(self):
        for key in self.paths:
            with self.subTest(key=key):
                real = self.paths[key]
                link = self.root / "link"
                link.symlink_to(real)
                try:
                    with self.assertRaises(gate.ArtifactGateError):
                        self.verify(**{key: link})
                finally:
                    link.unlink()
        self.runner.assert_not_called()

    def test_sdk_symlink_rejected(self):
        real = self.tool_dir / "lib" / "apksigner.jar"
        backup = self.tool_dir / "lib" / "real.jar"
        real.rename(backup)
        real.symlink_to(backup)
        with self.assertRaises(gate.ArtifactGateError):
            self.verify()
        self.runner.assert_not_called()

    def test_ancestor_symlink_rejected(self):
        link = self.root / "alias"
        link.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(gate.ArtifactGateError):
            self.verify(signed_apk=link / "signed.apk")
        self.runner.assert_not_called()

    def test_nonregular_file_fifo_is_rejected_without_blocking(self):
        fifo = self.root / "fifo"
        os.mkfifo(fifo)
        with self.assertRaisesRegex(gate.ArtifactGateError, "regular file"):
            self.verify(signed_apk=fifo)
        self.runner.assert_not_called()

    def test_relative_traversal_and_directory_paths(self):
        for path in ("signed.apk", str(self.root) + "/../signed.apk", str(self.root) + "//signed.apk", self.root):
            with self.subTest(path=path), self.assertRaises(gate.ArtifactGateError):
                self.verify(signed_apk=path)

    def test_file_mutation_during_runner_fails(self):
        def mutate(*_args, **_kwargs):
            self.paths["signed_apk"].write_bytes(self.signed + b"changed")
            return SimpleNamespace(returncode=0, stdout=OUTPUT, stderr="")
        self.runner.side_effect = mutate
        with self.assertRaisesRegex(gate.ArtifactGateError, "changed"):
            self.verify()

    def test_file_replacement_during_runner_fails(self):
        def replace(*_args, **_kwargs):
            backup = self.root / "replaced.apk"
            backup.write_bytes(self.signed)
            backup.replace(self.paths["signed_apk"])
            return SimpleNamespace(returncode=0, stdout=OUTPUT, stderr="")
        self.runner.side_effect = replace
        with self.assertRaises(gate.ArtifactGateError):
            self.verify()

    def test_sdk_mutation_during_runner_fails(self):
        def mutate(*_args, **_kwargs):
            (self.tool_dir / "lib" / "apksigner.jar").write_bytes(b"changed")
            return SimpleNamespace(returncode=0, stdout=OUTPUT, stderr="")
        self.runner.side_effect = mutate
        with self.assertRaises(gate.ArtifactGateError):
            self.verify()

    def test_retained_descriptor_and_recheck_detect_replacement_before_install(self):
        with self.verify() as artifact:
            stable_path = artifact.apk_path
            replacement = self.root / "replacement.apk"
            replacement.write_bytes(b"arbitrary")
            replacement.replace(self.paths["signed_apk"])
            self.assertEqual(Path(stable_path).read_bytes(), self.signed)
            with self.assertRaises(gate.ArtifactGateError):
                artifact.recheck()

    def test_ancestor_replacement_after_open_fails(self):
        parent = self.root / "artifact-dir"
        parent.mkdir()
        target = parent / "file"
        target.write_bytes(b"checked")
        with gate.CheckedFile(target, 100) as checked:
            parent.rename(self.root / "old-artifact-dir")
            parent.mkdir()
            (parent / "file").write_bytes(b"checked")
            with self.assertRaisesRegex(gate.ArtifactGateError, "ancestor"):
                checked.recheck()

    def test_failure_closes_all_retained_descriptors(self):
        if not Path("/proc/self/fd").is_dir():
            self.skipTest("Linux procfs is required")
        before = len(list(Path("/proc/self/fd").iterdir()))
        self.runner.return_value.returncode = 1
        for _ in range(4):
            with self.assertRaises(gate.ArtifactGateError):
                self.verify()
        self.assertEqual(before, len(list(Path("/proc/self/fd").iterdir())))


class ZipPayloadTests(unittest.TestCase):
    def setUp(self):
        self.unsigned = make_zip()
        self.signed = add_signing_block(self.unsigned)

    def test_unchanged_payload_with_signing_block(self):
        records, schemes, has_v1 = gate.verify_payload(self.unsigned, self.signed)
        self.assertEqual(len(records), 2)
        self.assertEqual(schemes, {"v3"})
        self.assertFalse(has_v1)

    def alignment_padding(self):
        cd_offset = struct.unpack_from("<I", self.unsigned, len(self.unsigned) - 6)[0]
        padding = bytes((-cd_offset) % 4096)
        self.assertGreater(len(padding), 1)
        return padding

    def test_minimal_zero_alignment_before_signing_block(self):
        padding = self.alignment_padding()
        signed = add_signing_block(self.unsigned, padding=padding)
        records, schemes, has_v1 = gate.verify_payload(self.unsigned, signed)
        self.assertEqual(len(records), 2)
        self.assertEqual(schemes, {"v3"})
        self.assertFalse(has_v1)
        self.assertEqual(gate.inspect_zip_payload(signed).signing_alignment_bytes, len(padding))

    def test_nonzero_alignment_before_signing_block_rejected(self):
        padding = self.alignment_padding()
        for offset in (0, len(padding) // 2, len(padding) - 1):
            bad = bytearray(padding)
            bad[offset] = 1
            with self.subTest(offset=offset), self.assertRaisesRegex(gate.ArtifactGateError, "alignment padding"):
                gate.verify_payload(self.unsigned, add_signing_block(self.unsigned, padding=bytes(bad)))

    def test_overlong_zero_alignment_before_signing_block_rejected(self):
        for padding in (bytes(4096), self.alignment_padding() + bytes(4096)):
            with self.subTest(length=len(padding)), self.assertRaisesRegex(gate.ArtifactGateError, "alignment padding"):
                gate.verify_payload(self.unsigned, add_signing_block(self.unsigned, padding=padding))

    def test_misaligned_and_truncated_zero_padding_rejected(self):
        padding = self.alignment_padding()
        for bad in (padding[:-1], padding + bytes(1), padding[:1]):
            with self.subTest(length=len(bad)), self.assertRaisesRegex(gate.ArtifactGateError, "alignment padding"):
                gate.verify_payload(self.unsigned, add_signing_block(self.unsigned, padding=bad))

    def test_alignment_does_not_hide_truncated_or_missing_signing_block(self):
        padding = self.alignment_padding()
        block = signing_block()
        for bad in (block[:-1], block[1:], b"", block[:24]):
            with self.subTest(length=len(bad)), self.assertRaises(gate.ArtifactGateError):
                gate.verify_payload(self.unsigned, add_signing_block(self.unsigned, bad, padding))

    def test_no_alignment_is_needed_when_payload_is_already_aligned(self):
        # Extend an indexed entry until its local end is an exact page boundary.
        amount = len(self.alignment_padding())
        unsigned = make_zip([("classes.dex", b"synthetic-dex"),
                             ("AndroidManifest.xml", b"synthetic-manifest" + bytes(amount))])
        signed = add_signing_block(unsigned)
        gate.verify_payload(unsigned, signed)
        self.assertEqual(gate.inspect_zip_payload(signed).signing_alignment_bytes, 0)
        with self.assertRaisesRegex(gate.ArtifactGateError, "alignment padding"):
            gate.verify_payload(unsigned, add_signing_block(unsigned, padding=bytes(4096)))

    def test_payload_change(self):
        bad = add_signing_block(make_zip([("classes.dex", b"changed"), ("AndroidManifest.xml", b"synthetic-manifest")]))
        with self.assertRaisesRegex(gate.ArtifactGateError, "changed"):
            gate.verify_payload(self.unsigned, bad)

    def test_payload_missing(self):
        bad = add_signing_block(make_zip([("classes.dex", b"synthetic-dex")]))
        with self.assertRaisesRegex(gate.ArtifactGateError, "lost"):
            gate.verify_payload(self.unsigned, bad)

    def test_new_nonsignature_entry_including_meta_inf_not_excluded(self):
        base = [("classes.dex", b"synthetic-dex"), ("AndroidManifest.xml", b"synthetic-manifest")]
        for name in ("classes2.dex", "assets/extra", "META-INF/extra", "META-INF/foo.RSA", "META-INF/services/new"):
            with self.subTest(name=name), self.assertRaises(gate.ArtifactGateError):
                gate.verify_payload(self.unsigned, add_signing_block(make_zip(base + [(name, b"arbitrary")])) )

    def test_duplicate_entries_and_case_alias(self):
        for entries in [ [("classes.dex", b"x"), ("classes.dex", b"y")],
                         [("classes.dex", b"x"), ("CLASSES.DEX", b"y")] ]:
            with self.subTest(entries=entries), self.assertRaisesRegex(gate.ArtifactGateError, "duplicate"):
                gate.inspect_zip_payload(make_zip(entries))

    def test_v1_must_be_complete_single_signer_pair(self):
        base = [("classes.dex", b"synthetic-dex"), ("AndroidManifest.xml", b"synthetic-manifest")]
        valid = base + [("META-INF/MANIFEST.MF", b"manifest"), ("META-INF/CERT.SF", b"signature"),
                        ("META-INF/CERT.RSA", b"signed" )]
        records, schemes, has_v1 = gate.verify_payload(self.unsigned, add_signing_block(make_zip(valid)))
        self.assertTrue(has_v1)
        for added in [valid[:-1], valid + [("META-INF/OTHER.RSA", b"extra")],
                      base + [("META-INF/MANIFEST.MF", b"manifest"), ("META-INF/CERT.SF", b"signature"),
                              ("META-INF/OTHER.RSA", b"signed")],
                      base + [("META-INF/MANIFEST.MF", b""), ("META-INF/CERT.SF", b"signature"),
                              ("META-INF/CERT.RSA", b"signed")]]:
            with self.subTest(entries=added), self.assertRaises(gate.ArtifactGateError):
                gate.verify_payload(self.unsigned, add_signing_block(make_zip(added)))

    def test_no_signature_structure(self):
        with self.assertRaisesRegex(gate.ArtifactGateError, "no signature"):
            gate.verify_payload(self.unsigned, self.unsigned)

    def test_zip_alignment_changes_only(self):
        for extra in (b"\x00\x00", struct.pack("<HHH", 0xD935, 4, 4) + b"\x00\x00"):
            with self.subTest(extra=extra):
                signed = add_signing_block(make_zip(extra=extra))
                gate.verify_payload(self.unsigned, signed)

    def test_bad_alignment_or_unknown_extra(self):
        for extra in (struct.pack("<HHH", 0x1234, 2, 4), struct.pack("<HHH", 0xD935, 2, 3),
                      struct.pack("<HHH", 0xD935, 4, 4) + b"\x00X"):
            with self.subTest(extra=extra), self.assertRaises(gate.ArtifactGateError):
                gate.verify_payload(self.unsigned, add_signing_block(make_zip(extra=extra)))

    def test_new_hidden_gap_fails(self):
        bad = add_signing_block(insert_gap(self.unsigned, 1, b"arbitrary hidden data"))
        with self.assertRaisesRegex(gate.ArtifactGateError, "hidden ZIP bytes"):
            gate.verify_payload(self.unsigned, bad)

    def test_exact_inherited_padding_allowed_preserved_or_removed(self):
        inherited = bytes.fromhex("504b030400000000000021082102000000000000000000000000000016c3") + bytes(49942)
        base = make_zip([("classes2.dex", b"synthetic-dex"), ("AndroidManifest.xml", b"synthetic-manifest")])
        unsigned = insert_gap(base, 1, inherited)
        gate.verify_payload(unsigned, add_signing_block(unsigned))
        gate.verify_payload(unsigned, add_signing_block(base))
        moved = add_signing_block(insert_gap(base, 0, inherited))
        with self.assertRaisesRegex(gate.ArtifactGateError, "hidden ZIP bytes"):
            gate.verify_payload(unsigned, moved)

    def test_arbitrary_reference_gap_is_not_treated_as_alignment(self):
        unsigned = insert_gap(self.unsigned, 1, b"arbitrary inherited data")
        with self.assertRaisesRegex(gate.ArtifactGateError, "unapproved hidden data"):
            gate.verify_payload(unsigned, add_signing_block(unsigned))

    def test_unapproved_signing_block_ids_and_nonzero_padding(self):
        pairs = [((0xDEADBEEF, b"payload"),), ((0x7109871A, b"a"), (0x7109871A, b"b")),
                 ((0x7109871A, b"a"), (0x42726577, b"nonzero")), ((0x42726577, b"\x00"),)]
        for items in pairs:
            with self.subTest(items=items), self.assertRaises(gate.ArtifactGateError):
                gate.verify_payload(self.unsigned, add_signing_block(self.unsigned, signing_block(*items)))

    def test_verity_zero_padding(self):
        block = signing_block((0x7109871A, b"signature"), (0x42726577, bytes(64)))
        gate.verify_payload(self.unsigned, add_signing_block(self.unsigned, block))

    def test_malformed_signing_block_size(self):
        bad = bytearray(signing_block())
        bad[0] ^= 1
        with self.assertRaisesRegex(gate.ArtifactGateError, "block size"):
            gate.verify_payload(self.unsigned, add_signing_block(self.unsigned, bytes(bad)))

    def test_path_names_and_symlink_entries(self):
        for name in ("../escape", "/absolute", "a//b", "a/./b", "a\\b", "directory/"):
            with self.subTest(name=name), self.assertRaises(gate.ArtifactGateError):
                gate.inspect_zip_payload(make_zip([(name, b"value")]))
        with self.assertRaisesRegex(gate.ArtifactGateError, "nonregular"):
            gate.inspect_zip_payload(make_zip([("link", b"target")], mode=0o120777))

    def test_trailing_bytes_and_comments(self):
        for blob in (self.signed + b"suffix", self.signed + b"PK\x05\x06"):
            with self.assertRaises(gate.ArtifactGateError):
                gate.inspect_zip_payload(blob)
        out = io.BytesIO(self.unsigned)
        with zipfile.ZipFile(out, "a") as archive:
            archive.comment = b"comment"
        with self.assertRaises(gate.ArtifactGateError):
            gate.inspect_zip_payload(out.getvalue())

    def test_local_filename_mismatch(self):
        blob = bytearray(self.unsigned)
        blob[30] ^= 1
        with self.assertRaisesRegex(gate.ArtifactGateError, "filename mismatch"):
            gate.inspect_zip_payload(bytes(blob))

    def test_local_size_mismatch(self):
        blob = bytearray(self.unsigned)
        blob[22] ^= 1
        with self.assertRaisesRegex(gate.ArtifactGateError, "header mismatch"):
            gate.inspect_zip_payload(bytes(blob))

    def test_recompression_is_not_a_signing_only_change(self):
        recompressed = add_signing_block(make_zip(compression=zipfile.ZIP_DEFLATED))
        with self.assertRaisesRegex(gate.ArtifactGateError, "changed"):
            gate.verify_payload(self.unsigned, recompressed)

    def test_bounded_decompression(self):
        with patch.object(gate, "MAX_ENTRY_BYTES", 4), self.assertRaises(gate.ArtifactGateError):
            gate.inspect_zip_payload(self.unsigned)
        with patch.object(gate, "MAX_PAYLOAD_BYTES", 20), self.assertRaises(gate.ArtifactGateError):
            gate.inspect_zip_payload(self.unsigned)


class VerifierParserTests(unittest.TestCase):
    def test_valid_output(self):
        parsed = gate.parse_apksigner_verify_output(OUTPUT)
        self.assertEqual(parsed["signer_cert_sha256"], CERT)
        self.assertTrue(parsed["schemes"]["v3"])

    def test_every_scheme_and_stamp_result_is_required(self):
        for line in OUTPUT.splitlines():
            if line.startswith(("Verified using", "Verified for SourceStamp")):
                with self.subTest(line=line), self.assertRaisesRegex(gate.ArtifactGateError, "incomplete"):
                    gate.parse_apksigner_verify_output(OUTPUT.replace(line + "\n", ""))

    def test_false_missing_duplicate_multiple_and_warning_failures(self):
        bad = [OUTPUT.replace("Verifies\n", ""), OUTPUT + "Verifies\n",
               OUTPUT.replace("Number of signers: 1", "Number of signers: 2"),
               OUTPUT + "Number of signers: 1\n",
               OUTPUT + "Signer #2 certificate SHA-256 digest: " + CERT + "\n",
               OUTPUT + "Signer #1 certificate SHA-256 digest: " + CERT + "\n",
               OUTPUT.replace("v3): true", "v3): false"),
               OUTPUT + "Verified using v2 scheme (APK Signature Scheme v2): true\n",
               OUTPUT + "WARNING: anything\n", OUTPUT + "ERROR: anything\n",
               OUTPUT + "DOES NOT VERIFY\n", OUTPUT.replace(CERT, "not-a-digest"),
               OUTPUT + "unexpected success claims\n",
               OUTPUT + "Signer #1 certificate DN: CN=SecondDN\n",
               OUTPUT + "Verified for SourceStamp: false\n",
               OUTPUT.replace("SourceStamp: false", "SourceStamp: true"),
               OUTPUT.replace("v3.1): false", "v3.1): true"),
               OUTPUT.replace("v4): false", "v4): true"),
               OUTPUT + "Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate DN: CN=Rotated\n",
               OUTPUT + "\x00", OUTPUT + "a" * gate.MAX_VERIFY_OUTPUT_BYTES]
        for value in bad:
            with self.subTest(value=value[-100:]), self.assertRaises(gate.ArtifactGateError):
                gate.parse_apksigner_verify_output(value)

    def test_wrong_types_and_diagnostics(self):
        for stdout, stderr, code in [(b"Verifies", "", 0), (OUTPUT, b"", 0), (OUTPUT, "", False),
                                     (OUTPUT, "warning", 0), (OUTPUT, " \n", 0), (OUTPUT, "", 1)]:
            with self.subTest(code=code, stderr=stderr), self.assertRaises(gate.ArtifactGateError):
                gate.parse_apksigner_verify_output(stdout, stderr, code)


class FrozenContractTests(unittest.TestCase):
    def test_shipped_gate_is_unapproved_with_exact_frozen_constants(self):
        self.assertEqual(gate.require_approved_identity(gate.DELIVERY_PARENT)['signed_sha256'], '583bf552e66564656e94d2f5c406b1d4235c82e72997aae634e90a301a077ccf')
        self.assertEqual(gate.DELIVERY_PARENT, "4062451c315265c827161f7e3ac925ff8df39121")
        self.assertEqual(gate.UNSIGNED_DELIVERY_PARENT, "893e8e06fc5f59914da194e65f72c105631a17cc")
        self.assertEqual(gate.UNSIGNED_SHA256, "1d79341a02ea25673b2c406e8e2c901a9b6db60058bf326c314e2dbeaac267fd")
        self.assertEqual(gate.SOURCE_SHA256, "a898bd4c6b9bfd182b4d14ac99b829416adea7b798d986c13b24c36f6a5ee798")
        self.assertEqual(gate.PACKAGE_FACTS, {"package_name": "com.hatsunama.captionaction.captureprobe",
                                            "version_code": 1, "version_name": "0.1-head28ac",
                                            "min_sdk": 29, "target_sdk": 36})
        self.assertEqual(gate.PACKAGE_PERMISSIONS, (
            "android.permission.RECORD_AUDIO", "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"))

    def test_frozen_unsigned_payload_manifest_if_available(self):
        # These are only the approved frozen *unsigned* input files.
        plan = Path("/workspace/shared/caption-muted-emulator-plan")
        path = plan / "public/tools/muted-capture-probe/artifacts/caption-muted-platform-probe-unsigned.apk"
        if not path.is_file():
            self.skipTest("frozen input is not present; all synthetic tests still run")
        unsigned = path.read_bytes()
        self.assertEqual(sha(unsigned), gate.UNSIGNED_SHA256)
        manifest = json.loads((plan / "unsigned-payload-manifest.json").read_text())
        payload = gate.inspect_zip_payload(unsigned)
        self.assertEqual([entry.receipt_record() for entry in payload.entries], manifest["zip_payload_entries"])
        self.assertEqual(len(payload.entries), 18)
        self.assertEqual(len(payload.gaps), 1)
        self.assertEqual(len(payload.gaps[0][2]), 49972)
        self.assertEqual(payload.gaps[0][:2], ("classes2.dex", "AndroidManifest.xml"))
        source = path.with_name("caption-muted-platform-probe-source.tar.gz")
        self.assertEqual(sha(source.read_bytes()), gate.SOURCE_SHA256)


if __name__ == "__main__":
    unittest.main()
