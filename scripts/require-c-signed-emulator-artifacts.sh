#!/usr/bin/env bash
# Public builds deliberately have no signing keys. Do not generate a debug key,
# invoke connected/install tasks, or install these unsigned artifacts as a fallback.
printf '%s\n' 'Emulator checkpoint blocked: public app and instrumentation APKs are unsigned. A separately reviewed C-signed APK pair and explicit install/test flow are required; no signing keys are generated here.' >&2
exit 4
