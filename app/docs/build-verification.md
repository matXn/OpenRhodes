# Wordmark highlight 2.1.27-dev — 2026-10-27

- Added a diagonal soft white highlight that traverses the wordmark in 2.6s,
  reversing continuously. Its gradient uses SrcAtop on a local offscreen glyph
  layer; transparency is retained inside letter counters and around the image.
  The underlying glyphs are gently dimmed for visible contrast without a glow
  outside the wordmark. Underline drawing runs after the highlight and retains
  its independent entrance and refresh behavior.
- APK assembly, the five relevant root native-render/gesture/navigation tests
  and Android Lint passed. Pixel checks verify both highlight directions,
  transparent O counter, circular O, underline attachment and entry/refresh.
- Installed and launched code 52 / 2.1.27-dev in UU/MuMu Android 15. Two native
  screenshots 700ms apart confirm visible wordmark changes. Captures remain in
  ignored app/build/ui-review-2127; no physical phone was used.
- APK SHA-256: c79a9e64ec2ab76fbab735202742e88a014861752ebffeb549efeab87befcb98

# Proportional SVG wordmark 2.1.26-dev — 2026-10-27

- Removed the 0.78 horizontal transform from both SVG and Android vector.
  SVG viewBox and native viewport now use the natural 571x100 geometry; the
  Compose container scales both axes equally. Wide-screen maximum width was
  adjusted to retain the previous cap height; phone widths fit proportionally.
- Rebuilt O with circular contours. Native pixel assertions check equal O width
  and height at regular/narrow widths and larger system font scale. Connected
  underline, add-icon alignment, all three entrance sweeps, pull refresh and
  settings navigation still pass.
- APK assembly, all five relevant root visual/gesture/navigation tests and
  Android Lint passed. Other application logic is unchanged from 2.1.25-dev,
  whose complete 87-test suite passed below.
- Installed code 51 / 2.1.26-dev in UU/MuMu Android 15. No physical phone used.
- APK contains no font files and retains the existing development signing key.
- APK SHA-256: 927ee76f7ab1dee803c63208fcc1272927a3552455060b2e525f378e077ffa43

# Android source / release verification — 2026-10-27

Version 2.1.25-dev, code 50, application ID com.openrhodes.app.
The public project was packaged from the local independent client 2.1.24-dev.
The original local font-based project remains intact. This public version uses
original geometric SVG paths for the wordmark and a matching native vector;
no proprietary font software is included. The wordmark generator is committed.

- JDK 21 / Android SDK 36 build passed from this source project.
- All 87 unit, state-machine, Robolectric native-render and gesture tests passed.
- Android Lint passed. Root visual checks cover narrow width/font scale, native
  vector extent, connected p underline, add-icon alignment, bright/dark/bright
  entry directions, final hold, refresh gesture and control/settings navigation.
- Native image root-home.png reviewed; installed and launched on UU/MuMu Android 15.
- APK font-file audit passed: no TTF, OTF, WOFF or WOFF2 files.
- APK signature matches the previously published 2.1.6-dev development build.
- No physical phone or lamp was modified. Physical BLE provisioning and vendor
  launcher behavior are not established by emulator/JVM tests.
- Source upload excludes local.properties, build output, private signing keys,
  emulator data and local verification screenshots.

APK SHA-256: 5395a024761bcff8ab3081ecad9474562b164309ed86dd81f89b1e030d3a9cd9
