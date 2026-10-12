#!/usr/bin/env python3
"""Strip Android runner only; reuse all assertions against the entire production recorder."""
from pathlib import Path
import subprocess

here = Path(__file__).resolve().parent
root = here.parents[2]
out = here / "generated"
out.mkdir(exist_ok=True)
source = root / "app/src/main/java/com/hatsunama/captionaction/data/SubtitleFileRecorder.kt"
test = root / "app/src/test/java/com/hatsunama/captionaction/data/SubtitleFileRecorderSessionTest.kt"
s = test.read_text()
for line in ["import android.app.Application\n", "import org.junit.runner.RunWith\n", "import org.robolectric.RobolectricTestRunner\n", "import org.robolectric.annotation.Config\n", "@RunWith(RobolectricTestRunner::class)\n", "@Config(sdk = [28], application = Application::class)\n"]:
    assert s.count(line) == 1, line
    s = s.replace(line, "")
# Kotlin fixture supplies a property; the Android framework supplies a Java getter.
s = s.replace('override fun getFilesDir() =', 'override val filesDir get() =')
(out / test.name).write_text(s)
(out / "after.kt").write_bytes(source.read_bytes())
baseline = subprocess.check_output(["git", "show", "6ecc3377d10b5ce32aa41c1fa7ca4735bd513cb5:" + str(source.relative_to(root))], cwd=root).decode()
# Clock-only test seam; the original filename/open/write/close behavior remains exact.
baseline = baseline.replace('class SubtitleFileRecorder(private val context: Context) {', '''class SubtitleFileRecorder internal constructor(
    private val context: Context,
    private val now: () -> Date
) {
    constructor(context: Context) : this(context, { Date() })''')
baseline = baseline.replace('.format(Date())', '.format(now())')
(out / "before.kt").write_text(baseline)
