# Vendored AndroidLiquidGlass renderer

Source: https://github.com/unreliablecode/AndroidLiquidGlass

Commit: `65ab177e90e5c1d8c62e70cf7755841982da65f6`

The original Apache 2.0 license is preserved in `LICENSE`. Sources from
`backdrop/src/commonMain` and `backdrop/src/androidMain` are compiled as a regular
Android library. The platform `expect`/`actual` declarations are merged into
ordinary Android functions, without changing the original AGSL shaders,
refraction, blur, color filters, highlights or shadow rendering.

The surface export is clipped to the material's shape and recorded before child
content is drawn so a nested glass surface samples its parent's current-frame
surface without recording itself.
Application controls use this export combined with the ancestor backdrop. Each
export contains only the glass material, keeping the capture graph acyclic.

## Verifying nested rendering

`NestedGlassRenderingTest` is an Android hardware rendering test in the app's
`androidTest` source set. It changes a parent glass tint while leaving its scene
fixed and asserts that the nested material's pixels change. It then changes only
the scene and verifies multiple subsequent frame updates. Pixels outside the
parent must remain unchanged during the parent-only update.

Run on an Android 13+ device with:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.github.musicyou.ui.glass.NestedGlassRenderingTest"
```

The `GlassRenderTest` log tag reports the measured RGB deltas. Screenshot capture
is synchronized with Compose idle and hardware frame copying.
