/* AVNIVO offline model catalogue — native Android bridge integration UI.
   No fake downloads or browser-local "installed" flags. */
(function(){
'use strict';
const modules=[
 {id:'uzbek-stt',name:'O‘zbekcha ovoz tanish',size:'591,6 MB',sizeBytes:591649571,description:'Internetsiz o‘zbekcha nutqni matnga aylantiradi.',ready:true},
 {id:'whisper-small-q8',name:'Whisper Small Multilingual',size:'Fayllar tasdiqlanmagan',sizeBytes:null,description:'Internetsiz nutqni matnga aylantiradi.',ready:false},
 {id:'nllb-200-600m-int8',name:'NLLB-200 600M INT8',size:'Taxminan 895 MB (tasdiqlanmagan)',sizeBytes:null,description:'Internetsiz matnni boshqa tilga tarjima qiladi.',ready:false}
 ,{id:'navoiy-tts',name:'Navoiy o‘zbekcha TTS',size:'2 GB dan ortiq',sizeBytes:null,description:'O‘zbekcha ovoz sintezi. Android ijro dvigateli talab qilinadi.',ready:true}
 ,{id:'mms-tts',name:'MMS ruscha TTS',size:'291 MB atrofida',sizeBytes:null,description:'Ruscha ovoz sintezi. Android ijro dvigateli talab qilinadi.',ready:true}
];
const css=document.createElement('style');
css.textContent=`
#avnivoModuleManager{margin:16px 0;padding:12px;border:1px solid #9d8055;border-radius:14px;color:inherit}
#avnivoModuleManager h3{margin:0 0 8px;font-size:16px}
#avnivoModuleManager .module-note{font-size:12px;opacity:.9;line-height:1.5}
#avnivoModuleManager .module-card{padding:13px 0;border-top:1px solid #79684e}
#avnivoModuleManager .module-head{display:flex;align-items:center;justify-content:space-between;gap:10px}
#avnivoModuleManager .module-head strong{font-size:14px}
#avnivoModuleManager .module-size{font-size:12px;opacity:.85;margin:5px 0}
#avnivoModuleManager .module-description{font-size:12px;line-height:1.55;margin:9px 0 0}
#avnivoModuleManager .module-state{font-size:12px;margin-top:6px}
#avnivoModuleManager .module-icon{display:inline-flex;align-items:center;justify-content:center;width:42px;min-width:42px;height:42px;border:1px solid rgba(231,183,91,.65);border-radius:13px;background:linear-gradient(145deg,#ffe3a2,#e4a93c);color:#35230c;box-shadow:0 3px 10px rgba(0,0,0,.2);cursor:pointer;font-size:20px;position:relative;overflow:hidden}
#avnivoModuleManager .module-icon svg{width:24px;height:24px;stroke:currentColor;fill:none;stroke-width:1.9;stroke-linecap:round;stroke-linejoin:round;transition:transform .25s ease}
#avnivoModuleManager .module-icon:not(:disabled):active svg{transform:scale(.85)}
#avnivoModuleManager .module-icon:not(:disabled):hover svg{transform:translateY(2px) scale(1.08)}
#avnivoModuleManager .module-icon.is-loading{background:rgba(231,183,91,.12);color:#e7b75b;border-color:#e7b75b}
#avnivoModuleManager .module-icon.is-loading svg{animation:avnivo-rotate 1s linear infinite}
@keyframes avnivo-rotate{to{transform:rotate(360deg)}}
@media(prefers-reduced-motion:reduce){#avnivoModuleManager .module-icon svg{transition:none}#avnivoModuleManager .module-icon.is-loading svg{animation:none}}
#avnivoModuleManager .module-icon:disabled{opacity:.45;cursor:not-allowed}
#avnivoModuleManager .module-progress{width:100%;height:10px;margin-top:8px}
#avnivoModuleManager .module-progress-text{font-size:12px}
#avnivoModuleManager .module-active{display:inline-flex;align-items:center;gap:5px;color:#4a9cff;font-size:11px;font-weight:700;margin-top:5px;animation:avnivo-activate .45s ease-out}
#avnivoModuleManager .module-active::before{content:'';width:6px;height:6px;background:#4a9cff;border-radius:50%;box-shadow:0 0 8px #4a9cff}
@keyframes avnivo-activate{from{opacity:0;transform:scale(.8)}to{opacity:1;transform:scale(1)}}
`;
document.head.append(css);
function bridge(){const b=window.AvnivoOfflineModules;return b&&typeof b.status==='function'?b:null}
function read(id){try{const b=bridge();if(!b)return null;const value=b.status(id);return typeof value==='string'?JSON.parse(value):value}catch(_){return null}}
function mount(){
 const sheet=document.getElementById('sensorSheet');
 if(!sheet||document.getElementById('avnivoModuleManager'))return;
 const target=sheet.querySelector('.avnivo-mode-content[data-mode="offline"]')||sheet.querySelector('.avnivo-mode-content:last-of-type')||sheet.querySelector('.sensor-card');
 if(!target)return;
 const root=document.createElement('section');root.id='avnivoModuleManager';
 const heading=document.createElement('h3');heading.textContent='Offline modullar';
 const note=document.createElement('p');note.className='module-note';note.textContent='Har bir modul alohida yuklanadi va Android ilova xotirasida saqlanadi. HTML rejimida Android yuklagichi mavjud bo‘lmasa, yuklash faol bo‘lmaydi.';
 root.append(heading,note);
 const refreshers=[];
 modules.forEach(mod=>{
  const card=document.createElement('article');card.className='module-card';
  const head=document.createElement('div');head.className='module-head';
  const name=document.createElement('strong');name.textContent=mod.name;
  const action=document.createElement('button');action.type='button';action.className='module-icon';
  head.append(name,action);
  const size=document.createElement('p');size.className='module-size';size.textContent='Fayl hajmi: '+mod.size;
  const status=document.createElement('div');status.className='module-state';status.setAttribute('role','status');status.setAttribute('aria-live','polite');
  const active=document.createElement('span');active.className='module-active';active.textContent='Faol';active.hidden=true;
  const progress=document.createElement('progress');progress.className='module-progress';progress.max=100;progress.value=0;progress.hidden=true;
  const progressText=document.createElement('div');progressText.className='module-progress-text';progressText.hidden=true;
  const desc=document.createElement('p');desc.className='module-description';desc.textContent=mod.description;
  card.append(head,size,active,status,progress,progressText,desc);root.append(card);
  let installed=false;
  function refresh(){
   const b=bridge(),data=read(mod.id);
   const phase=String(data?.state||'not_installed');
   const supported=!!b&&typeof b.supports==='function'&&b.supports(mod.id)===true;
   installed=!!b&&(phase==='installed'||phase==='active');
   const percent=Number(data?.percent);
   const loading=!!b&&(phase==='downloading'||phase==='activating');
   const activeNow=installed&&(data?.active===true||data?.isActive===true||phase==='active');
   active.hidden=!activeNow;
   action.classList.toggle('is-loading',loading);
   const nextIcon=installed?'delete':loading?'loading':'download';
   if(action.dataset.icon!==nextIcon){action.dataset.icon=nextIcon;action.innerHTML=installed
    ? '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M10 4h4M7 7l1 13h8l1-13M10 11v6M14 11v6"/></svg>'
    : loading
    ? '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3a9 9 0 1 1-9 9"/></svg>'
    : '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 18H6a4 4 0 0 1-.5-7.97A7 7 0 0 1 19 9.5a4.5 4.5 0 0 1-.5 8.5H17"/><path d="M12 11v10m-4-4 4 4 4-4"/></svg>';}
   action.setAttribute('aria-label',installed?mod.name+' modulini o‘chirish':mod.name+' modulini yuklab olish');
   action.title=action.getAttribute('aria-label');
   action.disabled=!b||loading||(!installed&&(!mod.ready||!supported))||(!installed&&typeof b.download!=='function')||(installed&&typeof b.remove!=='function');
   progress.hidden=!loading;progressText.hidden=!loading;
   if(loading){const p=Number.isFinite(percent)?Math.max(0,Math.min(100,percent)):0;progress.value=p;progressText.textContent=phase==='activating'?'Faollashtirilmoqda…':Math.round(p)+'% yuklandi';}
   if(!b)status.textContent='Hozircha faqat ko‘rish mumkin — APK yuklash xizmati ulanmagan';
   else if(installed)status.textContent=activeNow?'':'O‘rnatilgan — faollashtirish kutilmoqda';
   else if(loading)status.textContent=phase==='activating'?'Faollashtirilmoqda…':'Yuklanmoqda…';
   else if(phase==='error')status.textContent='Xato: '+String(data?.message||'Yuklash bajarilmadi');
   else if(!mod.ready)status.textContent='Model fayllari aniqlanmaguncha yuklab bo‘lmaydi';
   else if(!supported)status.textContent='Server fayllari mavjud, ammo Android ijro moduli tasdiqlanmagan';
   else status.textContent='Yuklab olinmagan';
  }
  action.addEventListener('click',()=>{
   const b=bridge();if(!b)return;
   try{
    if(installed){if(!window.confirm(mod.name+' modulini telefondan o‘chirasizmi?'))return;b.remove(mod.id)}
    else if(mod.ready&&typeof b.supports==='function'&&b.supports(mod.id)===true)b.download(mod.id);
    refresh();
   }catch(e){status.textContent='Xato: '+String(e?.message||e)}
  });
  refreshers.push(refresh);refresh();
 });
 target.append(root);
 window.addEventListener('avnivo-offline-module-status',()=>refreshers.forEach(f=>f()));
 sheet.addEventListener('click',()=>{refreshers.forEach(f=>f())});
}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',mount);else mount();
})();
