#!/usr/bin/env node
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const html=readFileSync(new URL('../../index.html',import.meta.url),'utf8');
const scripts=[...html.matchAll(/<script\\b[^>]*>([\\s\\S]*?)<\\/script>/gi)].map(x=>x[1]).filter(x=>x.trim());
assert(scripts.length>0,'No inline scripts');
for(const [index,source] of scripts.entries())new vm.Script(source,{filename:'index.html:inline-script-'+index});
assert.match(html,/async function choose\\(code\\)/,'Onboarding language choice must be async');
assert.match(html,/await api\\.setLanguage\\(code\\|\\|'uz'\\)/,'Language choice must apply selected locale');
assert.match(html,/window\\.avnivoI18nReady=window\\.AVNIVO_I18N\\.init\\(\\)/,'Localization must initialize');
assert.match(html,/tutorial\\.history\\.title/,'Tutorial translation keys absent');
assert.match(html,/id="avnivo-interface-language"/,'Settings language selector absent');
assert.match(html,/id="mode-online"/,'Online mode missing');
assert.match(html,/id="mode-offline"/,'Offline mode missing');
console.log('PASS: '+scripts.length+' inline scripts compile; onboarding, language persistence, tutorial, settings and mode wiring present');
