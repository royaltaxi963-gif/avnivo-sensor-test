#!/usr/bin/env node
/**
 * AVNIVO current single-file UI string inventory.
 * Run: node scripts/audit-ui-strings.mjs onboarding-tutorial-preview.html
 * No external packages, no network, read-only.
 * This is a candidate inventory, NOT proof of complete localization.
 */
import {readFileSync} from 'node:fs';
const path=process.argv[2]||'onboarding-tutorial-preview.html';
const html=readFileSync(path,'utf8');
const records=[];
function add(category,value,offset){
 const text=String(value).replace(/\\n/g,' ').trim();
 if(!text||!/[A-Za-z\u0400-\u04ff\u0600-\u06ff\u00c0-\u024f]/u.test(text))return;
 const line=html.slice(0,offset).split('\n').length;
 records.push({category,line,text});
}
for(const m of html.matchAll(/\b(?:aria-label|title|placeholder|alt|data-tooltip)\s*=\s*(["'])(.*?)\1/gs))add('html-attribute',m[2],m.index);
for(const m of html.matchAll(/\b(?:textContent|innerText|placeholder|ariaLabel)\s*=\s*(["'])(.*?)\1/gs))add('js-assignment',m[2],m.index);
for(const m of html.matchAll(/\b(?:hint|alert|confirm|onlineState)\s*\(\s*(["'])(.*?)\1/gs))add('js-notice',m[2],m.index);
for(const m of html.matchAll(/\b(?:title|body)\s*:\s*(["'])(.*?)\1/gs))add('object-label',m[2],m.index);
const seen=new Set();
const unique=records.filter(r=>{const key=r.category+'|'+r.text;if(seen.has(key))return false;seen.add(key);return true});
const tutorial=(html.match(/const tutorialSteps=\[([\s\S]*?)\n\];/)||[])[1]||'';
const tutorialSteps=[...tutorial.matchAll(/\{selector:'([^']+)',title:'([^']+)',body:'([^']+)'/g)].map(m=>({selector:m[1],title:m[2],body:m[3]}));
const output={file:path,generatedAt:new Date().toISOString(),summary:{candidates:records.length,uniqueCandidates:unique.length,tutorialSteps:tutorialSteps.length,hasRuntimeI18n:html.includes('AVNIVO_I18N')||/data-i18n(?:=|-)/.test(html)},tutorialSteps,candidates:unique,limitations:['Regex-based inventory; does not cover every DOM text node, template literal, concatenated runtime string or external module.','This report does not certify translated text or language accuracy.']};
process.stdout.write(JSON.stringify(output,null,2)+'\n');
