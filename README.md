# GovMap 1.10.0

Android 8.0+. Launcher name **GovMap**, package `il.david.govmap.dark`, version code `13`. Same saved signing key. Install as an update without uninstalling or clearing app data. Existing persistent assets from 1.8/1.8.1 remain compatible.

## GitHub automatic updates

After a one-time opt-in, the native app checks public releases from `davidreuveni/GovMap`. Launch checks run off the UI thread after five seconds, throttled to once per 15 minutes. A network-connected, battery-not-low job checks roughly every six hours, subject to Android scheduling and device restrictions. It survives reboot; this is not instant push delivery. A check/transfer is bounded to two minutes, 16 KiB of metadata and 32 MiB per APK. Only complete, size/hash-checked APKs with the original certificate, correct package and increasing version code are staged. No GitHub token or signing key is included in the APK.

Automatic installation waits until the map is closed. Android 12+ can permit self-updates without repeated confirmation when its platform requirements are satisfied. Otherwise an Android installation approval is offered on a subsequent app opening. Users must permit GovMap to install unknown apps. Long-press the GovMap title to check/install manually or turn updates off. The first setup prompt can be declined. Disabling updates cancels scheduled work and removes pending updater files/sessions.

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

Measurements, report UI, profiling callbacks, debugging sockets and ZIP exporters remain removed. Location warm-up, statistical-area layer default, foreground location handling and GovMap navigation restrictions remain. `README-production.md` is historical documentation for 1.7.

## Build and tests

Requires Java 17, Android SDK platform/build-tools 35, ECJ 3.38.0 and zip. From the directory containing `govmap`:

```bash
GOVMAP_SDK=/absolute/android-sdk GOVMAP_ECJ=/absolute/ecj-3.38.0.jar bash govmap/build.sh
GOVMAP_SDK=/absolute/android-sdk GOVMAP_ECJ=/absolute/ecj-3.38.0.jar bash govmap/tests/assets/run.sh
GOVMAP_SDK=/absolute/android-sdk GOVMAP_ECJ=/absolute/ecj-3.38.0.jar bash govmap/tests/updates/run.sh
```

Output: `GovMap-Dark.apk`. The JSON jar is host-test-only and is not packaged in the APK. `LiveAssetTest.java` is a separate optional HTTPS test. Keep the original `signing/` backup private and unchanged for future signed updates.

Host checks cover first-byte delivery before a gated download completes, concurrent readers, complete-only persistence, corrupt-body repair, commit-failure handling, gzip/headers, eviction, metadata freshness/validation/credential rules, and theme injection/state/contrast samples. A previous real HTTPS test retrieved GovMap's 12,878,764-byte JavaScript once and reused the same checksum after reopening the store without another fetch. Update tests exercise release parsing, unsafe URLs/redirects, transfer bounds, truncation, corrupt hashes, cancellation, actual signed-APK metadata and tamper rejection. These are component checks, not phone measurements. APK 1.10.0, Android installation dialogs, background jobs and self-replacement have not been run on a phone here. GitHub publication and Actions execution are pending repository setup. See `tests/assets/validation.md`.
