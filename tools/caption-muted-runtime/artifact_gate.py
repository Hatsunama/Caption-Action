"""Fail-closed, offline identity/payload gate for the dedicated Caption probe.

This module does not download, build, sign, install, or start anything. The sole
external command is read-only verification with an already installed Android
build-tools/apksigner. The caller supplies a bounded runner accepting
``runner(argv, *, timeout, max_output_bytes)`` and returning returncode, stdout,
and stderr attributes. No shell command string is used.

SIGNED_IDENTITY is bound to the root-reviewed exact signed delivery. Only an explicit review of the exact
final bytes, receipt, certificate and official preinstalled verifier may replace
it. Neither a receipt nor a command-line/environment value can approve itself.
The exact dictionary schema (no extra keys) is:
    schema_version: 1
    delivery_parent: DELIVERY_PARENT
    unsigned_sha256: UNSIGNED_SHA256
    source_sha256: SOURCE_SHA256
    signed_sha256: lower-case 64-hex digest, different from unsigned_sha256
    signed_size: positive integer, at most MAX_APK_BYTES
    receipt_sha256: lower-case 64-hex digest of the exact UTF-8 JSON receipt
    signer_cert_sha256: lower-case 64-hex certificate digest
    package_name/version_code/version_name/min_sdk/target_sdk: PACKAGE_FACTS
    build_tools_version: exact installed release, e.g. "36.0.0"
    apksigner_sha256/apksigner_jar_sha256: hashes of the reviewed official
        build-tools/<version>/apksigner and lib/apksigner.jar

The delivered receipt has a strict public-summary schema, checked below. It has
no raw verifier output, per-entry records, build-tools version, or signed-delivery
commit. Its exact-byte hash binds it to the separately reviewed identity. Its
unsigned_artifact_commit remains the original UNSIGNED_DELIVERY_PARENT. The
receipt explicitly says source_archive_independently_verified=false: this gate
independently hashes the source archive and proves every original APK entry and
its metadata unchanged. Package/SDK/permission facts are anchored to that frozen
manifest, not trusted from the receipt alone. The receipt's v3=true claim must
match clean default live verification and a real v3 block. Absent receipt scheme
fields are never inferred. For this fixed minSDK29 artifact the verifier may skip
obsolete v1/v2 schemes. Their structures remain parsed and exact-blob pinned but
are not relied upon; v3 protects the complete APK. Any live-claimed scheme must
have matching structures, and rotated v3.1 blocks remain forbidden. Duplicate
JSON keys, unknown fields, wrong types, and nonfinite values fail.

Use ``with verify_artifacts(...) as verified:`` and install ONLY
``verified.apk_path`` while that descriptor remains open; call recheck immediately
before and after use. This Linux /proc/<pid>/fd path keeps the checked file inode
pinned. All supplied file paths and every ancestor are opened without following
symlinks; inode/path/metadata and byte hashes are checked again after verification.
The preinstalled SDK must be a trusted, non-concurrently-mutated installation;
launcher and JAR are pinned too, but this module is not a sandbox for SDK code.

The frozen unsigned APK has 18 indexed payload entries and one 49,972-byte
unindexed builder padding record. The only tolerated inherited unindexed region
is that exact byte sequence in its original entry boundary, or its removal.
New hidden bytes are rejected, except fewer than 4096 zero bytes immediately
before a signing block whose start is thereby aligned to the next 4096-byte
boundary. The block is found from its validated footer, never by skipping bytes.
Added v1 signature entries require a complete single-signer set; APK signing blocks accept only
known v2/v3/v3.1 and zero verity-padding IDs. Source stamps/key rotation are not
approved by this single-signer gate. ZIP alignment extras may contain only zeros
or the Android 0xd935 field with power-of-two alignment and zero padding.

Verifier output reference:
https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/apksigner/java/com/android/apksigner/ApkSignerTool.java
"""
from __future__ import annotations

from contextlib import ExitStack
from dataclasses import dataclass
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import struct
from typing import Callable, Mapping
import zipfile

DELIVERY_PARENT = "4062451c315265c827161f7e3ac925ff8df39121"
UNSIGNED_DELIVERY_PARENT = "893e8e06fc5f59914da194e65f72c105631a17cc"
UNSIGNED_SHA256 = "1d79341a02ea25673b2c406e8e2c901a9b6db60058bf326c314e2dbeaac267fd"
SOURCE_SHA256 = "a898bd4c6b9bfd182b4d14ac99b829416adea7b798d986c13b24c36f6a5ee798"
PACKAGE_FACTS = {
    "package_name": "com.hatsunama.captionaction.captureprobe",
    "version_code": 1,
    "version_name": "0.1-head28ac",
    "min_sdk": 29,
    "target_sdk": 36,
}
PACKAGE_PERMISSIONS = (
    "android.permission.RECORD_AUDIO",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
)
SIGNED_IDENTITY = {'schema_version': 1, 'delivery_parent': '4062451c315265c827161f7e3ac925ff8df39121', 'unsigned_sha256': '1d79341a02ea25673b2c406e8e2c901a9b6db60058bf326c314e2dbeaac267fd', 'source_sha256': 'a898bd4c6b9bfd182b4d14ac99b829416adea7b798d986c13b24c36f6a5ee798', 'signed_sha256': '583bf552e66564656e94d2f5c406b1d4235c82e72997aae634e90a301a077ccf', 'signed_size': 3757712, 'receipt_sha256': 'ed410e0e404826eefb57b9f3ceea41da02c31b11191c790f6cfe96a480d8d836', 'signer_cert_sha256': '2a0d37dc62eebed39bd08a9238c9255e26eb52a00143a02e0c6c473489985a21', 'build_tools_version': '36.0.0', 'apksigner_sha256': 'b47549e373b895ce6ca620d0c7887e674d9615ffa837a86ac601dcfd04adb0f0', 'apksigner_jar_sha256': '3716d9311e55d2b0918a2fd9d54ba9e406c5f6abeea700b287f11259bc163dec', 'package_name': 'com.hatsunama.captionaction.captureprobe', 'version_code': 1, 'version_name': '0.1-head28ac', 'min_sdk': 29, 'target_sdk': 36}
MAX_APK_BYTES = 64 * 1024 * 1024
MAX_SOURCE_BYTES = 8 * 1024 * 1024
MAX_RECEIPT_BYTES = 1024 * 1024
MAX_VERIFY_OUTPUT_BYTES = 64 * 1024
VERIFY_TIMEOUT_SECONDS = 30.0
MAX_ENTRY_BYTES = 32 * 1024 * 1024
MAX_PAYLOAD_BYTES = 64 * 1024 * 1024
_HEX = re.compile(r"[0-9a-f]{64}\Z")
_IDENTITY_KEYS = {
    "schema_version", "delivery_parent", "unsigned_sha256", "source_sha256",
    "signed_sha256", "signed_size", "receipt_sha256", "signer_cert_sha256",
    "build_tools_version", "apksigner_sha256", "apksigner_jar_sha256",
    *PACKAGE_FACTS,
}
_SIGNATURE_ENTRY = re.compile(r"META-INF/([A-Z0-9_-]{1,8})\.(SF|RSA|DSA|EC)\Z")
_SIGNING_IDS = {0x7109871A: "v2", 0xF05368C0: "v3", 0x1B93AD61: "v3.1"}
_VERITY_PADDING_ID = 0x42726577
_SIGNING_BLOCK_ALIGNMENT = 4096
_FROZEN_BUILDER_PADDING_SHA256 = "f5e6977a3963be2cc0fcaa4c4da826eee231acc1e6f45231f2715f9f4a1283f2"


class ArtifactGateError(RuntimeError):
    """An artifact, identity, receipt or verifier failed the fail-closed gate."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ArtifactGateError(message)


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def require_approved_identity(delivery_parent: str) -> dict:
    """Must be called before downloads, SDK changes, install or emulator work."""
    _require(delivery_parent == DELIVERY_PARENT, "delivery parent is not the exact approved commit")
    _require(type(SIGNED_IDENTITY) is dict, "no exact approved signed identity; runtime is disabled")
    identity = dict(SIGNED_IDENTITY)
    _require(set(identity) == _IDENTITY_KEYS, "approved signed identity schema is incomplete or unexpected")
    fixed = {"schema_version": 1, "delivery_parent": DELIVERY_PARENT,
             "unsigned_sha256": UNSIGNED_SHA256, "source_sha256": SOURCE_SHA256, **PACKAGE_FACTS}
    for key, value in fixed.items():
        _require(type(identity[key]) is type(value) and identity[key] == value,
                 "approved identity has wrong " + key)
    for key in ("unsigned_sha256", "source_sha256", "signed_sha256", "receipt_sha256",
                "signer_cert_sha256", "apksigner_sha256", "apksigner_jar_sha256"):
        _require(type(identity[key]) is str and bool(_HEX.fullmatch(identity[key])),
                 "approved identity has invalid " + key)
    _require(identity["signed_sha256"] != UNSIGNED_SHA256, "unsigned APK cannot be the signed identity")
    _require(type(identity["signed_size"]) is int and 0 < identity["signed_size"] <= MAX_APK_BYTES,
             "approved identity has invalid signed_size")
    _require(type(identity["build_tools_version"]) is str and
             bool(re.fullmatch(r"[1-9][0-9]*\.[0-9]+\.[0-9]+", identity["build_tools_version"])),
             "build-tools must be an exact reviewed stable release")
    return identity


def _absolute_parts(path: os.PathLike | str) -> tuple[str, list[str]]:
    value = os.fspath(path)
    _require(type(value) is str and value.startswith("/") and "\x00" not in value,
             "artifact paths must be absolute text paths")
    parts = value.split("/")[1:]
    _require(bool(parts) and all(part not in ("", ".", "..") for part in parts),
             "empty or traversal path component is forbidden")
    return value, parts


def _inode(st: os.stat_result) -> tuple:
    return st.st_dev, st.st_ino, st.st_mode


def _file_state(st: os.stat_result) -> tuple:
    return _inode(st) + (st.st_size, st.st_mtime_ns, st.st_ctime_ns, st.st_nlink)


class CheckedFile:
    """Retains a no-follow descriptor and ancestor chain, rejecting replacement."""

    def __init__(self, path: os.PathLike | str, max_bytes: int):
        self.path, parts = _absolute_parts(path)
        self._fds: list[int] = []
        self._links: list[tuple[int, str, tuple]] = []
        self.fd = -1
        self.max_bytes = max_bytes
        try:
            _require(hasattr(os, "O_NOFOLLOW") and hasattr(os, "O_DIRECTORY"),
                     "no-follow descriptor support is required")
            parent = os.open("/", os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
            self._fds.append(parent)
            for part in parts[:-1]:
                child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC,
                                dir_fd=parent)
                self._fds.append(child)
                self._links.append((parent, part, _inode(os.fstat(child))))
                parent = child
            self.fd = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC,
                              dir_fd=parent)
            self._fds.append(self.fd)
            st = os.fstat(self.fd)
            _require(stat.S_ISREG(st.st_mode), "artifact/tool is not a regular file")
            _require(0 < st.st_size <= max_bytes, "artifact/tool size is outside the allowed bound")
            self._initial = _file_state(st)
            self._links.append((parent, parts[-1], _inode(st)))
            self.recheck()
        except (OSError, ValueError) as exc:
            self.close()
            raise ArtifactGateError("no-follow path verification failed: " + str(exc)) from exc
        except BaseException:
            self.close()
            raise

    @property
    def stable_path(self) -> str:
        _require(self.fd >= 0, "verified file is closed")
        return f"/proc/{os.getpid()}/fd/{self.fd}"

    def recheck(self) -> None:
        _require(self.fd >= 0, "verified file is closed")
        try:
            _require(_file_state(os.fstat(self.fd)) == self._initial,
                     "file changed while it was being verified")
            for parent, name, expected in self._links:
                actual = os.stat(name, dir_fd=parent, follow_symlinks=False)
                _require(_inode(actual) == expected, "file or ancestor pathname was replaced")
        except OSError as exc:
            raise ArtifactGateError("file/ancestor recheck failed: " + str(exc)) from exc

    def read(self) -> bytes:
        self.recheck()
        chunks = []
        offset = 0
        while True:
            chunk = os.pread(self.fd, min(1024 * 1024, self.max_bytes + 1 - offset), offset)
            if not chunk:
                break
            chunks.append(chunk)
            offset += len(chunk)
            _require(offset <= self.max_bytes, "file exceeded its size bound")
        self.recheck()
        _require(offset == self._initial[3], "file size changed during read")
        return b"".join(chunks)

    def close(self) -> None:
        for fd in reversed(self._fds):
            os.close(fd)
        self._fds.clear()
        self.fd = -1

    def __enter__(self) -> "CheckedFile":
        return self

    def __exit__(self, *_args) -> None:
        self.close()


@dataclass(frozen=True)
class Entry:
    name: str
    size: int
    sha256: str
    compressed_sha256: str
    compression: int
    flags: int
    metadata: tuple

    def receipt_record(self) -> dict:
        return {"name": self.name, "bytes": self.size, "sha256": self.sha256}


@dataclass(frozen=True)
class ZipPayload:
    entries: tuple[Entry, ...]
    gaps: tuple[tuple[str | None, str | None, bytes], ...]
    signing_schemes: frozenset[str]
    signing_alignment_bytes: int


def _alignment_extra(extra: bytes) -> None:
    while extra:
        if not any(extra):
            return
        _require(len(extra) >= 6, "unknown ZIP extra metadata")
        field, length = struct.unpack_from("<HH", extra)
        _require(field == 0xD935 and 2 <= length <= len(extra) - 4,
                 "only Android alignment extra metadata is allowed")
        data, extra = extra[4:4 + length], extra[4 + length:]
        alignment = struct.unpack_from("<H", data)[0]
        _require(1 <= alignment <= 32768 and alignment & (alignment - 1) == 0 and not any(data[2:]),
                 "malformed Android alignment metadata")


def _signing_block(data: bytes) -> frozenset[str]:
    _require(len(data) >= 32 and data[-16:] == b"APK Sig Block 42", "unexpected hidden ZIP bytes")
    size = struct.unpack_from("<Q", data)[0]
    _require(size == len(data) - 8 and struct.unpack_from("<Q", data, len(data) - 24)[0] == size,
             "malformed APK signing block size")
    pos, stop, ids, schemes = 8, len(data) - 24, set(), set()
    while pos < stop:
        _require(stop - pos >= 12, "truncated APK signing pair")
        length = struct.unpack_from("<Q", data, pos)[0]
        _require(4 <= length <= stop - pos - 8, "invalid APK signing pair size")
        block_id = struct.unpack_from("<I", data, pos + 8)[0]
        _require(block_id not in ids, "duplicate APK signing block ID")
        ids.add(block_id)
        value = data[pos + 12:pos + 8 + length]
        if block_id == _VERITY_PADDING_ID:
            _require(not any(value), "nonzero APK verity padding")
        else:
            _require(block_id in _SIGNING_IDS and bool(value), "unapproved APK signing block ID")
            schemes.add(_SIGNING_IDS[block_id])
        pos += 8 + length
    _require(pos == stop and bool(schemes), "APK signing block lacks a supported signature")
    return frozenset(schemes)


def _signing_region(blob: bytes, cursor: int, cd_offset: int) -> tuple[frozenset[str], int]:
    """Validate the footer-delimited block and minimal, bounded zero alignment."""
    region_size = cd_offset - cursor
    _require(region_size >= 32 and blob[cd_offset - 16:cd_offset] == b"APK Sig Block 42",
             "unexpected hidden ZIP bytes before central directory")
    block_size = struct.unpack_from("<Q", blob, cd_offset - 24)[0] + 8
    _require(32 <= block_size <= region_size, "malformed APK signing block size")
    block_start = cd_offset - block_size
    padding_size = block_start - cursor
    if padding_size:
        _require(padding_size < _SIGNING_BLOCK_ALIGNMENT and
                 block_start % _SIGNING_BLOCK_ALIGNMENT == 0 and
                 not any(blob[cursor:block_start]),
                 "invalid APK signing-block alignment padding")
    return _signing_block(blob[block_start:cd_offset]), padding_size


def inspect_zip_payload(blob: bytes) -> ZipPayload:
    """Parse bounded ZIP bytes without extraction; reject ambiguous ZIP structure."""
    _require(type(blob) is bytes and 22 <= len(blob) <= MAX_APK_BYTES, "invalid APK byte bound")
    end = blob.rfind(b"PK\x05\x06", max(0, len(blob) - 65557))
    _require(end >= 0 and end + 22 <= len(blob), "missing ZIP end record")
    _, disk, cd_disk, disk_n, count, cd_size, cd_offset, comment_len = struct.unpack_from("<4s4H2IH", blob, end)
    _require(disk == cd_disk == 0 and 0 < count == disk_n <= 256 and
             comment_len == 0 and end + 22 == len(blob) and cd_offset + cd_size == end,
             "ZIP64, multidisk, comments, trailing data or malformed directory are forbidden")
    entries, spans = [], []
    try:
        with zipfile.ZipFile(io.BytesIO(blob)) as archive:
            infos = archive.infolist()
            _require(len(infos) == count and archive.start_dir == cd_offset,
                     "ambiguous ZIP central directory")
            names, total = set(), 0
            for info in infos:
                name = info.filename
                _require(name == info.orig_filename and name.isascii() and "\\" not in name and
                         not name.startswith("/") and all(p not in ("", ".", "..") for p in name.split("/")),
                         "unsafe or ambiguous ZIP name")
                _require(name.casefold() not in names, "duplicate or case-alias ZIP entry")
                names.add(name.casefold())
                _require(info.flag_bits & ~0x800 == 0 and info.compress_type in (0, 8),
                         "encrypted, descriptor or unsupported ZIP entry")
                mode = info.external_attr >> 16
                _require(stat.S_IFMT(mode) in (0, stat.S_IFREG), "nonregular ZIP entry")
                _require(not info.comment and 0 <= info.file_size <= MAX_ENTRY_BYTES,
                         "ZIP entry metadata or size is not allowed")
                total += info.file_size
                _require(total <= MAX_PAYLOAD_BYTES, "ZIP payload exceeds total byte bound")
                _alignment_extra(info.extra)
                off = info.header_offset
                _require(0 <= off and off + 30 <= cd_offset, "invalid local ZIP header offset")
                fields = struct.unpack_from("<4s5H3I2H", blob, off)
                sig, _version, flags, method, _time, _date, crc, compressed, size, name_n, extra_n = fields
                start = off + 30 + name_n + extra_n
                finish = start + info.compress_size
                _require(sig == b"PK\x03\x04" and flags == info.flag_bits and method == info.compress_type
                         and crc == info.CRC and size == info.file_size and compressed == info.compress_size
                         and finish <= cd_offset, "local/central ZIP header mismatch")
                encoding = "utf-8" if flags & 0x800 else "cp437"
                _require(blob[off + 30:off + 30 + name_n] == name.encode(encoding),
                         "local/central ZIP filename mismatch")
                _alignment_extra(blob[off + 30 + name_n:start])
                with archive.open(info) as payload:
                    data = payload.read(MAX_ENTRY_BYTES + 1)
                    _require(len(data) == info.file_size and not payload.read(1), "ZIP entry length mismatch")
                # Do not permit a signing step to rebuild/recompress an original entry.
                entries.append(Entry(name, len(data), _sha(data), _sha(blob[start:finish]), method, flags,
                                     (info.CRC, info.date_time, info.external_attr, info.internal_attr,
                                      info.create_system, info.create_version, info.extract_version)))
                spans.append((off, finish, name))
    except (zipfile.BadZipFile, ValueError, EOFError, RuntimeError, struct.error) as exc:
        if isinstance(exc, ArtifactGateError):
            raise
        raise ArtifactGateError("invalid APK ZIP: " + str(exc)) from exc
    spans.sort()
    cursor, previous, gaps, schemes, padding_size = 0, None, [], frozenset(), 0
    for start, finish, name in spans:
        _require(start >= cursor, "overlapping ZIP entries")
        if start > cursor:
            gaps.append((previous, name, blob[cursor:start]))
        cursor, previous = finish, name
    if cursor < cd_offset:
        schemes, padding_size = _signing_region(blob, cursor, cd_offset)
    _require(cursor <= cd_offset, "APK payload overlaps its central directory")
    return ZipPayload(tuple(entries), tuple(gaps), schemes, padding_size)


def verify_payload(unsigned: bytes, signed: bytes) -> tuple[list[dict], frozenset[str], bool]:
    """Prove original payload and non-alignment metadata are unchanged."""
    before, after = inspect_zip_payload(unsigned), inspect_zip_payload(signed)
    _require(not before.signing_schemes, "reference unsigned APK unexpectedly has a signing block")
    _require(len(before.gaps) <= 1 and all(
        previous == "classes2.dex" and following == "AndroidManifest.xml" and len(data) == 49972
        and _sha(data) == _FROZEN_BUILDER_PADDING_SHA256
        for previous, following, data in before.gaps), "reference unsigned APK has unapproved hidden data")
    old = {entry.name: entry for entry in before.entries}
    new = {entry.name: entry for entry in after.entries}
    _require(set(old) <= set(new), "signed APK lost an original payload entry")
    for name, original in old.items():
        _require(new[name] == original, "original ZIP payload/metadata changed: " + name)
    # A signer can preserve or eliminate inherited builder padding, but cannot
    # introduce arbitrary data or move padding to a different entry boundary.
    _require(all(gap in before.gaps for gap in after.gaps) and len(after.gaps) <= len(before.gaps),
             "signed APK introduced or relocated hidden ZIP bytes")
    added = set(new) - set(old)
    has_v1 = bool(added)
    if added:
        _require("META-INF/MANIFEST.MF" in added, "unapproved new ZIP entry")
        signature_names = added - {"META-INF/MANIFEST.MF"}
        matches = [_SIGNATURE_ENTRY.fullmatch(name) for name in signature_names]
        _require(len(matches) == 2 and all(matches), "unexpected added non-signature ZIP entry")
        stems = {match.group(1) for match in matches}
        extensions = {match.group(2) for match in matches}
        _require(len(stems) == 1 and "SF" in extensions and len(extensions & {"RSA", "DSA", "EC"}) == 1,
                 "v1 signature entries must be one complete signer pair")
        _require(all(new[name].size > 0 for name in added), "empty signature metadata")
    _require(bool(after.signing_schemes) or has_v1, "signed APK contains no signature structures")
    return [entry.receipt_record() for entry in before.entries], after.signing_schemes, has_v1


def parse_apksigner_verify_output(stdout: str, stderr: str = "", returncode: int = 0) -> dict:
    """Strict pure parser for successful, single-certificate verifier output."""
    _require(type(stdout) is str and type(stderr) is str and type(returncode) is int,
             "invalid verifier output types")
    _require(returncode == 0 and stderr == "", "apksigner failed or wrote diagnostics")
    _require(len(stdout.encode("utf-8")) <= MAX_VERIFY_OUTPUT_BYTES and
             all(ord(c) >= 32 or c in "\r\n\t" for c in stdout), "invalid/oversized verifier output")
    lines = stdout.splitlines()
    _require(lines.count("Verifies") == 1 and lines.count("Number of signers: 1") == 1,
             "apksigner did not verify exactly one signer")
    _require(not any(re.search(r"WARNING|ERROR|DOES NOT VERIFY|Source Stamp Signer|Signer \(", line)
                     for line in lines), "unreviewed verifier warning, failure, stamp or rotated signer")
    digest_lines = [line for line in lines if "certificate SHA-256 digest:" in line]
    _require(len(digest_lines) == 1, "missing or multiple signer certificate SHA-256 digests")
    match = re.fullmatch(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})", digest_lines[0])
    _require(match is not None, "invalid signer certificate SHA-256 line")
    signer = match.group(1).lower()
    schemes = {}
    labels = {"v1": "JAR signing", "v2": "APK Signature Scheme v2", "v3": "APK Signature Scheme v3",
              "v3.1": "APK Signature Scheme v3.1", "v4": "APK Signature Scheme v4"}
    certificate_fields = set()
    stamp_lines = 0
    field_patterns = {
        "certificate DN": r".+",
        "certificate SHA-256 digest": r"[0-9a-fA-F]{64}",
        "certificate SHA-1 digest": r"[0-9a-fA-F]{40}",
        "certificate MD5 digest": r"[0-9a-fA-F]{32}",
        "key algorithm": r"RSA|EC|DSA",
        "key size (bits)": r"[1-9][0-9]*|n/a",
        "public key SHA-256 digest": r"[0-9a-fA-F]{64}",
        "public key SHA-1 digest": r"[0-9a-fA-F]{40}",
        "public key MD5 digest": r"[0-9a-fA-F]{32}",
    }
    for line in lines:
        if line.startswith("Verified using"):
            m = re.fullmatch(r"Verified using (v[1-4](?:\.1)?) scheme \(([^)]+)\): (true|false)", line)
            _require(m is not None and m.group(1) in labels and labels[m.group(1)] == m.group(2)
                     and m.group(1) not in schemes, "malformed or duplicate signature-scheme result")
            schemes[m.group(1)] = m.group(3) == "true"
        elif line.startswith("Number of signers:"):
            _require(line == "Number of signers: 1", "multiple or invalid signers")
        elif line.startswith("Signer #"):
            _require(line.startswith("Signer #1 "), "unexpected signer identity")
            field, separator, value = line[len("Signer #1 "):].partition(": ")
            _require(separator == ": " and field in field_patterns and field not in certificate_fields
                     and bool(re.fullmatch(field_patterns[field], value)),
                     "malformed, unexpected or duplicate signer field")
            certificate_fields.add(field)
        elif line.startswith("Verified for SourceStamp:"):
            stamp_lines += 1
            _require(line == "Verified for SourceStamp: false" and stamp_lines == 1,
                     "source stamp requires separate review or is duplicated")
        else:
            _require(line == "Verifies", "unexpected apksigner output line")
    _require(set(schemes) == set(labels) and stamp_lines == 1,
             "incomplete apksigner signature-scheme or source-stamp results")
    _require(any(schemes[v] for v in ("v2", "v3")),
             "a verified APK v2 or v3 signature is required")
    _require(not schemes.get("v3.1", False) and not schemes.get("v4", False),
             "rotated or sidecar signature schemes require separate review")
    return {"signer_cert_sha256": signer, "schemes": schemes}


def _json_no_duplicates(pairs):
    value = {}
    for key, item in pairs:
        _require(key not in value, "duplicate JSON receipt key")
        value[key] = item
    return value


def verify_receipt(blob: bytes, identity: Mapping, payload_entries: list[dict]) -> dict:
    """Bind the actual public summary; live/source/payload checks remain separate."""
    _require(type(blob) is bytes and 0 < len(blob) <= MAX_RECEIPT_BYTES, "invalid receipt byte bound")
    _require(_sha(blob) == identity["receipt_sha256"], "receipt SHA256 differs from approved identity")
    try:
        receipt = json.loads(blob.decode("utf-8"), object_pairs_hook=_json_no_duplicates,
                             parse_constant=lambda _value: (_ for _ in ()).throw(ArtifactGateError("nonfinite JSON value")))
    except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as exc:
        raise ArtifactGateError("receipt is not strict UTF-8 JSON") from exc
    expected = {
        "unsigned_artifact_commit": UNSIGNED_DELIVERY_PARENT,
        "unsigned_sha256": identity["unsigned_sha256"],
        "source_archive_sha256_expected_from_review_receipt": identity["source_sha256"],
        "source_archive_independently_verified": False,
        "signed_sha256": identity["signed_sha256"],
        "signed_bytes": identity["signed_size"],
        "certificate_sha256": identity["signer_cert_sha256"],
        "signature_verified": True,
        "signature_scheme_v3": True,
        "unsigned_payload_entries": len(payload_entries),
        "all_unsigned_payload_entries_unchanged": True,
        "package": identity["package_name"],
        **{key: identity[key] for key in ("version_code", "version_name", "min_sdk", "target_sdk")},
        "permissions": list(PACKAGE_PERMISSIONS),
        "runtime_executed": False,
        "signing_key_created_or_exported": False,
    }
    _require(type(receipt) is dict and set(receipt) == set(expected), "receipt schema mismatch")
    for key, value in expected.items():
        _require(type(receipt[key]) is type(value) and receipt[key] == value, "receipt has wrong " + key)
    return receipt


@dataclass
class VerifiedArtifact:
    """Owns the verified APK descriptor; close it after installation/last use."""
    _file: CheckedFile
    identity: dict
    payload_entries: list[dict]
    verification: dict

    @property
    def apk_path(self) -> str:
        return self._file.stable_path

    def recheck(self) -> None:
        _require(_sha(self._file.read()) == self.identity["signed_sha256"], "verified APK changed before use")

    def close(self) -> None:
        self._file.close()

    def __enter__(self) -> "VerifiedArtifact":
        self.recheck()
        return self

    def __exit__(self, *_args) -> None:
        self.close()


def verify_artifacts(*, delivery_parent: str, signed_apk: os.PathLike | str,
                     unsigned_apk: os.PathLike | str, source_archive: os.PathLike | str,
                     receipt_path: os.PathLike | str, android_sdk_root: os.PathLike | str,
                     runner: Callable) -> VerifiedArtifact:
    """Gate all inputs; read-only apksigner is called only after every local check.

    No caller-supplied identity override exists. The returned descriptor must stay
    open through installation. Any exception is failure, never partial approval.
    The SDK is preinstalled and explicitly reviewed; no PATH-based fallback,
    automatic installation, tool discovery, credential or signing operation exists.
    """
    identity = require_approved_identity(delivery_parent)
    sdk, _ = _absolute_parts(android_sdk_root)
    tool_dir = Path(sdk) / "build-tools" / identity["build_tools_version"]
    with ExitStack() as stack:
        signed_file = stack.enter_context(CheckedFile(signed_apk, MAX_APK_BYTES))
        unsigned_file = stack.enter_context(CheckedFile(unsigned_apk, MAX_APK_BYTES))
        source_file = stack.enter_context(CheckedFile(source_archive, MAX_SOURCE_BYTES))
        receipt_file = stack.enter_context(CheckedFile(receipt_path, MAX_RECEIPT_BYTES))
        signed_bytes, unsigned_bytes = signed_file.read(), unsigned_file.read()
        _require(len(signed_bytes) == identity["signed_size"] and _sha(signed_bytes) == identity["signed_sha256"],
                 "signed APK bytes/size differ from approved identity")
        _require(_sha(unsigned_bytes) == UNSIGNED_SHA256, "frozen unsigned APK SHA256 mismatch")
        _require(_sha(source_file.read()) == SOURCE_SHA256, "frozen source archive SHA256 mismatch")
        records, block_schemes, has_v1 = verify_payload(unsigned_bytes, signed_bytes)
        receipt_verification = verify_receipt(receipt_file.read(), identity, records)
        launcher = stack.enter_context(CheckedFile(tool_dir / "apksigner", 1024 * 1024))
        jar = stack.enter_context(CheckedFile(tool_dir / "lib" / "apksigner.jar", 64 * 1024 * 1024))
        _require(_sha(launcher.read()) == identity["apksigner_sha256"] and
                 _sha(jar.read()) == identity["apksigner_jar_sha256"], "preinstalled official verifier hash mismatch")
        _require(os.fstat(launcher.fd).st_mode & 0o111 != 0, "apksigner launcher is not executable")
        command = [launcher.path, "verify", "--verbose", "--print-certs", "-Werr", signed_file.stable_path]
        try:
            result = runner(command, timeout=VERIFY_TIMEOUT_SECONDS, max_output_bytes=MAX_VERIFY_OUTPUT_BYTES)
        except Exception as exc:
            raise ArtifactGateError("bounded apksigner verification did not complete") from exc
        for retained in (signed_file, unsigned_file, source_file, receipt_file, launcher, jar):
            retained.recheck()
        # Hash again as well as checking inode/metadata, including the SDK tools.
        _require(_sha(signed_file.read()) == identity["signed_sha256"] and
                 _sha(launcher.read()) == identity["apksigner_sha256"] and
                 _sha(jar.read()) == identity["apksigner_jar_sha256"], "verification inputs changed")
        _require(all(hasattr(result, attr) for attr in ("stdout", "stderr", "returncode")),
                 "bounded runner returned an invalid verification result")
        verification = parse_apksigner_verify_output(result.stdout, result.stderr, result.returncode)
        _require(verification["signer_cert_sha256"] == identity["signer_cert_sha256"], "live APK signer mismatch")
        _require(verification["schemes"]["v3"] is receipt_verification["signature_scheme_v3"],
                 "live verifier disagrees with reviewed receipt v3 result")
        # The frozen manifest's minSDK29 makes v3 the relevant scheme. Default
        # apksigner can skip v1/v2 even when their structures are present; false
        # is not a failed cryptographic result for those unused older schemes.
        # Never infer any result from missing receipt fields or relax -Werr.
        _require(not verification["schemes"]["v1"] or has_v1, "v1 structures/verification disagree")
        verified_blocks = frozenset(v for v in ("v2", "v3", "v3.1") if verification["schemes"][v])
        _require("v3" in block_schemes and verified_blocks <= block_schemes and
                 block_schemes <= {"v2", "v3"}, "APK signature blocks/verification disagree")
        verification.update(
            required_scheme="v3", verified_for_min_sdk=identity["min_sdk"],
            legacy_schemes_not_relied_upon=sorted(
                (block_schemes | ({"v1"} if has_v1 else set())) & {"v1", "v2"}),
        )
        verified = VerifiedArtifact(signed_file, identity, records, verification)
        # Transfer only the signed APK descriptor; close all other retained files.
        stack.pop_all()
        for retained in (unsigned_file, source_file, receipt_file, launcher, jar):
            retained.close()
        return verified
