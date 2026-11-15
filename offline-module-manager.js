/* AVNIVO offline module manager — UI only until Android bridge is implemented. */
(function(){
 'use strict';
 const configs=[
  {id:'whisper-small',title:'Whisper Small Multilingual',kind:'Ovozni matnga aylantirish',size:'466 MiB',url:'https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin'},
  {id:'nllb-int8',title:'NLLB-200 600M INT8',kind:'Matn tarjimasi',size:'taxminan 895 MB',url:null}
 ];
 const css=document.createElement('style');
 css.textContent='.avnivo-module-manager{margin:14px 0;padding:12px;border:1px solid #79684e;border-radius:12px}.avnivo-module-card{border-top:1px solid #6b5742;padding:12px 0}.avnivo-module-card button{padding:9px 12px;border-radius:8px;background:#e7b75b;color:#20180e;border:0;margin:6px 8px 0 0}.avnivo-module-card button:disabled{opacity:.55}.avnivo-module-status{font-size:12px;line-height:1.5;white-space:pre-wrap}.avnivo-module-card progress{width:100%}';
 document.head.append(css);
 function getBridge(){const b=window.AvnivoOfflineModules;return b&&typeof b.download==='function'&&typeof b.status==='function'?b:null}
 function safeStatus(id){try{const raw=getBridge()?.status(id);if(typeof raw==='string')return JSON.parse(raw);if(raw&&typeof raw==='object')return raw}catch(e){}return null}
 function init(){
  const target=document.querySelector('#sensorSheet .avnivo-mode-content[hidden]')||document.querySelector('#sensorSheet .avnivo-mode-content:last-of-type');
  if(!target||document.getElementById('avnivoModuleManager'))return;
  const root=document.createElement('section');root.id='avnivoModuleManager';root.className='avnivo-module-manager';
  const h=document.createElement('h3');h.textContent='Offline modullar';root.append(h);
  const note=document.createElement('p');note.textContent='APK uchun tayyorlanmoqda. Modelni yuklash va ishlatish uchun Android ilovasida mahalliy modul xizmati zarur. HTML sahifada model o‘rnatildi deb ko‘rsatilmaydi.';root.append(note);
  for(const c of configs){
   const card=document.createElement('div');card.className='avnivo-module-card';
   const title=document.createElement('strong');title.textContent=c.title;
   const detail=document.createElement('p');detail.textContent=c.kind+' · '+c.size;
   const state=document.createElement('div');state.className='avnivo-module-status';state.setAttribute('role','status');state.setAttribute('aria-live','polite');
   const progress=document.createElement('progress');progress.max=100;progress.value=0;progress.hidden=true;
   const download=document.createElement('button');download.type='button';download.textContent='Yuklab olish';
   const remove=document.createElement('button');remove.type='button';remove.textContent='O‘chirish';
   function refresh(){
    const bridge=getBridge(),s=safeStatus(c.id);
    if(!bridge){state.textContent='HTML rejimi: APK modul yuklagichi hali ulanmagan.';download.disabled=true;remove.disabled=true;progress.hidden=true;return}
    const phase=String(s?.state||'not_installed');
    const pct=Number(s?.percent);
    state.textContent=phase==='installed'?'O‘rnatilgan':phase==='downloading'?'Yuklanmoqda'+(Number.isFinite(pct)?': '+Math.max(0,Math.min(100,Math.round(pct)))+'%':''):phase==='error'?'Xato: '+String(s?.message||'noma’lum'):'O‘rnatilmagan';
    progress.hidden=phase!=='downloading'||!Number.isFinite(pct);
    if(!progress.hidden)progress.value=Math.max(0,Math.min(100,pct));
    download.disabled=phase==='installed'||phase==='downloading';remove.disabled=phase==='not_installed';
   }
   download.addEventListener('click',()=>{try{getBridge()?.download(c.id);state.textContent='Yuklash so‘rovi yuborildi. Holatni yangilang.'}catch(e){state.textContent='Yuklash xatosi: '+e.message}});
   remove.addEventListener('click',()=>{if(!confirm(c.title+' modulini o‘chirasizmi?'))return;try{if(typeof getBridge()?.remove!=='function')throw Error('APK o‘chirish xizmati ulanmagan');getBridge().remove(c.id);refresh()}catch(e){state.textContent=e.message}});
   const refreshButton=document.createElement('button');refreshButton.type='button';refreshButton.textContent='Holatni yangilash';refreshButton.addEventListener('click',refresh);
   card.append(title,detail,state,progress,download,remove,refreshButton);root.append(card);refresh();
  }
  target.append(root);
 }
 if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',init);else init();
 window.addEventListener('avnivo-offline-module-status',()=>{document.querySelectorAll('#avnivoModuleManager button').forEach(b=>{if(b.textContent==='Holatni yangilash')b.click()})});
})();
