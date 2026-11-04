# GovMap 1.7

Personal Android WebView wrapper; Android 8.0+. Launcher name: **GovMap**.
Package: `il.david.govmap.dark`. Updates versions 1.3–1.6 with the same saved signing key.

The toolbar button cycles Light → Dark → OLED → Light directly and persists the selection. Light mode leaves the site's appearance unchanged. Dark mode uses navy surfaces and high-contrast light text. OLED uses pure-black native controls and site surfaces, with a raster ground-tone mask on the default street map. Alternate URL basemaps retain original canvas colors. The OLED mask is a color heuristic, not semantic building recognition: some same-colored features can be affected, and some ground tones may remain nonblack.

Version 1.7 corrects literal dark panel titles, layer metadata, sharing labels, tool cards, MUI form labels and location-control SVG contrast. It handles WebView renderer loss without terminating the Activity: a readable message appears and Home/Refresh recreate the map. These buttons remain available in all three modes.

Statistical areas (`lay=23`) load by default. HTTPS GovMap navigation restrictions, popup/download blocking and foreground-only 25-second location-provider warm-up remain. External sharing, account popups, file downloads and printing are restricted by the wrapper; their presence on the website does not mean all corresponding actions are available in this app.

## Build

Requires Java 17, Android SDK platform/build-tools 35, ECJ 3.38.0 and zip.
From the project parent:

```
GOVMAP_SDK=/absolute/path/to/android-sdk GOVMAP_ECJ=/absolute/path/to/ecj.jar ./govmap/build.sh
node govmap/tests/theme.test.cjs
```

Output: `GovMap-Dark.apk`.
The signing folder is a private backup. Keep it private and retain it for future updates. The original version 1.2 signing key is unavailable; this package installs separately from `il.david.govmap`.

## Validation

Compilation, DEX generation, APK v2/v3 signature checks and theme-state/contrast tests pass. Actual Android 29 runtime checks verify the native mode cycle, native colors and renderer-crash recovery. The website did not render successfully in this emulator. Its original WebView 74 was updated from an official Android system image to WebView 124; the latter's renderer crashes with SIGTRAP, and emulator network diagnostics show DNS failures. No successful full map/menu/GPS runtime audit is claimed.

Live public-site checks in a separate desktop browser cover original light UI, statistical-layer toggles and details, seven basemap categories, zoom, sharing/embed dialog, tools menu and coordinate tool, main/about menu and create menu. They do not validate the injected dark/OLED appearance. See `tests/QA-report.md` for exact coverage and remaining checks.
