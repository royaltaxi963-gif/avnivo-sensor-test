/* AVNIVO offline installer foundation. No activation without a working runtime.
 * Ticket contract: {model_id,files:[{name,bytes,sha256,url}]}
 * URL must be a short-lived HTTPS signed download URL from trusted backend.
 */
(function(){
'use strict';
const API='https://avnivo-translation-test-792624108019.europe-west1.run.app';
const IDS=new Set(['whisper-small-q8','whisper-large-v3-turbo','nllb-200-600m-int8','navoiy-tts','mms-tts']);
const CHUNK=4*1024*1024;
const active=new Map();
function database(){return new Promise((ok,fail)=>{const q=indexedDB.open('avnivo-offline-installer-v2',1);q.onupgradeneeded=()=>{q.result.createObjectStore('parts');q.result.createObjectStore('verified')};q.onsuccess=()=>ok(q.result);q.onerror=()=>fail(q.error)})}
async function record(store,key,value){const d=await database();try{return await new Promise((ok,fail)=>{const t=d.transaction(store,value===undefined?'readonly':'readwrite'),q=value===undefined?t.objectStore(store).get(key):t.objectStore(store).put(value,key);q.onsuccess=()=>ok(q.result);q.onerror=()=>fail(q.error);t.onerror=()=>fail(t.error)})}finally{d.close()}}
const hex=b=>Array.from(new Uint8Array(b),x=>x.toString(16).padStart(2,'0')).join('');
const digest=b=>crypto.subtle.digest('SHA-256',b).then(hex);
async function ticket(id){
 const r=await fetch(API+'/api/offline-models/'+encodeURIComponent(id)+'/download-ticket',{cache:'no-store'});
 if(!r.ok)throw Error(r.status===404?'Bu modul uchun xavfsiz yuklash manbasi hali tayyor emas':'Yuklash ruxsati: HTTP '+r.status);
 const m=await r.json();
 if(m.model_id!==id||!Array.isArray(m.files)||!m.files.length)throw Error('Manifest noto‘g‘ri');
 let total=0;const names=new Set();
 for(const f of m.files){
  if(!f||typeof f.name!=='string'||!/^[a-zA-Z0-9_.\/-]+$/.test(f.name)||f.name.includes('..')||names.has(f.name)||!Number.isSafeInteger(f.bytes)||f.bytes<1||!/^[a-f0-9]{64}$/.test(f.sha256)||typeof f.url!=='string'||!f.url.startsWith('https://'))throw Error('Xavfsiz manifest talablari bajarilmadi');
  names.add(f.name);total+=f.bytes;
 }
 if(!Number.isSafeInteger(total)||total>15*1024**3)throw Error('Model hajmi cheklovdan katta');
 return m;
}
async function download(id,onProgress,onStatus){
 if(!IDS.has(id))throw Error('Noma’lum model');
 if(!isSecureContext||!indexedDB||!crypto.subtle)throw Error('HTTPS va IndexedDB kerak');
 if(active.has(id))return active.get(id);
 const work=(async()=>{
  let manifest=await ticket(id);
  const total=manifest.files.reduce((n,f)=>n+f.bytes,0);
  if(navigator.storage?.estimate){const e=await navigator.storage.estimate();if(e.quota&&e.usage&&e.quota-e.usage<total*1.1)throw Error('Telefonda yetarli bo‘sh xotira yo‘q')}
  let done=0;
  for(const f of manifest.files){
   const chunks=Math.ceil(f.bytes/CHUNK);
   for(let i=0;i<chunks;i++){const b=await record('parts',id+'/'+f.name+'/'+i);if(b?.byteLength===Math.min(CHUNK,f.bytes-i*CHUNK))done+=b.byteLength}
  }
  onProgress?.(done,total);
  for(const f of manifest.files){
   const chunks=Math.ceil(f.bytes/CHUNK);
   for(let i=0;i<chunks;i++){
    const k=id+'/'+f.name+'/'+i,start=i*CHUNK,end=Math.min(f.bytes,(i+1)*CHUNK)-1;
    const previous=await record('parts',k);
    if(previous?.byteLength===end-start+1)continue;
    let success=false;
    for(let attempt=0;attempt<4&&!success;attempt++){
     try{
      if(!navigator.onLine)await new Promise(resolve=>window.addEventListener('online',resolve,{once:true}));
      const fresh=manifest.files.find(x=>x.name===f.name);
      const r=await fetch(fresh.url,{headers:{Range:'bytes='+start+'-'+end},cache:'no-store'});
      if(r.status===401||r.status===403){manifest=await ticket(id);continue}
      if(r.status!==206||r.headers.get('Content-Range')!=='bytes '+start+'-'+end+'/'+f.bytes)throw Error('Server bo‘laklab yuklashni qo‘llamaydi');
      const data=await r.arrayBuffer();if(data.byteLength!==end-start+1)throw Error('Yuklangan hajm mos emas');
      await record('parts',k,data);done+=data.byteLength;onProgress?.(done,total);success=true;
     }catch(e){if(attempt===3)throw e;await new Promise(resolve=>setTimeout(resolve,1000*(attempt+1)));manifest=await ticket(id)}
    }
   }
   onStatus?.('SHA-256 tekshirilmoqda: '+f.name);
   // Incremental SHA-256 implementation is required for very large models;
   // do not allocate multi-GB contiguous buffers or mark unverified models ready.
   if(f.bytes>256*1024*1024)throw Error('Katta modelni xotirasiz SHA-256 tekshiruvchi hali ulanmagan; yuklangan bo‘laklar saqlandi');
   const data=new Uint8Array(f.bytes);
   for(let i=0;i<chunks;i++){const part=await record('parts',id+'/'+f.name+'/'+i);if(!part)throw Error('Bo‘lak topilmadi');data.set(new Uint8Array(part),i*CHUNK)}
   if(await digest(data)!==f.sha256)throw Error('SHA-256 mos kelmadi: '+f.name);
   await record('verified',id+'/'+f.name,{sha256:f.sha256,bytes:f.bytes});
  }
  onStatus?.('Fayllar tekshirildi. Offline ijro dvigateli ishga tushishi kutilmoqda.');
  return {downloaded:true,active:false};
 })().finally(()=>active.delete(id));
 active.set(id,work);return work;
}
window.avnivoSafeOfflineInstaller={download};
})();