'use strict';
const assert=require('node:assert/strict');
const base=process.env.TRAINING_PREVIEW_BASE||'http://127.0.0.1:18087';
const host=new URL(base);if(!['127.0.0.1','localhost'].includes(host.hostname))throw new Error('Local preview verification only');
let checks=0;const check=(condition,message)=>{assert.ok(condition,message);checks++;};
(async()=>{
  for(const path of ['/','/materials.html','/answer.html','/updates.html']){const r=await fetch(base+path);const text=await r.text();check(r.ok,'public page '+path);check(text.includes('yanxu-v13-clarity-r9'),'current R9 page build '+path);}
  for(const path of ['/environment.js?v=20260906v13r7','/releases.js?v=20260906v13r9','/updates.js?v=20260906v13r7','/assets/book-paper-v13r7.jpg']){const r=await fetch(base+path);check(r.ok,'public asset '+path);}
  const response=await fetch(base+'/api/visitor-context');check(response.ok,'anonymous login weather endpoint');check(/(?:^|,)\s*no-store(?:\s*,|$)/.test(response.headers.get('cache-control')||''),'no shared IP cache (preview proxy also enforces no-store)');
  const payload=await response.json();check(payload.status==='not_configured'||payload.status==='location_unavailable','no fake location in local preview');check(!Object.keys(payload).some(key=>/ip|token|latitude|longitude|key/i.test(key)),'no private details');
  check((await fetch(base+'/api/visitor-context?city=上海')).status===400,'manual city override rejected');check((await fetch(base+'/api/visitor-context',{method:'POST'})).status===405,'weather cannot mutate');
  const start=performance.now();const many=await Promise.all(Array.from({length:80},()=>fetch(base+'/api/visitor-context')));check(many.every(r=>r.status===200),'80 local requests successful');await Promise.all(many.map(r=>r.arrayBuffer()));
  console.log(JSON.stringify({ok:true,checks,concurrent_requests:80,total_ms:Math.round(performance.now()-start),scope:'local disabled-weather HTTP only; not provider/server load-test'},null,2));
})().catch(error=>{console.error(error);process.exitCode=1;});
