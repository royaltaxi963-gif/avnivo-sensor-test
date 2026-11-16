import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';
import {filterTranslationLanguages,groupByBaseLanguage} from './translation-language-picker.mjs';
const catalog=JSON.parse(readFileSync(new URL('../config/translation-llm-supported-languages.json',import.meta.url),'utf8'));
assert.equal(catalog.model,'general/translation-llm');
assert.ok(Array.isArray(catalog.languages));
const codes=catalog.languages.map(x=>x.code);
assert.equal(new Set(codes).size,codes.length,'duplicate codes');
for(const item of catalog.languages){
 assert.match(item.code,/^[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/,'invalid locale code: '+item.code);
 assert.ok(['official','experimental'].includes(item.support));
 assert.equal(item.enabled_in_avnivo,false,'catalog alone must not enable a language');
}
const visible=filterTranslationLanguages(catalog);
assert.equal(visible.length,catalog.languages.filter(x=>x.support==='official').length);
assert.ok(visible.every(x=>!x.enabled),'no language should be enabled by catalog alone');
const groups=groupByBaseLanguage(visible);
assert.ok(groups.length>0);
console.log('Validated '+codes.length+' catalog locale codes; '+visible.length+' non-experimental; '+groups.length+' base groups. No languages auto-enabled.');
