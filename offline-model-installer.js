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
// Incremental SHA-256: bounded memory for multi-GB model files.
class SHA256 {
 constructor(){this.h=new Uint32Array([0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]);this.buf=new Uint8Array(64);this.used=0;this.bytes=0;this.w=new Uint32Array(64)}
 static rotr(x,n){return (x>>>n)|(x<<(32-n))}
 block(b,off){const w=this.w,k=SHA256.K,R=SHA256.rotr;for(let i=0;i<16;i++){const j=off+i*4;w[i]=((b[j]<<24)|(b[j+1]<<16)|(b[j+2]<<8)|b[j+3])>>>0}for(let i=16;i<64;i++){const x=w[i-15],y=w[i-2];w[i]=(w[i-16]+(R(x,7)^R(x,18)^(x>>>3))+w[i-7]+(R(y,17)^R(y,19)^(y>>>10)))>>>0}let [a,c,d,e,f,g,h,j]=this.h;for(let i=0;i<64;i++){const s1=R(f,6)^R(f,11)^R(f,25),ch=(f&g)^(~f&h),t1=(j+s1+ch+k[i]+w[i])>>>0,s0=R(a,2)^R(a,13)^R(a,22),maj=(a&c)^(a&d)^(c&d),t2=(s0+maj)>>>0;j=h;h=g;g=f;f=(e+t1)>>>0;e=d;d=c;c=a;a=(t1+t2)>>>0}const v=[a,c,d,e,f,g,h,j];for(let i=0;i<8;i++)this.h[i]=(this.h[i]+v[i])>>>0}
 update(input){const b=input instanceof Uint8Array?input:new Uint8Array(input);this.bytes+=b.length;let p=0;if(this.used){const n=Math.min(64-this.used,b.length);this.buf.set(b.subarray(0,n),this.used);this.used+=n;p+=n;if(this.used===64){this.block(this.buf,0);this.used=0}}while(p+64<=b.length){this.block(b,p);p+=64}if(p<b.length){this.buf.set(b.subarray(p),0);this.used=b.length-p}return this}
 finish(){const bits=this.bytes*8;this.buf[this.used++]=128;if(this.used>56){this.buf.fill(0,this.used);this.block(this.buf,0);this.used=0}this.buf.fill(0,this.used,56);const hi=Math.floor(bits/4294967296),lo=bits>>>0;for(let i=0;i<4;i++){this.buf[56+i]=(hi>>>(24-i*8))&255;this.buf[60+i]=(lo>>>(24-i*8))&255}this.block(this.buf,0);return Array.from(this.h,x=>x.toString(16).padStart(8,'0')).join('')}
}
SHA256.K=new Uint32Array([0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2]);
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
   for(let i=0;i<chunks;i++){const b=await record('parts',id+'/'+f.sha256+'/'+f.name+'/'+i);if(b?.byteLength===Math.min(CHUNK,f.bytes-i*CHUNK))done+=b.byteLength}
  }
  onProgress?.(done,total);
  for(const f of manifest.files){
   const chunks=Math.ceil(f.bytes/CHUNK);
   for(let i=0;i<chunks;i++){
    const k=id+'/'+f.sha256+'/'+f.name+'/'+i,start=i*CHUNK,end=Math.min(f.bytes,(i+1)*CHUNK)-1;
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
   const hasher=new SHA256();
   for(let i=0;i<chunks;i++){
    const part=await record('parts',id+'/'+f.sha256+'/'+f.name+'/'+i);
    if(!part)throw Error('Bo‘lak topilmadi');
    hasher.update(part);
    if(i%8===0)await new Promise(resolve=>setTimeout(resolve,0));
   }
   if(hasher.finish()!==f.sha256)throw Error('SHA-256 mos kelmadi: '+f.name);
   await record('verified',id+'/'+f.name,{sha256:f.sha256,bytes:f.bytes});
  }
  onStatus?.('Fayllar tekshirildi. Offline ijro dvigateli ishga tushishi kutilmoqda.');
  return {downloaded:true,active:false};
 })().finally(()=>active.delete(id));
 active.set(id,work);return work;
}
window.avnivoSafeOfflineInstaller={download};
})();