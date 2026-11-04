(function () {
  if (window.location.protocol !== 'https:' || !/(^|\.)govmap\.gov\.il$/i.test(window.location.hostname)) return false;
  var geo = navigator.geolocation;
  if (!geo || typeof geo.watchPosition !== 'function' || typeof geo.clearWatch !== 'function') return false;
  if (!window.__govmapLocationPreload) {
    var id = null, wanted = false, denied = false;
    function clear() {
      if (id !== null) {
        try { geo.clearWatch(id); } catch (e) {}
        id = null;
      }
    }
    function start(retryDenied) {
      if (retryDenied) denied = false;
      wanted = true;
      if (document.hidden || denied || id !== null) return false;
      try {
        // Keep the browser's own provider ready; do not intercept the site's requests or save coordinates.
        id = geo.watchPosition(function () {}, function (error) {
          if (error && error.code === 1) { denied = true; wanted = false; clear(); }
        }, {enableHighAccuracy: true, maximumAge: 5000, timeout: 20000});
        return true;
      } catch (e) { wanted = false; clear(); return false; }
    }
    function stop() { wanted = false; clear(); }
    window.__govmapLocationPreload = {start: start, stop: stop};
    document.addEventListener('visibilitychange', function () {
      if (document.hidden) clear();
      else if (wanted && !denied) start(false);
    });
    window.addEventListener('pagehide', stop);
  }
  return window.__govmapLocationPreload.start(/*RETRY_VALUE*/);
})();
