import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {localizedServerMessage} from './errorMessage.ts';
import {createTranslator} from '../i18n/translator.ts';
const root=new URL('../../../../',import.meta.url);
const domains=[
  ['app.error.','simplepoint-plugins/simplepoint-plugin-app/simplepoint-plugin-app-form-api/src/main/java/org/simplepoint/plugin/app/form/api/error/ApplicationFormErrorCode.java'],
  ['dna.runtime.error.','simplepoint-plugins/simplepoint-plugin-dna/simplepoint-plugin-dna-runtime-api/src/main/java/org/simplepoint/plugin/dna/runtime/api/error/DnaRuntimeErrorCode.java'],
];
for(const locale of ['zh-CN','en-US']) test(`all form and DNA domain errors have actionable ${locale} translations`,()=>{
  const messages=JSON.parse(fs.readFileSync(new URL(`./simplepoint-services/simplepoint-service-common/src/main/resources/META-INF/simplepoint/i18n/${locale}/common.json`,root),'utf8'));
  const translate=createTranslator(messages);
  for(const [prefix,file] of domains){
    const source=fs.readFileSync(new URL('./'+file,root),'utf8');
    const codes=[...source.matchAll(/^  ([A-Z][A-Z_]+)(?:[,;]|\(Dna|$)/gm)].map(m=>m[1]);
    assert.ok(codes.length>20);
    for(const code of codes){
      const key=prefix+code;
      assert.ok(messages[key],key);
      assert.equal(localizedServerMessage({code,message:'Raw English diagnostic',messageKey:key},translate),messages[key]);
    }
  }
});
