#!/bin/bash
# Compatibility entry point. Historical extraction implementation is preserved in
# historical-harness.tar.gz; N5 coverage compiles actual sources and real HTTP/cache logic.
set -uo pipefail
D=$(cd "$(dirname "$0")" && pwd)
bash "$D/../download-integrity/run.sh" before
STATUS=$?
if [[ $STATUS != 1 ]]; then
    echo "Expected frozen pre-integrity regression failures; got exit $STATUS" >&2
    exit 1
fi
exec bash "$D/../download-integrity/run.sh" after
