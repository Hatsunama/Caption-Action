#!/usr/bin/env python3
"""Inspect APK/AAB/AAR size and ELF alignment without SDK tools or signing keys.

This is a static report, not a substitute for zipalign/bundletool or device tests.
Run: python3 scripts/audit-package.py path/to/package.apk [more archives ...]
"""
import argparse
import json
from pathlib import Path
import struct
import zipfile


def elf_load_alignments(data):
    if data[:4] != b'\x7fELF' or data[4] not in (1, 2) or data[5] not in (1, 2):
        raise ValueError('Not a supported ELF file')
    endian = '<' if data[5] == 1 else '>'
    is_64 = data[4] == 2
    phoff = struct.unpack_from(endian + ('Q' if is_64 else 'I'), data, 32 if is_64 else 28)[0]
    size, count = struct.unpack_from(endian + 'HH', data, 54 if is_64 else 42)
    alignments = []
    relro_ends = []
    for index in range(count):
        offset = phoff + index * size
        kind = struct.unpack_from(endian + 'I', data, offset)[0]
        if kind == 1:  # PT_LOAD
            alignment = struct.unpack_from(endian + ('Q' if is_64 else 'I'), data, offset + (48 if is_64 else 28))[0]
            alignments.append(alignment)
        if kind == 0x6474e552:  # PT_GNU_RELRO
            address = struct.unpack_from(endian + ('Q' if is_64 else 'I'), data, offset + (16 if is_64 else 8))[0]
            memory_size = struct.unpack_from(endian + ('Q' if is_64 else 'I'), data, offset + (40 if is_64 else 20))[0]
            relro_ends.append(address + memory_size)
    if not alignments:
        raise ValueError('ELF has no PT_LOAD segments')
    return alignments, relro_ends


def audit(path):
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        native = []
        for entry in entries:
            if not entry.filename.endswith('.so'):
                continue
            alignments, relro_ends = elf_load_alignments(archive.read(entry))
            native.append({
                'path': entry.filename,
                'bytes': entry.file_size,
                'compressed_bytes': entry.compress_size,
                'load_alignments': alignments,
                'load_alignment_at_least_16k': all(a >= 16384 for a in alignments),
                'relro_end_remainders_16k': [end % 16384 for end in relro_ends],
                'relro_ends_aligned_16k': all(end % 16384 == 0 for end in relro_ends),
            })
        return {
            'archive': str(path), 'archive_bytes': path.stat().st_size,
            'uncompressed_entry_bytes': sum(e.file_size for e in entries),
            'native_uncompressed_bytes': sum(e['bytes'] for e in native),
            'native': native,
            'largest_entries': [{'path': e.filename, 'bytes': e.file_size,
                                 'compressed_bytes': e.compress_size}
                                for e in sorted(entries, key=lambda e: e.file_size, reverse=True)[:15]],
            'warning': 'Static ELF alignment only; verify ZIP alignment and runtime behavior separately.',
        }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archives', type=Path, nargs='+')
    args = parser.parse_args()
    print(json.dumps([audit(path) for path in args.archives], indent=2))
