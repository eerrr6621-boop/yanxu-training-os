'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('web/environment.js','utf8');let checks=0;
const check=(condition,message)=>{assert.ok(condition,message);checks++;};
const settle=()=>new Promise(resolve=>setImmediate(resolve));
function harness(fetcher){
  let now=Date.now(),seq=0;const timers=new Map(),listeners=new Map(),windowListeners=new Map();
  class Element{constructor(){this.children=[];this.textContent='';this.dataset={};}replaceChildren(...nodes){this.children=nodes;}append(...nodes){this.children.push(...nodes);}}
  const clock=new Element(),date=new Element(),weather=new Element(),login=new Element();
  const host={querySelector:s=>({'[data-local-clock]':clock,'[data-local-date]':date,'[data-local-weather]':weather}[s]),closest:()=>login};
  class TestDate extends Date {constructor(...args){super(...(args.length?args:[now]));}static now(){return now;}}
  const doc={hidden:false,createElement:()=>new Element(),addEventListener:(k,f)=>listeners.set(k,f),removeEventListener:(k,f)=>{if(listeners.get(k)===f)listeners.delete(k);}};
  const win={fetch:fetcher,setTimeout:(f,ms)=>{const id=++seq;timers.set(id,{f,ms});return id;},clearTimeout:id=>timers.delete(id),addEventListener:(k,f)=>windowListeners.set(k,f),removeEventListener:(k,f)=>{if(windowListeners.get(k)===f)windowListeners.delete(k);}};
  vm.runInNewContext(source,{window:win,document:doc,Date:TestDate,Intl,URL,AbortController});
  return {mount:()=>win.YanxuEnvironment.mount(host),timers,listeners,windowListeners,weather,clock,doc,advance:ms=>now+=ms};
}
(async()=>{
  let calls=0;const h=harness(async()=>{calls++;if(calls===1)throw new Error('offline');return {ok:true,json:async()=>({status:'not_configured'})};});
  const instance=h.mount();await settle();check(calls===1,'one initial weather request');check([...h.timers.values()].some(x=>x.ms===60000),'temporary failure retries in 60 seconds');check(![...h.timers.values()].some(x=>x.ms===900000),'failure not treated as successful 15m cache');
  h.advance(6000);h.windowListeners.get('online')();await settle();check(calls===2,'online event recovers weather after cooldown');check(h.weather.children[0].textContent==='天气服务待配置','truthful unavailable state');
  h.doc.hidden=true;h.listeners.get('visibilitychange')();check(h.timers.size===0,'hidden page stops timers');h.doc.hidden=false;h.listeners.get('visibilitychange')();check(h.timers.size===2,'visible page resumes one clock and one refresh');
  instance.destroy();check(h.timers.size===0 && h.listeners.size===0 && h.windowListeners.size===0,'destroy clears all timers/listeners');
  let complete,signal;const late=harness((url,options)=>{check(url==='/api/visitor-context' && !url.includes('?'),'only own endpoint without IP/city override');signal=options.signal;return new Promise(resolve=>complete=resolve);});
  const old=late.mount();old.destroy();check(signal.aborted,'destroy aborts pending request');complete({ok:true,json:async()=>({status:'not_configured'})});await settle();check(late.weather.children.length===0,'late response cannot update detached page');check(late.timers.size===0,'late response cannot restart timers');
  const safe=harness(async()=>({ok:true,json:async()=>({status:'ok',city:'<script>bad</script>',temperature:20,condition:'晴',fetchedAt:new Date().toISOString(),source:'QWeather',attributions:['javascript:alert(1)','https://developer.qweather.com/attribution.html']})}));const safeInstance=safe.mount();await settle();check(safe.weather.children[0].textContent==='<script>bad</script>','location is text, never injected HTML');check(safe.weather.children.filter(x=>x.href).every(x=>x.href.startsWith('https:')),'attribution links protocol checked');safeInstance.destroy();
  console.log(JSON.stringify({ok:true,suite:'weather clock lifecycle and network recovery',checks}));
})().catch(error=>{console.error(error);process.exitCode=1;});
