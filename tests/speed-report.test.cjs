const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const code=fs.readFileSync('assets/speed-report.js','utf8');
const asset='https://www.govmap.gov.il/assets/map-deadbeef123456.js';
function report(resources,extra={}){
  const performance={now:()=>1800,getEntriesByType:type=>type==='resource'?resources:type==='navigation'?[{responseStart:120,domContentLoadedEventEnd:800,loadEventEnd:0,transferSize:500}]:type==='paint'?[{name:'first-contentful-paint',startTime:400}]:[]};
  const context={URL,performance,window:{performance},...extra};
  Object.defineProperty(context,'navigator',{get(){throw Error('Location/device APIs must not be read')}});
  return JSON.parse(vm.runInNewContext(code,context));
}
const entries=[{name:asset,initiatorType:'script',duration:900,transferSize:10000,encodedBodySize:9800,decodedBodySize:30000},
 {name:'https://www.govmap.gov.il/api/search?lat=31.123456&token=private',initiatorType:'fetch',duration:500,transferSize:0,encodedBodySize:0,decodedBodySize:0},
 {name:asset,initiatorType:'script',duration:10,transferSize:0,decodedBodySize:30000,deliveryType:'cache'},
 {name:'https://private.example.test/31.123456/34.987654',initiatorType:'SECRET',duration:NaN,transferSize:Infinity}];
const d=report(entries);
assert.equal(d.available,true);assert.equal(d.browserReportedTransferBytes,10000);assert.equal(d.unknownTransferEntries,3);assert.equal(d.explicitCacheEntries,1);
assert.equal(d.navigation.loadEventMs,null);assert.equal(d.navigation.domReadyMs,800);assert.equal(d.slowest[0].name,'map-deadbeef123456.js');
assert.equal(d.largest[0].transferBytes,10000);
assert.match(d.slowest.find(x=>x.name==='[resource URL omitted]').source,/unknown/);
const text=JSON.stringify(d);
for(const secret of ['31.123456','34.987654','private.example','token=private','SECRET','?lat='])assert.ok(!text.includes(secret));
assert.equal(entries[0].name,asset,'sampling must not modify browser entries');
assert.equal(d.slowest.find(x=>x.type==='other').durationMs,0);
for(const url of [asset+'?token=x',asset+'#secret','https://user:secret@www.govmap.gov.il/assets/map-deadbeef123456.js','file:///assets/map-deadbeef123456.js'])
 assert.equal(report([{name:url,initiatorType:'script'}]).largest[0].name,'[resource URL omitted]');
const bounded=report(Array.from({length:2000},(_,i)=>({name:asset,duration:i,transferSize:i})));
assert.equal(bounded.sampledResourceEntries,1500);assert.equal(bounded.slowest.length,15);assert.equal(bounded.largest.length,15);
assert.equal(JSON.parse(vm.runInNewContext(code,{window:{}})).available,false);
assert.equal(report([],{performance:{getEntriesByType(){throw Error('unavailable')}}}).available,false);
console.log('PASS: on-demand speed sampling; unknown/cache distinction; navigation pending state; bounded rankings; URL/coordinate/credential omission; no location access.');
