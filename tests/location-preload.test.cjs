const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert/strict');
const template = fs.readFileSync(path.join(__dirname, '../assets/location-preload.js'), 'utf8');

function environment({hidden=false,host='www.govmap.gov.il',protocol='https:',geoMissing=false,throws=false}={}) {
  const events = new Map(), watches = new Map(), calls = [], cleared = [];
  let next = 100, ordinaryRequests = 0;
  const geo = {
    getCurrentPosition() { ordinaryRequests++; },
    watchPosition(success,error,options) {
      if (throws) throw new Error('Location unavailable');
      const id = next++; watches.set(id,{success,error,options}); calls.push(id); return id;
    },
    clearWatch(id) { cleared.push(id); watches.delete(id); }
  };
  const methods = {...geo};
  const add = (name,callback) => {if(!events.has(name))events.set(name,[]);events.get(name).push(callback);};
  const document = {hidden,addEventListener:add};
  const window = {location:{hostname:host,protocol},addEventListener:add};
  const context = {window,document,navigator:{geolocation:geoMissing?null:geo}};
  const inject = (retry=false) => vm.runInNewContext(template.replace('/*RETRY_VALUE*/',String(retry)),context);
  const event = name => (events.get(name)||[]).forEach(callback=>callback());
  return {geo,methods,watches,calls,cleared,events,document,window,inject,event,get ordinaryRequests(){return ordinaryRequests;}};
}

const app = environment();
const siteWatch = app.geo.watchPosition(()=>{},()=>{},{});
assert.equal(app.inject(),true);
const preload = app.calls.at(-1), settings = app.watches.get(preload).options;
assert.equal(settings.enableHighAccuracy,true);
assert.equal(settings.maximumAge,5000);
assert.equal(settings.timeout,20000);
assert.equal(app.inject(),false,'repeat page callbacks do not create a second preload watch');
assert.equal(app.calls.length,2);
assert.equal(app.events.get('visibilitychange').length,1);
assert.equal(app.events.get('pagehide').length,1);
assert.equal(app.geo.getCurrentPosition,app.methods.getCurrentPosition);
assert.equal(app.geo.watchPosition,app.methods.watchPosition);
assert.equal(app.geo.clearWatch,app.methods.clearWatch);
app.watches.get(preload).success({coords:{latitude:31.123456789,longitude:35.987654321,accuracy:8}});
assert(!JSON.stringify(app.window).includes('31.123456789'),'preloader does not retain coordinates');
assert.equal(app.ordinaryRequests,0,'preloader does not invoke or replace the site location-button request');

app.document.hidden=true;app.event('visibilitychange');
assert(!app.watches.has(preload));assert(app.watches.has(siteWatch),'site-owned watch is not cleared');
app.document.hidden=false;app.event('visibilitychange');assert.equal(app.watches.size,2);
app.window.__govmapLocationPreload.stop();assert.equal(app.watches.size,1);
app.document.hidden=true;app.event('visibilitychange');app.document.hidden=false;app.event('visibilitychange');
assert.equal(app.watches.size,1,'native stop prevents a visibility event restarting location');
assert.equal(app.inject(true),true,'foreground resume starts one new preload watch');
app.event('pagehide');assert.equal(app.watches.size,1,'page exit clears only the preload watch');

const denied = environment();denied.inject();let id=denied.calls.at(-1);
denied.watches.get(id).error({code:1});assert.equal(denied.watches.size,0);
assert.equal(denied.inject(),false,'denial does not cause automatic retry loops');
denied.document.hidden=true;denied.event('visibilitychange');denied.document.hidden=false;denied.event('visibilitychange');
assert.equal(denied.calls.length,1);
assert.equal(denied.inject(true),true,'explicit native retry after permission is granted can recover');
id=denied.calls.at(-1);denied.watches.get(id).error({code:3});
assert.equal(denied.watches.size,1,'timeout does not create retry timers or duplicate watches');
denied.window.__govmapLocationPreload.stop();

const hidden = environment({hidden:true});assert.equal(hidden.inject(),false);assert.equal(hidden.calls.length,0);
hidden.document.hidden=false;hidden.event('visibilitychange');assert.equal(hidden.calls.length,1);
for (const options of [{host:'evilgovmap.gov.il'},{host:'www.govmap.gov.il.evil.example'},{protocol:'http:'},{geoMissing:true}]) {
  const e=environment(options);assert.equal(e.inject(),false);assert.equal(e.calls.length,0);
}
const unsupported=environment({throws:true});assert.equal(unsupported.inject(),false);assert.equal(unsupported.watches.size,0);
console.log('PASS: early high-accuracy watch; singleton startup/resume; pause/hidden/page-exit cleanup; site API/watch preservation; denial/timeout handling; trusted-origin guard; no coordinate retention.');
