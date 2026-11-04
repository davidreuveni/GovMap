# GovMap 1.9.1 validation — 2026-11-02

## Passed host checks

- API 35 compile, min API 26 DEX generation, zip alignment and APK v2/v3 signature checks.
- Same package and launcher name, version code 12/version name 1.9.1. Original INTERNET/location permissions only. Signing backup and certificate unchanged.
- Manifest declares the selected orientation/window/keyboard configuration changes; Activity callback redraws/resizes the existing WebView without calling loadUrl/recreate. No orientation lock introduced. Physical rotation/resizing behavior has not been exercised here.
- Controlled two-stage stream: get() returns while the remainder is deliberately blocked; a reader receives the first 4,096 bytes before the gate is released. A second reader independently receives the same content from one fetcher. No incomplete body appears as a reusable file. A failed metadata commit still lets readers consume a complete valid network response.
- Persistent reopen/cache-delete/version update, integrity repair, interrupted/truncated/206/private/cookie/oversize rejection, gzip/charset/CORS, bounded eviction and concurrent deduplication tests pass.
- Metadata tests: nonblocking cold misses, fresh-only reuse, duplicate refresh suppression, conditional ETag 304, disk reuse, expiration, updated data, request credential/range/no-store/reload rules, response private/no-cache/no-store/Vary rules, origin Age/Expires, short heuristic and access-denial backoff. Rejected refresh policies invalidate older entries.
- Theme tests: same-document duplicate injection does not write CSS or attributes; light/dark/OLED state remains reversible; removed stylesheet can be repaired; existing contrast and map-mask samples pass. Full live visual coverage is not claimed.
- Fresh APK inspection excludes the removed profiler/debug/export classes and measurement asset. Source patch applies to 1.9 and reproduces this source tree.

## Location preload checks

The location script starts one high-accuracy browser watch before any site getCurrentPosition call, with maximumAge 5 seconds and timeout 20 seconds. Tests verify repeat callbacks do not duplicate it; hide/pause/native stop/page exit clear only its own watch; foreground resume restarts it; permission denial does not create retry loops; explicit retry can recover; timeout does not create extra watches. A hidden document and non-GovMap/insecure origin do not start a watch. The original site geolocation methods are unchanged, and the success callback retains no coordinates.

Native integration compiles and checks foreground/document readiness before starting. The automatic permission-attempt flag is not set on an uncommitted document. Existing Android coarse/fine permissions are unchanged; no background-location or foreground-service permission is added. Native permission-dialog lifecycle and actual GPS latency have not been exercised on a physical device.

## Real HTTPS component check inherited from 1.9


Production asset downloader/store retrieved `https://www.govmap.gov.il/assets/index-DyQH9mBN.js` (12,878,764-byte body). One fetch; reopened store returned the same checksum without another network fetch. This was a Java host component test, not Android WebView.

Anonymous access to the catalog endpoint was denied in this environment (HTTP 400/403, `access denied`). The live metadata optimization could not be validated against a permitted response. The code falls through immediately to the website's request and does not cache the denial. Deterministic test responses cover the cache's permitted-response behavior.

## Not verified

- Installation and runtime of APK 1.9.1 on a physical phone.
- Measured improvement in real phone cold/warm startup or frame timing.
- Full live map/menu/GPS/theme audit.

No runtime profiling or report feature was reintroduced. Cache freshness timestamps and integrity/length checks are cache bookkeeping, not performance measurement.
