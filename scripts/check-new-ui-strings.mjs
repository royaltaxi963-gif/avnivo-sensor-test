#!/usr/bin/env node
/**
 * Detect new UI text between two generated audit JSON reports.
 * Example: node scripts/check-new-ui-strings.mjs old.json new.json
 * Exit 1 when unreviewed new text is detected. Does not claim to translate it.
 */
import {readFileSync} from 'node:fs';
const [previous,current]=process.argv.slice(2);
if(!previous||!current){console.error('Usage: node scripts/check-new-ui-strings.mjs previous.json current.json');process.exit(2)}
const old=JSON.parse(readFileSync(previous,'utf8'));
const now=JSON.parse(readFileSync(current,'utf8'));
const key=r=>r.category+'\u0000'+r.text;
const known=new Set((old.candidates||[]).map(key));
const added=(now.candidates||[]).filter(r=>!known.has(key(r)));
const oldTutorial=new Set((old.tutorialSteps||[]).map(x=>x.selector+'\u0000'+x.title+'\u0000'+x.body));
const changedTutorial=(now.tutorialSteps||[]).filter(x=>!oldTutorial.has(x.selector+'\u0000'+x.title+'\u0000'+x.body));
if(added.length||changedTutorial.length){
 console.error('New/changed UI text requires localization into all supported languages:');
 for(const r of added)console.error('  '+r.category+' line '+r.line+': '+r.text.slice(0,160));
 for(const r of changedTutorial)console.error('  tutorial '+r.selector+': '+r.title);
 console.error('Add stable i18n keys, translated bundles and runtime bindings; do not silently fall back.');
 process.exit(1);
}
console.log('PASS: no new UI string candidates or tutorial changes.');
