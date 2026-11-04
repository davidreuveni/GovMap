const fs = require('fs');
const vm = require('vm');
const assert = require('assert/strict');
const path = require('path');
const css = fs.readFileSync(path.join(__dirname, '../assets/dark.css'), 'utf8');
const template = fs.readFileSync(path.join(__dirname, '../assets/theme.js'), 'utf8');
const nodes = new Map(); const attrs = {};
let cssWrites=0,attributeWrites=0;
const appendChild = element => { assert(!nodes.has(element.id)); nodes.set(element.id, element); };
const document = {
  documentElement: {getAttribute:key=>attrs[key],setAttribute: (key,value) => {attributeWrites++;attrs[key] = value;}, appendChild},
  head: {appendChild}, getElementById: id => nodes.get(id), createElement: tag => {let text;return {tag,get textContent(){return text;},set textContent(value){cssWrites++;text=value;}};}, createElementNS: (ns,tag) => ({tag,style:{},setAttribute(){}})
};
const window = {location:{search:''}};
let pollMapType; let timerCount = 0;
const apply = (dark, oled=false) => vm.runInNewContext(template.replace('/*CSS_VALUE*/', JSON.stringify(css)).replace('/*DARK_VALUE*/', String(dark)).replace('/*OLED_VALUE*/', String(oled)), {document,window,URLSearchParams,setInterval:callback=>{pollMapType=callback; timerCount++; return 1;}});
apply(true);
assert.equal(attrs['data-govmap-dark'], 'true');
assert.equal(nodes.size, 2);
assert.equal(nodes.get('govmap-wrapper-dark-style').textContent, css);
const firstCssWrites=cssWrites,firstAttributeWrites=attributeWrites;
assert.equal(apply(true),true);assert.equal(cssWrites,firstCssWrites,'same document does not reparse CSS');assert.equal(attributeWrites,firstAttributeWrites,'same state does not rewrite attributes');
apply(false); assert.equal(attrs['data-govmap-dark'], 'false'); assert.equal(nodes.size, 2);
apply(true); apply(true); assert.equal(nodes.size, 2);
// Head absent during early load; append to document root instead.
document.head = null; nodes.clear(); apply(true); assert.equal(nodes.size, 2);
// New document after reload uses the saved state; no duplicate styles across toggle cycles.
nodes.clear(); apply(false); assert.equal(attrs['data-govmap-dark'], 'false');
const luminance = hex => {
 const rgb=hex.match(/[a-f0-9]{2}/gi).map(x=>parseInt(x,16)/255).map(x=>x<=.04045?x/12.92:((x+.055)/1.055)**2.4);
 return .2126*rgb[0]+.7152*rgb[1]+.0722*rgb[2];
};
for (const fg of ['edf3fc','c7d2e3','aebed5']) for(const bg of ['151c27','202b3a','2c394c']) {
 const ratio=(luminance(fg)+.05)/(luminance(bg)+.05);
 assert(ratio>=4.5, `${fg}/${bg}: ${ratio}`);
}
assert(css.includes('.ol-layer canvas'));
assert(!/(^|[;{]\s*)(pointer-events|display|position)\s*:/m.test(css));
console.log('PASS: injection, reversible toggle, singleton style, early-load fallback, saved-state application, 9 text contrast pairs, unchanged layout/input properties.');

apply(true,true); assert.equal(attrs['data-govmap-oled'],'true');
window.location.search='?b=1&sb=7'; apply(true,true); assert.equal(attrs['data-govmap-original-map'],'true');
window.location.search='?b=0'; apply(true,true); assert.equal(attrs['data-govmap-original-map'],'false');
apply(false,false); assert.equal(attrs['data-govmap-oled'],'false');
console.log('PASS: OLED state, original-color aerial basemap detection, return to street basemap, light restoration.');
const filterMarkup = nodes.get('govmap-oled-filters').innerHTML;
const filterTables = [...filterMarkup.matchAll(/tableValues="([01 ]+)"/g)].map(m=>m[1].split(' ').map(Number));
assert.equal(filterTables.length, 3);
const lookup = (table, value) => table[Math.min(255,Math.max(0,Math.floor(value*256)))];
const groundMask = rgb => lookup(filterTables[0],(.2126*rgb[0]+.7152*rgb[1]+.0722*rgb[2])/255)
  * lookup(filterTables[1], .5+(rgb[0]-rgb[1])/255)
  * lookup(filterTables[2], .5+(rgb[1]-rgb[2])/255);
assert.equal(groundMask([80,80,80]),1,'neutral ground becomes black');
assert.equal(groundMask([58,58,58]),0,'darker building stays visible');
assert.equal(groundMask([235,235,235]),0,'bright label stays visible');
assert.equal(groundMask([20,100,25]),0,'green park stays colored');
console.log('PASS: OLED filter ground/building/label/park sample regressions. These samples do not establish full raster map coverage.');

window.location.search='?b=1'; document.hidden=false; pollMapType();
assert.equal(attrs['data-govmap-original-map'],'true','poll detects map changes without theme reapply');
window.location.search='?b=0'; document.hidden=true; pollMapType();
assert.equal(attrs['data-govmap-original-map'],'true','hidden document does not poll');
document.hidden=false; pollMapType();
assert.equal(attrs['data-govmap-original-map'],'false','visible document resumes map detection');
assert.equal(timerCount,1,'toggle cycles do not duplicate the map timer');
console.log('PASS: live URL changes, background polling pause/resume, single map timer.');

console.log('PASS: repeated theme injection is a no-op; CSS is installed once per document and switches remain reversible.');
const beforeRepair=cssWrites;nodes.delete('govmap-wrapper-dark-style');
apply(false,false);assert.equal(cssWrites,beforeRepair+1,'removed stylesheet can be repaired without recreating filters');assert.equal(nodes.size,2);
console.log('PASS: theme stylesheet can be restored if the website removes its node.');
