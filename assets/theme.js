(function () {
  var root = document.documentElement;
  if (!root) return;
  var style = document.getElementById('govmap-wrapper-dark-style');
  if (!style) {
    style = document.createElement('style');
    style.id = 'govmap-wrapper-dark-style';
    (document.head || root).appendChild(style);
  }
  var css = /*CSS_VALUE*/;
  if (style.textContent !== css) style.textContent = css;
  function attribute(name, value) {
    if (root.getAttribute(name) !== value) root.setAttribute(name, value);
  }
  attribute('data-govmap-dark', String(/*DARK_VALUE*/));
  attribute('data-govmap-oled', String(/*OLED_VALUE*/));
  if (!document.getElementById('govmap-oled-filters')) {
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.id = 'govmap-oled-filters';
    svg.setAttribute('width', '0'); svg.setAttribute('height', '0');
    svg.setAttribute('aria-hidden', 'true');
    svg.style.cssText = 'position:absolute;width:0;height:0;overflow:hidden;pointer-events:none';
    // Color mask selects neutral ground tones; buildings and labels retain their original tones.
    var mask = [];
    var neutral = [];
    for (var i = 0; i < 256; i++) {
      mask.push(i >= 65 && i <= 95 ? '1' : '0');
      neutral.push(i >= 115 && i <= 140 ? '1' : '0');
    }
    svg.innerHTML = '<defs><filter id="govmap-oled-map" color-interpolation-filters="sRGB" x="0" y="0" width="100%" height="100%">'
      + '<feColorMatrix in="SourceGraphic" values="0 0 0 0 1 0 0 0 0 1 0 0 0 0 1 0.2126 0.7152 0.0722 0 0" result="luma-mask"/>'
      + '<feComponentTransfer in="luma-mask" result="ground-range"><feFuncA type="discrete" tableValues="' + mask.join(' ') + '"/></feComponentTransfer>'
      + '<feColorMatrix in="SourceGraphic" values="0 0 0 0 1 0 0 0 0 1 0 0 0 0 1 1 -1 0 0 0.5" result="rg-diff"/>'
      + '<feComponentTransfer in="rg-diff" result="rg-neutral"><feFuncA type="discrete" tableValues="' + neutral.join(' ') + '"/></feComponentTransfer>'
      + '<feColorMatrix in="SourceGraphic" values="0 0 0 0 1 0 0 0 0 1 0 0 0 0 1 0 1 -1 0 0.5" result="gb-diff"/>'
      + '<feComponentTransfer in="gb-diff" result="gb-neutral"><feFuncA type="discrete" tableValues="' + neutral.join(' ') + '"/></feComponentTransfer>'
      + '<feComposite in="ground-range" in2="rg-neutral" operator="in" result="ground-rg"/>'
      + '<feComposite in="ground-rg" in2="gb-neutral" operator="in" result="ground-selected"/>'
      + '<feComponentTransfer in="ground-selected" result="ground-mask"><feFuncA type="linear" slope="-1" intercept="1"/></feComponentTransfer>'
      + '<feComponentTransfer in="SourceGraphic" result="visible-features"><feFuncR type="linear" slope="0.85" intercept="0.15"/><feFuncG type="linear" slope="0.85" intercept="0.15"/><feFuncB type="linear" slope="0.85" intercept="0.15"/></feComponentTransfer>'
      + '<feComposite in="visible-features" in2="ground-mask" operator="in" result="features"/>'
      + '<feFlood flood-color="#000" result="black-ground"/><feComposite in="features" in2="black-ground" operator="over"/>'
      + '</filter></defs>';
    (document.body || root).appendChild(svg);
  }
  function syncMapType() {
    var query = new URLSearchParams(window.location.search);
    var background = query.get('b');
    // Only the default street basemap receives recoloring. Preserve all alternate basemaps.
    var originalColors = (background !== null && background !== '0') || query.has('sb');
    attribute('data-govmap-original-map', String(originalColors));
  }
  syncMapType();
  if (!window.__govmapMapTypeTimer) {
    window.__govmapMapTypeTimer = setInterval(function () {
      if (!document.hidden) syncMapType();
    }, 600);
  }
  return true;
})();
