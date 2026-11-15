import test from 'node:test';
import assert from 'node:assert/strict';
import {filterTranslationLanguages,groupByBaseLanguage,readRecent,saveRecent,reciprocalLabel} from './translation-language-picker.mjs';
const catalog={languages:[
 {code:'uz',support:'official'}, {code:'ru',support:'official'},
 {code:'en-US',support:'official'}, {code:'en-GB',support:'official'},
 {code:'am',support:'experimental'}]};
test('search is case insensitive and includes native/translated labels',()=>{
 assert.deepEqual(filterTranslationLanguages(catalog,{query:'en-',enabledCodes:['uz']}).map(x=>x.code),['en-US','en-GB']);
});
test('experimental entries hidden by default',()=>{
 assert.equal(filterTranslationLanguages(catalog).some(x=>x.code==='am'),false);
 assert.equal(filterTranslationLanguages(catalog,{includeExperimental:true}).some(x=>x.code==='am'),true);
});
test('catalog never automatically enables languages',()=>{
 const items=filterTranslationLanguages(catalog,{enabledCodes:['uz','ru']});
 assert.equal(items.find(x=>x.code==='en-US').enabled,false);
 assert.equal(items.find(x=>x.code==='uz').enabled,true);
});
test('regional variants are grouped',()=>{
 const groups=groupByBaseLanguage(catalog.languages);
 assert.deepEqual(groups.find(x=>x.base==='en').variants.map(x=>x.code),['en-US','en-GB']);
});
test('recent selections are restricted to enabled languages and deduplicated',()=>{
 const data=new Map();const store={getItem:k=>data.get(k)||null,setItem:(k,v)=>data.set(k,v)};
 assert.equal(saveRecent(store,'am',['uz','ru']),false);
 assert.equal(saveRecent(store,'uz',['uz','ru']),true);
 assert.equal(saveRecent(store,'ru',['uz','ru']),true);
 assert.equal(saveRecent(store,'uz',['uz','ru']),true);
 assert.deepEqual(readRecent(store,['uz','ru']),['uz','ru']);
});
test('reciprocal labels include separator',()=>{
 assert.match(reciprocalLabel('ru','uz'),/ ≈ /);
});
