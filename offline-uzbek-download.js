/* AVNIVO offline model downloader: real-byte resumable IndexedDB chunks.
   No model is marked active until an actual inference runtime is integrated. */
(function(){
'use strict';
const API='https://avnivo-translation-test-792624108019.europe-west1.run.app';
const EXPECTED={
 'model.int8.onnx':{bytes:591649160,sha256:'8bff17ef8d7da04369a559b99b71c1155a90fc604d35d84c6d4e44159c1b0873'},
 'tokens.txt':{bytes:411,sha256:'2022129a0c61784d4a1838da59e5875f9b29d2c6ad12413cbedc98632f98710f'}
};
const CHUNK=4*1024*1024;
const db=()=>new Promise((resolve,reject)=>{const q=indexedDB.open('avnivo-offline-models-v1',1);q.onupgradeneeded=()=>{const d=q.result;d.createObjectStore('chunks');d.createObjectStore('metadata')};q.onsuccess=()=>resolve(q.result);q.onerror=()=>reject(q.error)});
async function tx(store,mode,work){const d=await db();try{return await new Promise((resolve,reject)=>{const t=d.transaction(store,mode),s=t.objectStore(store),q=work(s);q.onsuccess=()=>resolve(q.result);q.onerror=()=>reject(q.error);t.onerror=()=>reject(t.error)})}finally{d.close()}}
const get=(store,key)=>tx(store,'readonly',s=>s.get(key));
const put=(store,key,value)=>tx(store,'readwrite',s=>s.put(value,key));
const del=(store,key)=>tx(store,'readwrite',s=>s.delete(key));
const key=(name,i)=>'uzbek-stt/'+name+'/'+i;
async function ticket(){const r=await fetch(API+'/api/offline-models/uzbek-stt/download-ticket',{cache:'no-store'});if(!r.ok)throw Error('Yuklash ruxsati: HTTP '+r.status);const x=await r.json();if(x.model_id!=='uzbek-stt'||!Array.isArray(x.files)||x.files.length!==2)throw Error('Server manifesti noto‘g‘ri');for(const [name,expected] of Object.entries(EXPECTED)){const f=x.files.find(f=>f.name===name);if(!f||f.bytes!==expected.bytes||f.sha256!==expected.sha256||!/^https:\/\//.test(f.url))throw Error('Manifest mos emas: '+name)}return x}
async function sha256(data){const digest=await crypto.subtle.digest('SHA-256',data);return Array.from(new Uint8Array(digest),x=>x.toString(16).padStart(2,'0')).join('')}
async function run(onProgress,onStatus){
 if(!window.indexedDB||!crypto.subtle)throw Error('Brauzer xavfsiz saqlash yoki SHA-256 ni qo‘llamaydi');
 let manifest=await ticket();
 const total=Object.values(EXPECTED).reduce((a,f)=>a+f.bytes,0);
 let done=0;
 for(const [name,expected] of Object.entries(EXPECTED)){
  const count=Math.ceil(expected.bytes/CHUNK);
  for(let i=0;i<count;i++){const existing=await get('chunks',key(name,i));const size=Math.min(CHUNK,expected.bytes-i*CHUNK);if(existing&&existing.byteLength===size)done+=size}
 }
 onProgress(done,total);
 for(const [name,expected] of Object.entries(EXPECTED)){
  const count=Math.ceil(expected.bytes/CHUNK);
  for(let i=0;i<count;i++){
   const start=i*CHUNK,end=Math.min(expected.bytes,(i+1)*CHUNK)-1,size=end-start+1;
   const existing=await get('chunks',key(name,i));if(existing&&existing.byteLength===size)continue;
   let ok=false;
   for(let attempt=0;attempt<4&&!ok;attempt++){
    const f=manifest.files.find(f=>f.name===name);
    try{
     const response=await fetch(f.url,{headers:{Range:'bytes='+start+'-'+end},cache:'no-store'});
     if(response.status===401||response.status===403){manifest=await ticket();continue}
     if(response.status!==206)throw Error('Server Range (206) qo‘llamaydi: '+response.status);
     const cr=response.headers.get('Content-Range');
     if(cr!=='bytes '+start+'-'+end+'/'+expected.bytes)throw Error('Content-Range mos emas');
     const data=await response.arrayBuffer();
     if(data.byteLength!==size)throw Error('Yuklangan bayt soni mos emas');
     await put('chunks',key(name,i),data);done+=size;onProgress(done,total);ok=true;
    }catch(e){if(attempt===3)throw e;await new Promise(r=>setTimeout(r,1000*(attempt+1)));manifest=await ticket()}
   }
  }
  onStatus('SHA-256 tekshirilmoqda: '+name);
  // crypto.subtle requires a contiguous buffer. Verification may fail on memory-constrained devices.
  const buffer=new Uint8Array(expected.bytes);
  for(let i=0;i<count;i++){const part=await get('chunks',key(name,i));if(!part)throw Error('Saqlangan bo‘lak topilmadi');buffer.set(new Uint8Array(part),i*CHUNK)}
  const hash=await sha256(buffer);
  if(hash!==expected.sha256){await del('metadata','uzbek-stt/verified');throw Error('SHA-256 mos kelmadi: '+name)}
  await put('metadata','uzbek-stt/'+name+'/verified',expected.sha256);
 }
 await put('metadata','uzbek-stt/verified',{at:Date.now(),sha256:EXPECTED['model.int8.onnx'].sha256});
 onStatus('Model yuklandi va SHA-256 tasdiqlandi. Ovoz tanish dvigateli hali ulanmagan.');
 return true;
}
// Resume automatically when connectivity returns while this page remains open.
function waitUntilOnline(onStatus){
 if(navigator.onLine)return Promise.resolve();
 onStatus('Internet uzildi. Yuklangan qism saqlandi; aloqa qaytsa avtomatik davom etadi.');
 return new Promise(resolve=>{
  const resume=()=>{if(!navigator.onLine)return;window.removeEventListener('online',resume);resolve()};
  window.addEventListener('online',resume);
  if(navigator.onLine)resume();
 });
}
let activeRun=null;
function runWithAutoResume(onProgress,onStatus){
 if(activeRun)return activeRun;
 activeRun=(async()=>{
  let reconnects=0;
  while(true){
   await waitUntilOnline(onStatus);
   try{
    if(reconnects)onStatus('Internet tiklandi. Yuklangan bo‘laklardan davom etmoqda…');
    return await run(onProgress,onStatus);
   }catch(error){
    if(navigator.onLine)throw error; // Server, storage or integrity errors must not loop forever.
    reconnects++;
    await waitUntilOnline(onStatus);
   }
  }
 })().finally(()=>{activeRun=null});
 return activeRun;
}
window.avnivoVerifiedUzbekDownload={run:runWithAutoResume};
})();