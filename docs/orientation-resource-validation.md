# Orientation resource validation

Baseline: `e94f377`. Checked 2026-10-12. This is resource-structure evidence,
not an Android rendering, accessibility, or physical-device pass.

## Changes

- Home's existing ScrollView now has `homeScroll`, allowing Android's normal
  view-state restoration to identify it after recreation.
- `layout-land/activity_mic_translator.xml` places the scrolling transcript next
  to a 240dp control pane. The controls also scroll when the available height is
  short. Back remains above both panes; the transcript no longer reserves the
  portrait layout's fixed 228dp bottom clearance.
- The model gate is wrapped in a ScrollView with natural-height content.
- The permission page is scrollable. Its card and content use natural height
  instead of collapsing a weighted card into the remaining landscape height.

All existing binding IDs and view types were compared with the baseline:
Home 14/14, permission page 7/7, model gate 11/11, and Mic 9/9 preserved.
Landscape Mic has the same nine IDs and types as portrait Mic. Added IDs are
`homeScroll`, `permissionScroll`, and `modelGateScroll`.

Portrait Mic is byte-for-byte unchanged from the baseline. Its SHA-256 is
`4b9798b381112f18b6ead9420c157a77d007aacf0dee390fa3ad9f090bfeb749`.
This resource change does not modify Kotlin, model/download logic, ASR/MT,
recording stop-on-pause, or native teardown.

## Reproducible structural regression

The script reads and parses actual Android XML; it does not inspect screenshots
or simulate Android's layout measurement.

```sh
python3 scripts/check-orientation-resources.py --revision e94f377
# Ran 7 tests; FAILED (failures=6); exit 1

python3 scripts/check-orientation-resources.py
# Ran 7 tests; OK; exit 0

git diff --check
# No whitespace errors
```

Baseline failures establish the missing Home scroll ID, missing landscape Mic
resource (three checks), unscrollable model gate, and unscrollable permission
page. XML parsing already passed before changes. After changes, all seven checks
pass: XML parsing, stable Home scroll/actions, Mic ID/type parity, independent
landscape scrolling panes, model-gate scrolling, permission scrolling without
zero-height weighted content, and retained system-window inset attributes.

## Remaining runtime checks

No Gradle build, Android measurement, emulator, or physical-device run was
performed for this resource-only check. Before claiming both orientations work:

1. On both target phones, rotate Home at a nonzero scroll position with nondefault
   languages/font settings; confirm restoration and that every button works.
2. Check Mic transcript width, language-wheel labels, Back, and Mic controls at
   short landscape heights, enlarged font, and both navigation modes. Confirm
   each pane scrolls and portrait appearance is unchanged.
3. In landscape, reach all model-gate and permission-page controls, including
   download progress, optional notification action, Cancel, and Back.
4. Check side cutouts and system bars on API 35/36. The roots retain
   `fitsSystemWindows`; the presence of that attribute alone proves no rendering
   outcome.
5. Verify rotation cancels the model gate safely with resumable download state,
   and that Mic rotation stops recording without automatically restarting it.

The baseline Mic screen also loses its transcript on Activity recreation; these
resource changes do not alter that behavior. The audit separately found a
preexisting live-overlay height-bound inversion below 375dp and missing overlay
rotation clamping. Those require their own verification and are not repaired by
these XML changes.
