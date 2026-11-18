/* AVNIVO dynamic i18n bindings.
 * Include after avnivo-i18n.js, then call AVNIVO_DYNAMIC_I18N.start().
 * New UI text must carry data-i18n or data-i18n-{aria-label,title,placeholder}.
 * Translates new settings/dialog nodes automatically using the selected
 * language's verified dictionary. Does NOT machine-translate arbitrary content,
 * chat messages, user input or text without a localization key.
 */
(function(global){
 'use strict';
 var observer=null,scheduled=false;
 function localize(){
  scheduled=false;
  if(global.AVNIVO_I18N&&typeof global.AVNIVO_I18N.apply==='function'){
   global.AVNIVO_I18N.apply();
  }
 }
 function schedule(){
  if(scheduled)return;
  scheduled=true;
  Promise.resolve().then(localize);
 }
 function hasBindings(node){
  if(!node||node.nodeType!==1)return false;
  return node.matches('[data-i18n],[data-i18n-aria-label],[data-i18n-title],[data-i18n-placeholder]')||
    !!node.querySelector('[data-i18n],[data-i18n-aria-label],[data-i18n-title],[data-i18n-placeholder]');
 }
 function start(){
  if(observer||!global.document||!global.MutationObserver)return;
  observer=new MutationObserver(function(records){
   for(var i=0;i<records.length;i++){
    var record=records[i];
    if(record.type==='attributes'){
     if(hasBindings(record.target)){schedule();return}
    }else{
     for(var j=0;j<record.addedNodes.length;j++){
      if(hasBindings(record.addedNodes[j])){schedule();return}
     }
    }
   }
  });
  observer.observe(global.document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['data-i18n','data-i18n-aria-label','data-i18n-title','data-i18n-placeholder','data-i18n-vars']});
  schedule();
 }
 function stop(){if(observer)observer.disconnect();observer=null;scheduled=false}
 global.AVNIVO_DYNAMIC_I18N={start:start,stop:stop,refresh:schedule};
})(typeof window!=='undefined'?window:this);
