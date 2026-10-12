# Canonical Hatsu background integration

Source: owner repository `Hatsunama/Hatsu-Animation`, commit
`0f4bb7512a074eabd704481fb06725e1b84ac928`.

The two files under `app/src/main/java/com/hatsunama/refract/` are byte-for-byte
copies of upstream `android/src/main/java/com/hatsunama/refract/`:
- RefractBackgroundView.kt SHA-256 `3ae35fd6e40bb419ef4d5a6cddc8baea7ac498e3d91f61b3b19ccfe502c9452b`
- RefractSeed.kt SHA-256 `4790b0f5976406755f9f2b72c5a390aad5746441f95591074382f28710e8f66d`

No license/NOTICE file exists in that pinned owner repository; no license is
invented here. Reuse was explicitly requested by the owner. Existing Caption
renderer/seed code was token-identical after removing comments/package/whitespace.
Thus geometry, palette, simulation, seed keys and per-install identity are retained.

The app-package `RefractBackgroundView` is now a thin host. It preserves owner
pause intent across visibility notifications and freezes the canonical renderer
when system animations are disabled, while retaining a visible still frame.
Inactive canonical content remains INVISIBLE to prevent its own layout/visibility
callbacks from rearming motion; the host explicitly draws its frozen frame.
Post-layout reconciliation keeps immediate scheduling consistent. Two added
actual-View order regressions failed before this correction (disabled/paused
before first size), without relying on eventual pre-draw cleanup.
Attach/detach manages callbacks and the animator-setting observer. Upstream files
are not patched. The old seed API delegates to the canonical implementation.

A Robolectric test against the actual original View reproduced ignored disabled
animations before this change. Other lifecycle tests are controls, not invented
before-fail claims. Robolectric uses Android API28 on JDK17; it is not API36/device
rendering proof. The headless API28 window boundary is explicitly made visible
in fixtures; host/renderer policy state is not changed by fixtures. The test-only dependency does not ship in the release package.

The existing “Hatsu Is Here For You” button and local ThankYouActivity/Back action
are preserved as the verified Caption brand pattern. The requested Cue Cam
reference could not be found in the verified remote/local source; no claim of
Cue parity is made and no replacement URL or action was invented.
Final device checks must cover visible animation, reduced motion, pause/resume,
rotation, input/caption settings and both portrait/landscape layouts.
