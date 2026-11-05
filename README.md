# GovMap 1.10.04

## Developer menu

Long-press **GovMap** for **כלי מפתח**: app updates, **דוח מהירות** (speed report), and **שמור לוג** (save app log). The menu also includes the seven additional tools below. The report reads the current WebView performance buffer on demand and adds a bounded native timing/cache snapshot. It lists slow/large public assets, native compressed network-body bytes versus decoded bytes, permanent-store reuse and file totals, plus in-progress downloads whose final byte counts are not yet available. Browser zero transfer sizes remain explicitly unknown unless the engine reports cache delivery; native/browser totals overlap and are not added together.

Additional developer tools:

- **Location status:** current precise/approximate permission, Android Location/provider state, latest readable cached-fix age and horizontal accuracy. Reads existing fixes only, without requesting a new fix. Coordinate values are never displayed, copied or logged.
- **Cache overview:** bounded metadata-only inspection of permanent asset bodies, metadata/in-flight files, public metadata, staged updates and the Android app cache directory. Shows native reuse/download totals and labels incomplete scans. Does not clear files; browser-only HTTP-cache totals are not guessed.
- **Startup/reload test:** reloads the current trusted GovMap page, records callback times and native transfer/cache deltas, then opens the speed report. A 45-second timeout produces partial results; the report explicitly distinguishes callbacks from full map-tile readiness.
- **Connection check:** explicit bounded HTTPS probes of GovMap's root and GitHub's update metadata. Redirect hosts/TLS are validated, responses are limited, and HTTP refusals are distinguished from network failure. No APK download or installation is triggered.
- **Copy debug info:** copies app/Android/WebView versions, updater details and recent redacted error events, only on explicit action.
- **Fresh log session:** clears only the in-memory event journal and restarts its relative log clock. Process transfer counters, caches and previously saved documents remain intact.
- **Detailed update status:** installed/latest observed versions, attempt/success times, permission, process phase, staged APK, byte progress, session progress and Android failures. Refresh reads metadata only, without downloading or installing. Unknown/not-recorded values remain explicit.


App events stay in a bounded, process-local 300-entry journal; diagnostic files are written only on explicit export. The updater retains small status/metadata timestamps in its existing preferences. Console message bodies, non-public-resource URLs, URL queries, request headers, search terms and coordinates are not captured. Reports include current updater status and its last installer error with URI/credential redaction. **Save log** (or the report's save button) opens Android's document picker for a plain-text report plus app-event log. No storage permission or automatic upload is added. These are app diagnostics, not system logcat. Saving to a cloud document provider is possible only if the user chooses it in the system picker.

Menu/report dialogs follow light/dark styling; the report body is black in OLED mode. Renderer/report collection has a timeout; export request IDs are kept separate from installer IDs and the pending temporary document survives Activity/process recreation. Temporary exports are private, bounded and cleaned after completion/cancellation. The native asset-store layout and streaming behavior are unchanged. New counters record metadata at cache/transfer boundaries, without per-byte diagnostics or background polling.

Android menu/file-picker interaction still needs phone validation; host tests cover report sanitization, timing/cache classification, actual gzip download versus permanent reuse, bounded concurrent logs, and existing app behavior.


Android 8.0+. Launcher name **GovMap**, package `il.david.govmap.dark`, version code `17`. Same saved signing key. Install as an update without uninstalling or clearing app data. Existing persistent assets from 1.8/1.8.1 remain compatible.

## Opening shared GovMap links

On Android 12 or later, GovMap offers setup once on a normal launch if link approval is missing. Tap **פתיחת הגדרות** (Open settings) to jump to **Open by default**. Turn on **Open supported links** and select both GovMap addresses if prompted. If you skip the prompt, long-press **GovMap** and choose **פתיחת קישורי GovMap** (Open GovMap links) to return to that screen. On phones that do not provide the direct shortcut, the button opens GovMap's app info instead.

After approving the addresses, tap a GovMap HTTPS link in another app to open its shared map view. The app passes the original coordinates, zoom, layers, and basemap parameters to GovMap.

## GitHub automatic updates

After a one-time opt-in, the native app checks public releases from `davidreuveni/GovMap`. Launch checks run off the UI thread after five seconds, throttled to once per 15 minutes. A network-connected, battery-not-low job checks roughly every six hours, subject to Android scheduling and device restrictions. It survives reboot; this is not instant push delivery. A check/transfer is bounded to two minutes, 16 KiB of metadata and 32 MiB per APK. Only complete, size/hash-checked APKs with the original certificate, correct package and increasing version code are staged. No GitHub token or signing key is included in the APK.

With automatic updates enabled, GovMap attempts installation after download or when you open the app with a verified update already waiting. You can keep the map open during the attempt. Android may close GovMap to replace it. Android 12+ can permit self-updates without repeated confirmation when its platform requirements are satisfied. If Android needs approval, GovMap offers it while the map is open and in focus. Users must permit GovMap to install unknown apps. A failed install delays automatic retries for that version; a newer release can still install. Long-press the GovMap title, then choose app updates to check/install manually or turn updates off. The first setup prompt can be declined. Disabling updates cancels scheduled work and removes pending updater files/sessions.

Version 1.10.04 retains the installer repair from 1.10.02 and adds developer diagnostics. Existing updater-enabled installations can receive this release through GitHub.

## Installer recovery in 1.10.02

A failed security-scan/installer handoff can leave an old install session waiting. Manual **בדוק / התקן** now starts a fresh ordinary Android APK-installer flow using a temporary read-only content URI for the verified APK. Automatic installation still uses PackageInstaller. Its original approval Intent is retained in memory without URI serialization; after process death a manual fallback starts a fresh flow. Device security checks remain in control.

Installer failure code/message is retained in the title's update controls. Cancellations no longer silently reuse the same failed session. While the ordinary installer is active, checks do not replace the APK being read. The provider is not exported, accepts one exact read-only URI, rejects path traversal, write access and symlink escapes, and exposes no signing or other app files. Confirmed OEM scan behavior still requires a phone test.

## Publish an update

The public release repository is [davidreuveni/GovMap](https://github.com/davidreuveni/GovMap). The updater reads its latest stable release. Every release has `GovMap-Dark.apk` and `update.json`.

- **Publish an already-signed build:** replace `release-input/GovMap-Dark.apk` on `main` and commit its matching updated source manifest. **Publish signed APK** verifies the original certificate, package and version, rejects tampering, and publishes both release assets. This route needs no signing secrets on GitHub.
- **Build on GitHub:** configure the two private Actions signing secrets and run **Build and publish (signing secrets required)** with a new version name and higher version code.

Install the updater-enabled APK once over existing GovMap, enable updates and allow Android's installation permission. The app's name and original signing certificate stay the same. Host checks pass; Android installation/background jobs still need testing on a phone. Never commit `signing/` or upload your private `GovMap-Dark-source.zip` backup. See [UPDATE-GITHUB.md](UPDATE-GITHUB.md).

## Location starts before the button is pressed

Android providers begin warming on foreground resume when location permission is already granted. As soon as the GovMap document is visible, a single high-accuracy WebView location watch starts, warming the browser's own provider and normal in-memory position cache. This addresses the gap in the previous native-only warm-up: the website no longer has to be the first browser geolocation consumer when the user presses its location button.

The preloader does not recenter the map, override getCurrentPosition/watchPosition/clearWatch, fabricate coordinates or retain coordinates in application data. GovMap's normal button still controls centering, freshness, accuracy and location errors. Existing native warm-up remains limited to 25 seconds; the browser watch stays ready while the app is visible and is cleared on hiding, pausing, page exit or destruction. It clears only its own watch, preserving website-owned watches. Resume restarts one watch rather than accumulating duplicates.

The initial automatic attempt uses Android's normal permission prompt if needed. A previous denial is not repeatedly prompted by automatic page callbacks; the website button can still request permission manually. Approximate-only grants are respected. Permission-result callbacks are resolved once, and preloading can recover after a permission dialog hides/cancels the original browser request. The location indicator may remain active while the app is open; keeping a foreground location provider ready trades some battery use for lower button latency. No background-location permission or foreground service is added.

Enable device Location and allow location while using the app. Precise location gives the best map positioning if the user chooses to grant it. A first fix can still be slow indoors or with weak signals. Sensor behavior and phone button latency have not been measured or physically verified here.

## Startup changes retained from 1.9

1. **Keep the map during supported configuration changes.** Rotation, screen/window size, screen layout and keyboard changes are handled by the existing Activity. The WebView is resized/redrawn rather than recreated. Android can still destroy the process or recreate the Activity for other reasons; this is not a background keep-alive service.
2. **Stream newly downloaded assets.** After response headers are validated, a bounded background producer saves the body while WebView reads the bytes already available. Multiple requests for the same asset get independent readers from one producer. Only a complete, length-checked, hashed response is installed atomically into permanent storage. Interrupted transfers are not reused. A failure to commit the cache after a valid complete transfer does not discard the valid network response. Network/read failures propagate as ordinary resource-load failures, and a later load retries. First response bytes no longer wait for the entire file to finish downloading.
3. **Conditional short cache for public layer metadata.** Only `/api/layers-catalog/catalog?lang=he|en|ar|ru` on GovMap qualifies. Requests carrying authorization, cookies, ranges or no-store bypass this cache. Anonymous, successful JSON responses may be reused briefly: server max-age/Expires and Age are honored, capped at 60 seconds; without explicit freshness, the heuristic is 30 seconds. Private/no-store/no-cache/cookie-setting/Vary-sensitive responses are not retained. Expired entries are not served stale. A refresh is scheduled in the background while an entry is still fresh, using ETag validation where available. Cache misses immediately fall through to the website's normal network request; anonymous background fills cannot delay that request. Access-denied responses back off instead of repeatedly retrying in the same process. Browser-delivered cached metadata uses no-store so it cannot acquire an independent, longer HTTP-cache lifetime.
4. **Avoid duplicate theme work.** CSS is installed when the document becomes visible, with a per-document/mode guard. The load-complete callback uses a small probe instead of resending the stylesheet. If the website removed the style/filter nodes, the theme is repaired. JavaScript also avoids rewriting identical CSS or unchanged theme attributes. Light/dark/OLED cycling and alternate-basemap colors are retained.

The metadata optimization is conditional. Anonymous catalog requests in this host environment were denied (HTTP 400/403, `access denied`), so live metadata reuse was not claimed. Those responses are never saved; the ordinary website request remains in control. This build does not bypass API authorization or change private/user endpoints.

## Storage and behavior

Public versioned JS/CSS/fonts/images under GovMap `/assets/` continue to use `no_backup/persistent-assets` in application data, outside Android cache directories. Directory layout, URL-derived keys and required metadata remain compatible. App closure and ordinary cache clearing leave completed files intact; clearing app data or uninstalling removes them. New versioned URLs fetch new files. The limits remain 128 MiB/256 completed files and 32 MiB per asset. Gzip is decoded while streaming; unknown decoded lengths are not sent as compressed Content-Length values. In-flight files are protected from eviction.

The optional anonymous catalog cache uses a separate `no_backup/public-layer-metadata` directory and bounded language variants, with hashes checked when loading its saved JSON. Other API calls and map tiles continue through the site's original loading behavior.

The former broad measurement profiler, debugging sockets and ZIP exporters remain removed; the developer menu above adds bounded, on-demand reports and explicit text-log export. Location warm-up, statistical-area layer default, foreground location handling and GovMap navigation restrictions remain. `README-production.md` is historical documentation for 1.7.

## Build and tests

Requires Java 17, Android SDK platform/build-tools 35, ECJ 3.38.0 and zip. From the directory containing `govmap`:

```bash
GOVMAP_SDK=/absolute/android-sdk GOVMAP_ECJ=/absolute/ecj-3.38.0.jar bash govmap/build.sh
GOVMAP_SDK=/absolute/android-sdk GOVMAP_ECJ=/absolute/ecj-3.38.0.jar bash govmap/tests/assets/run.sh
GOVMAP_SDK=/absolute/android-sdk GOVMAP_ECJ=/absolute/ecj-3.38.0.jar bash govmap/tests/updates/run.sh
```

Output: `GovMap-Dark.apk`. The JSON jar is host-test-only and is not packaged in the APK. `LiveAssetTest.java` is a separate optional HTTPS test. Keep the original `signing/` backup private and unchanged for future signed updates.

Host checks cover first-byte delivery before a gated download completes, concurrent readers, complete-only persistence, corrupt-body repair, commit-failure handling, gzip/headers, eviction, metadata freshness/validation/credential rules, and theme injection/state/contrast samples. A previous real HTTPS test retrieved GovMap's 12,878,764-byte JavaScript once and reused the same checksum after reopening the store without another fetch. Update tests exercise release parsing, unsafe URLs/redirects, transfer bounds, truncation, corrupt hashes, cancellation, actual signed-APK metadata and tamper rejection. These are component checks, not phone measurements. The new developer-menu dialogs, clipboard and file-picker interaction have not been exercised on a physical phone here. Signed-APK publication through GitHub Actions and live update-feed/download validation are verified separately. See `tests/assets/validation.md`.
