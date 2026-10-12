#!/usr/bin/env python3
"""Fail closed unless APK structure and apksigner both establish no signature."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import zipfile


def verify(path, apksigner):
    data = path.read_bytes()
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise ValueError(f'{path}: corrupt ZIP member')
        names = archive.namelist()
        if 'AndroidManifest.xml' not in names:
            raise ValueError(f'{path}: missing Android manifest')
        if any(re.fullmatch(r'META-INF/(?:MANIFEST\.MF|.*\.(?:RSA|DSA|EC|SF))', n, re.I) for n in names):
            raise ValueError(f'{path}: v1 signature metadata present')
    # Validate the complete EOCD, including comment length. Do not mistake comment
    # bytes for the central directory, or treat ZIP64/multidisk ambiguity as unsigned.
    ends = [i for i in range(max(0, len(data)-65557), len(data)-21)
            if data[i:i+4] == b'PK\x05\x06' and i+22+struct.unpack_from('<H', data, i+20)[0] == len(data)]
    if len(ends) != 1:
        raise ValueError(f'{path}: ambiguous ZIP end record')
    end = ends[0]
    _, disk, cd_disk, count_disk, count, size, offset, _ = struct.unpack_from('<4s4H2IH', data, end)
    if disk or cd_disk or count_disk != count or count == 65535 or offset == 0xffffffff or size == 0xffffffff or offset+size != end:
        raise ValueError(f'{path}: unsupported or malformed central directory')
    if data[offset:offset+4] != b'PK\x01\x02':
        raise ValueError(f'{path}: missing central directory')
    if data[max(0, offset-16):offset] == b'APK Sig Block 42':
        raise ValueError(f'{path}: APK signing block present')
    if Path(str(path)+'.idsig').exists():
        raise ValueError(f'{path}: v4 signature sidecar present')
    result = subprocess.run([apksigner, 'verify', '--verbose', '--print-certs', str(path)], capture_output=True, text=True)
    output = result.stdout + result.stderr
    if result.returncode != 1 or 'Missing META-INF/MANIFEST.MF' not in output or re.search(r'Signer #\d+ certificate', output):
        raise ValueError(f'{path}: verifier did not establish unsigned APK (exit {result.returncode}): {output}')
    return {'file': str(path), 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data), 'unsigned': True,
            'apkSigningBlockPresent': False, 'v1SignatureEntries': [], 'apksignerExit': result.returncode, 'apksignerOutput': output}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apksigner', required=True)
    parser.add_argument('apks', type=Path, nargs='+')
    args = parser.parse_args()
    print(json.dumps([verify(path, args.apksigner) for path in args.apks], indent=2))

if __name__ == '__main__': main()
