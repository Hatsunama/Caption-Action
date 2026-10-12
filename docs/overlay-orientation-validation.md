# Live overlay orientation bounds

Baseline `e94f377` contained `measuredHeight.coerceIn(minH, maxH)` with
`minH = 180 * density` and `maxH = screenHeight * 0.48`. Executing those production
expressions at density 1, screen height 320, measured height 240 throws
`IllegalArgumentException: maximum 153 is less than minimum 180`.

The orientation repair centralizes pixel bounds in `OverlayBounds`:
- Minimum size never exceeds available width or capped height, even at tiny sizes.
- Valid portrait geometry remains unchanged.
- Saved placement, drag/resize and service configuration changes are clamped so
  the stop/resize affordances do not remain beyond the current viewport.
- Auto-growth uses the same nonempty range and reclamps position after growth.
- A configuration event does not restart capture, inference or recording.

Six deterministic JVM tests passed: the original 320px crash case, original
portrait growth limits, unchanged valid portrait placement, portrait-to-landscape
saved bounds, drag/resize limits, and a matrix of tiny/multiwindow dimensions.
These test the actual shared production geometry helper; they do not substitute
for WindowManager/inset/gesture rendering on a device. Final both-phone QA must
include a long caption during rotation and drag/resize near each edge, plus stop.

Native inference lifetime and capture-generation findings remain outside this
orientation repair. No thread ownership or stop policy was changed.
