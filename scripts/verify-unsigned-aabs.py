#!/usr/bin/env python3
"""Verify optional release bundles are unsigned, without signing or accessing keys."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


def verify(path, jarsigner):
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise ValueError(f'{path}: corrupt ZIP member')
        names = archive.namelist()
        if not {'BundleConfig.pb', 'base/manifest/AndroidManifest.xml'} <= set(names):
            raise ValueError(f'{path}: missing required bundle entries')
        if any(re.fullmatch(r'META-INF/(?:MANIFEST\.MF|.*\.(?:RSA|DSA|EC|SF))', n, re.I) or
               n.lower().endswith('code_transparency_signed.jwt') for n in names):
            raise ValueError(f'{path}: JAR or code-transparency signature metadata present')
    result = subprocess.run([jarsigner, '-J-Duser.language=en', '-J-Duser.country=US', '-verify', str(path)],
                            env={**os.environ, 'LC_ALL': 'C'}, capture_output=True, text=True)
    output = result.stdout + result.stderr
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if result.returncode != 0 or lines not in (['jar is unsigned.'], ['no manifest.', 'jar is unsigned.']):
        raise ValueError(f'{path}: verifier did not establish unsigned bundle (exit {result.returncode}): {output}')
    return {'file': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'bytes': path.stat().st_size, 'unsigned': True, 'signatureEntries': [],
            'jarsignerExit': result.returncode, 'jarsignerOutput': output}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jarsigner', required=True)
    parser.add_argument('aabs', type=Path, nargs='+')
    args = parser.parse_args()
    print(json.dumps([verify(path, args.jarsigner) for path in args.aabs], indent=2))

if __name__ == '__main__': main()
