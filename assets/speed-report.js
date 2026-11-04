(function () {
  'use strict';
  function n(value) { return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? Math.min(value, 1e12) : 0; }
  function name(value) {
    try {
      var u = new URL(String(value));
      if (u.protocol === 'https:' && !u.username && !u.password && !u.search && !u.hash &&
          /^(www\.)?govmap\.gov\.il$/i.test(u.hostname) &&
          /^\/assets\/[A-Za-z0-9_/-]+-[A-Za-z0-9_-]{8,}\.(js|css|woff2?|ttf|otf|png|jpe?g|webp|svg|ico)$/.test(u.pathname))
        return u.pathname.slice(u.pathname.lastIndexOf('/') + 1).slice(0, 140);
    } catch (_) {}
    return '[resource URL omitted]';
  }
  try {
    if (!window.performance || typeof performance.getEntriesByType !== 'function') return JSON.stringify({available:false});
    var all = performance.getEntriesByType('resource'), total = 0, unknown = 0, cached = 0;
    var rows = all.slice(-1500).map(function (e) {
      var transfer = n(e.transferSize); total += transfer;
      var source = e.deliveryType === 'cache' ? 'browser-reported cache' : transfer > 0 ? 'browser-reported transfer' : 'unknown: cache/intercept/restricted timing';
      if (transfer === 0) unknown++; if (e.deliveryType === 'cache') cached++;
      var type = ['script','link','fetch','xmlhttprequest','img','css','iframe','navigation','other'].indexOf(e.initiatorType) >= 0 ? e.initiatorType : 'other';
      return {name:name(e.name), type:type, durationMs:n(e.duration),
        transferBytes:transfer, encodedBytes:n(e.encodedBodySize), decodedBytes:n(e.decodedBodySize), source:source};
    });
    var nav = performance.getEntriesByType('navigation')[0], navigation = null;
    if (nav) navigation = {ttfbMs:n(nav.responseStart), domReadyMs:n(nav.domContentLoadedEventEnd) || null,
      loadEventMs:n(nav.loadEventEnd) || null, documentTransferBytes:n(nav.transferSize)};
    var paints = performance.getEntriesByType('paint').slice(0, 8).map(function(e) {
      return {name:e.name === 'first-contentful-paint' ? e.name : 'paint', startMs:n(e.startTime)};
    });
    return JSON.stringify({available:true, retainedResourceEntries:all.length, sampledResourceEntries:rows.length,
      pageAgeMs:n(performance.now()), navigation:navigation, paints:paints, browserReportedTransferBytes:total,
      unknownTransferEntries:unknown, explicitCacheEntries:cached,
      slowest:rows.slice().sort(function(a,b){return b.durationMs-a.durationMs;}).slice(0, 15),
      largest:rows.slice().sort(function(a,b){return b.transferBytes-a.transferBytes;}).slice(0, 15)});
  } catch (_) { return JSON.stringify({available:false}); }
})()
