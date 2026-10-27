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
