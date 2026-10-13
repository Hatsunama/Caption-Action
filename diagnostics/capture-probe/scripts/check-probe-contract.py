#!/usr/bin/env python3
"""Structural review gates, not runtime/device proof."""
from pathlib import Path
s=(Path(__file__).resolve().parents[1]/'app/src/main/java/com/hatsunama/captionaction/captureprobe/ProbeService.kt').read_text()
assert '.addMatchingUid(Process.myUid())' in s, 'Capture must be isolated to the probe UID'
assert '.addMatchingUsage(AudioAttributes.USAGE_MEDIA)' in s, 'Capture must use MEDIA only'
assert 'AudioRecord.READ_NON_BLOCKING' in s, 'Probe reads must be nonblocking'
assert 'pcm.fill(0)' in s, 'PCM buffer must be wiped'
assert 'synthetic.fill(0)' in s, 'Synthetic buffer must be wiped'
assert 'addOnRoutingChangedListener' in s, 'Route changes must be watched'
assert 'Process.killProcess(Process.myPid())' in s, 'Independent hard deadline must terminate only this process'
assert 'setStreamVolume' not in s and 'adjustStreamVolume' not in s, 'Never alter system volume'
assert 'AudioSource.MIC' not in s and 'startMicrophoneCapture' not in s, 'No microphone input'
print('9 probe structural safety gates passed; not device proof')
