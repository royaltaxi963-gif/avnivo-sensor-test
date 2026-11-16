#!/usr/bin/env node
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const html=readFileSync(new URL('../../index.html',import.meta.url),'utf8');
const scripts=Array.from(html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi),m=>m[1]).filter(Boolean);
assert(scripts.length>0,'No inline scripts');
for(const [i,source] of scripts.entries())new vm.Script(source,{filename:'inline-'+i});
assert(html.includes('async function choose(code)'), 'Onboarding picker must be async');
assert(html.includes('await api.setLanguage(code'), 'Picker must apply language');
assert(html.includes('window.avnivoI18nReady=window.AVNIVO_I18N.init()'),'i18n not initialized');
for(const key of ['tutorial.history.title','tutorial.microphone.body','tutorial.offline.body'])assert(html.includes(key),key+' absent');
for(const id of ['avnivo-interface-language','mode-online','mode-offline'])assert(html.includes('id="'+id+'"'),id+' absent');
console.log('PASS '+scripts.length+' inline scripts compile; onboarding and mode wiring present');
