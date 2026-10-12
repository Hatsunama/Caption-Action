#!/usr/bin/env python3
"""Fail closed unless fresh merged/generated and packaged identities are isolated QA."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

NS = '{http://schemas.android.com/apk/res/android}'
BASE = 'com.hatsunama.captionaction'
QA = BASE + '.qa'
AUTHORITIES = {QA + '.mlkitinitprovider', QA + '.androidx-startup'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_manifest(root, test=False):
    expected = QA + ('.test' if test else '')
    require(root.tag == 'manifest' and root.get('package') == expected, 'Wrong QA package: ' + str(root.get('package')))
    require(root.get(NS + 'sharedUserId') is None, 'Shared UID is forbidden')
    providers = root.findall('.//provider')
    authorities = [p.get(NS + 'authorities') for p in providers]
    require(len(authorities) == len(set(authorities)), 'Duplicate provider authority')
    require(set(authorities) == (set() if test else AUTHORITIES), 'Unexpected provider authorities: ' + str(authorities))
    if test:
        runners = root.findall('instrumentation')
        require(len(runners) == 1 and runners[0].get(NS + 'targetPackage') == QA, 'Wrong instrumentation target')
        require(runners[0].get(NS + 'name') == 'androidx.test.runner.AndroidJUnitRunner', 'Wrong instrumentation runner')
    else:
        app = root.find('application')
        require(app is not None and app.get(NS + 'name') == BASE + '.CaptionActionApp', 'App class namespace changed')
        names = {e.get(NS + 'name') for e in app.findall('activity')}
        require(BASE + '.ui.home.HomeActivity' in names, 'Home class namespace changed')
        permissions = [e.get(NS + 'name') for e in root.findall('permission')]
        require(permissions == [QA + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'], 'Wrong declared app permission')
        uses = {e.get(NS + 'name') for e in root.findall('uses-permission')}
        require(QA + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION' in uses, 'Missing QA receiver permission')
        require(not any(p in uses for p in [BASE + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION', BASE + '.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION']), 'Protected package permission collision')
    return {'package': expected, 'authorities': sorted(authorities), 'targetPackage': QA if test else None}


def xmltree(text):
    """Read aapt's decoded XML tree, retaining hierarchy and quoted string attributes."""
    stack = []
    root = None
    for line in text.splitlines():
        match = re.match(r'(\s*)E: ([\w.-]+)(?:\s|$)', line)
        if match:
            indent = len(match[1])
            while stack and stack[-1][0] >= indent:
                stack.pop()
            node = ET.Element(match[2])
            if stack:
                stack[-1][1].append(node)
            else:
                require(root is None, 'Ambiguous APK manifest root')
                root = node
            stack.append((indent, node))
            continue
        require(not re.match(r'\s*A: android:sharedUserId(?:\([^)]*\))?=', line), 'Shared UID is forbidden in packaged manifest')
        match = re.match(r'\s*A: ([\w:.-]+)(?:\([^)]*\))?="([^"]*)"', line)
        if match:
            require(bool(stack), 'APK attribute without element')
            name = match[1]
            key = NS + name[8:] if name.startswith('android:') else name
            require(key not in stack[-1][1].attrib, 'Duplicate APK attribute')
            stack[-1][1].set(key, match[2])
    require(root is not None, 'Missing decoded APK manifest')
    return root


def validate_build_config(paths, test=False):
    require(len(paths) == 1, 'Absent or ambiguous BuildConfig: ' + str(paths))
    text = paths[0].read_text()
    ids = re.findall(r'\bAPPLICATION_ID\s*=\s*"([^"]+)"', text)
    namespaces = re.findall(r'^package\s+([\w.]+);', text, re.M)
    require(ids == [QA + ('.test' if test else '')], 'Wrong BuildConfig application ID')
    require(namespaces == [BASE + ('.test' if test else '')], 'BuildConfig namespace changed')


def verify(build, aapt):
    manifests = [build / 'intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml',
                 build / 'intermediates/packaged_manifests/debugAndroidTest/processDebugAndroidTestManifest/AndroidManifest.xml']
    apks = [build / 'outputs/apk/debug/app-debug-unsigned.apk',
            build / 'outputs/apk/androidTest/debug/app-debug-androidTest.apk']
    rows = []
    for index, (manifest, apk) in enumerate(zip(manifests, apks)):
        test = index == 1
        merged = validate_manifest(ET.parse(manifest).getroot(), test)
        source = build / ('generated/source/buildConfig/androidTest/debug' if test else 'generated/source/buildConfig/debug')
        validate_build_config(list(source.rglob('BuildConfig.java')), test)
        result = subprocess.run([aapt, 'dump', 'xmltree', str(apk), 'AndroidManifest.xml'], check=True, capture_output=True, text=True)
        packaged = validate_manifest(xmltree(result.stdout), test)
        require(merged == packaged, 'Merged and packaged identities differ')
        rows.append(dict(packaged, apk=str(apk), bytes=apk.stat().st_size, sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), merged_manifest=str(manifest)))
    return rows


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-dir', type=Path, required=True)
    parser.add_argument('--aapt', required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.build_dir, args.aapt), indent=2))
