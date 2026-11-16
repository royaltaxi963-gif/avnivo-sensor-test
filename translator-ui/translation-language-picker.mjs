/* AVNIVO translation-only picker. Not imported by production until reviewed.
   Language availability is supplied by the backend; catalog entries alone are NOT authorization. */
export const DEFAULT_LANGUAGES = Object.freeze(['uz','ru','en','tr']);
export const FAVORITE_LANGUAGES = Object.freeze(['uz','ru','en','ar','hi','bn','tr','fr','de','es']);
const RECENT_KEY = 'avnivo.online.translation.recent.v1';

export function languageLabel(code, displayLocale='uz') {
  try {
    const locale = new Intl.Locale(code);
    const names = new Intl.DisplayNames([displayLocale], {type:'language'});
    return names.of(locale.baseName) || code;
  } catch (_) { return code; }
}
export function reciprocalLabel(code, oppositeCode) {
  return languageLabel(code,code) + ' ≈ ' + languageLabel(code,oppositeCode);
}
export function groupByBaseLanguage(entries) {
  const groups = new Map();
  for (const entry of entries) {
    if (!entry || typeof entry.code !== 'string') continue;
    let base;
    try { base = new Intl.Locale(entry.code).language; } catch (_) { continue; }
    if (!groups.has(base)) groups.set(base,[]);
    groups.get(base).push(entry);
  }
  return [...groups.entries()].map(([base,variants])=>({base,variants}));
}
export function filterTranslationLanguages(catalog, {query='', enabledCodes=[], includeExperimental=false, displayLocale='uz'}={}) {
  const enabled = new Set(enabledCodes);
  const q=query.trim().toLocaleLowerCase();
  return (catalog?.languages||[])
    .filter(item=>item && typeof item.code==='string')
    .filter(item=>includeExperimental || item.support!=='experimental')
    .map(item=>({...item,enabled:enabled.has(item.code),
      nativeName:languageLabel(item.code,item.code),
      displayName:languageLabel(item.code,displayLocale)}))
    .filter(item=>!q || [item.code,item.nativeName,item.displayName,languageLabel(item.code,'en'),languageLabel(item.code,'ru')].some(v=>v.toLocaleLowerCase().includes(q)));
}
export function readRecent(storage, allowedCodes) {
  try {
    const list=JSON.parse(storage.getItem(RECENT_KEY)||'[]');
    const allowed=new Set(allowedCodes);
    return Array.isArray(list)?list.filter(c=>typeof c==='string'&&allowed.has(c)).slice(0,8):[];
  } catch (_) { return []; }
}
export function saveRecent(storage, code, allowedCodes) {
  if (!allowedCodes.includes(code)) return false;
  try {
    const list=[code,...readRecent(storage,allowedCodes).filter(c=>c!==code)].slice(0,8);
    storage.setItem(RECENT_KEY,JSON.stringify(list));
    return true;
  } catch (_) { return false; }
}
export function renderTranslationPicker(root, catalog, {enabledCodes=DEFAULT_LANGUAGES, displayLocale='uz', oppositeCode='ru', onSelect=()=>{}, storage=window.localStorage}={}) {
  const allowed=new Set(enabledCodes);
  root.replaceChildren();
  const search=document.createElement('input');
  search.type='search'; search.placeholder='Tilni qidirish / Search language';
  search.setAttribute('aria-label','Tarjima tilini qidirish');
  const recent=document.createElement('div'), list=document.createElement('div');
  root.append(search,recent,list);
  function buttonFor(item) {
    const btn=document.createElement('button');
    btn.type='button';
    btn.textContent=item.nativeName+' ≈ '+languageLabel(item.code,oppositeCode)+(item.enabled?'':' (hali yoqilmagan)');
    btn.disabled=!item.enabled;
    btn.dataset.languageCode=item.code;
    btn.addEventListener('click',()=>{saveRecent(storage,item.code,[...allowed]);onSelect(item.code);refresh();});
    return btn;
  }
  function refresh(){
    const items=filterTranslationLanguages(catalog,{query:search.value,enabledCodes,displayLocale});
    const recentCodes=readRecent(storage,enabledCodes);
    const byCode=new Map(items.map(item=>[item.code,item]));
    recent.replaceChildren();list.replaceChildren();
    if(!search.value.trim()){
      const title=document.createElement('strong');title.textContent='Oxirgi tanlanganlar';
      const recentItems=recentCodes.map(c=>byCode.get(c)).filter(Boolean);
      if(recentItems.length){recent.append(title,...recentItems.map(buttonFor));}
    }
    const favorites=FAVORITE_LANGUAGES.map(c=>byCode.get(c)).filter(Boolean);
    if(!search.value.trim()&&favorites.length){
      const title=document.createElement('strong');title.textContent='Asosiy tillar';
      list.append(title,...favorites.map(buttonFor));
    }
    const grouped=groupByBaseLanguage(items);
    for(const group of grouped){
      const details=document.createElement('details');
      const summary=document.createElement('summary');
      summary.textContent=languageLabel(group.base,displayLocale)+' ('+group.variants.length+')';
      details.append(summary,...group.variants.map(buttonFor));
      list.append(details);
    }
  }
  search.addEventListener('input',refresh);
  refresh();
  return {refresh,destroy(){root.replaceChildren();}};
}
