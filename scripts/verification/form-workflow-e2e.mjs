import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {writeFileSync} from 'node:fs';
import https from 'node:https';
import {isIP} from 'node:net';
import {setTimeout as delay} from 'node:timers/promises';
// Credentials are read from the invoking process, never written to the evidence file.
const origin = process.env.SIMPLEPOINT_VERIFY_ORIGIN;
const token = process.env.SIMPLEPOINT_VERIFY_TOKEN;
const cookie = process.env.SIMPLEPOINT_VERIFY_COOKIE;
const tenant = process.env.SIMPLEPOINT_VERIFY_TENANT_ID;
const context = process.env.SIMPLEPOINT_VERIFY_CONTEXT_ID;
const output = process.argv[2];
assert(origin && (Boolean(token) !== Boolean(cookie)) && tenant && context && output,
  'Required: SIMPLEPOINT_VERIFY_ORIGIN, TOKEN or COOKIE, TENANT_ID, CONTEXT_ID and an output JSON path');
const target = new URL(origin);
assert(target.protocol === 'https:' && !target.username && !target.password && target.pathname === '/',
  'Use an HTTPS origin without credentials or a path');
const headers = {...(token ? {'Authorization': 'Bearer ' + token} : {'Cookie': cookie}), 'X-Tenant-Id': tenant, 'X-Context-Id': context};
async function request(path, {method = 'GET', body, headers: supplied} = {}) {
  assert(path.startsWith('/') && !path.startsWith('//'));
  const address = process.env.SIMPLEPOINT_VERIFY_CONNECT_ADDRESS;
  if (address) assert(isIP(address), 'CONNECT_ADDRESS must be an explicit IPv4 or IPv6 address');
  return new Promise((resolve, reject) => {
    const payload = body === undefined ? undefined : JSON.stringify(body);
    const req = https.request(new URL(path, target), {method,
      headers: {...headers, ...supplied, ...(payload === undefined ? {} : {
        'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload)})},
      ...(address ? {lookup: (_host, options, callback) => options.all
        ? callback(null, [{address, family: isIP(address)}]) : callback(null, address, isIP(address))} : {}),
    }, response => {
      const chunks = []; let bytes = 0;
      response.on('data', chunk => {bytes += chunk.length;
        if (bytes > 16 * 1024 * 1024) response.destroy(new Error('Verification response exceeds 16 MiB'));
        else chunks.push(chunk);});
      response.on('error', reject);
      response.on('end', () => resolve({status: response.statusCode, body: Buffer.concat(chunks).toString(), path}));
    });
    req.setTimeout(90000, () => req.destroy(new Error('Verification request timed out')));
    req.on('error', reject); req.end(payload);
  });
}
function data(response) {
  assert.equal(response.status, 200, `HTTP ${response.status} ${response.path}: ${response.body.slice(0, 400)}`);
  const result = JSON.parse(response.body);
  return Object.hasOwn(result, 'data') ? result.data : result;
}
const call=async(path,method='GET',body)=>data(await request(path,{method,body,headers}));
const evidence={startedAt:new Date().toISOString(),scenarios:[],checks:[]};
const save=()=>writeFileSync(output,JSON.stringify(evidence,null,2),{mode:0o600});
const pass=label=>{evidence.checks.push(label);save();console.log('PASS: '+label);};
const field=(id,type)=>({fieldId:id,storageCode:'f_'+id,label:id,storageLabel:id,type,storageMode:'STORED',submitRequired:true,storageRequired:false,length:type==='STRING'?150:null,precision:type==='DECIMAL'?24:null,scale:type==='DECIMAL'?2:null,choices:[]});
const node=(id,type)=>({componentId:id,componentType:type,componentVersion:'1',columnSpan:24,readOnly:false,properties:{},children:[]});
async function scenario(code,name,fields,xml,variables,mappings){
 const created=await call('/app/applications','POST',{name:name+' '+new Date().toISOString(),description:'正式闭环场景验证',mainPageName:name});
 const appId=created.application.id,pageId=created.pages[0].id;
 const form=await call(`/app/applications/${appId}/forms`,'POST',{code,name});
 const base=`/app/applications/${appId}/forms/${form.id}`,runtime=`/app/runtime/applications/${appId}/pages/${pageId}`;
 let draft=await call(base+'/draft');const document=structuredClone(draft.document);document.fields=fields;
 document.scenes[0].root.children=[...fields.map(f=>({...node('input_'+f.fieldId,f.type==='STRING'?'field.text':f.type==='BOOLEAN'?'field.boolean':'field.number'),fieldId:f.fieldId})),node('submit','action.submit')];
 draft=await call(base+'/draft','PUT',{expectedRevision:draft.revision,document});const version=await call(base+'/versions','POST',{expectedRevision:draft.revision});
 const key=randomUUID();let active=false;for(let i=0;i<30;i++){const release=await call(base+`/versions/${version.version}/publication`,'POST',{pageId,idempotencyKey:key});if(release.pageBinding?.status==='ACTIVE'){active=true;break;}assert(!['FAILED','CANCELLED'].includes(release.pageBinding?.status),JSON.stringify(release));await delay(1000);}assert(active);
 const keyName=code+'_'+Date.now();const process=await call('/workflow/definitions','POST',{key:keyName,name,category:'engine-acceptance'});
 const contract={schemaVersion:1,variables,startBinding:{provider:'simplepoint-app',formId:form.id,version:version.version,variableMappings:mappings,sceneId:document.scenes[0].sceneId},taskBindings:{}};
 await call('/workflow/definitions/'+process.definition.id+'/draft','PUT',{expectedRevision:1,name,bpmnXml:xml?xml.replaceAll('PROCESS_KEY',keyName):process.bpmnXml,formContract:contract});
 await call('/workflow/definitions/'+process.definition.id+'/publish','POST',{expectedRevision:2});
 const fixture={appId,pageId,formId:form.id,formVersion:version.version,definitionId:process.definition.id,runtime,submissions:[]};evidence.scenarios.push(fixture);save();return fixture;
}
const submit=async(f,values,extra={})=>{
 const body={runtime:{formId:f.formId,formVersion:f.formVersion},idempotencyKey:randomUUID(),definitionId:f.definitionId,definitionVersion:1,title:'真实场景验证 '+randomUUID().slice(0,8),values,variables:{},...extra};
 let row=await call(f.runtime+'/submissions','POST',body);for(let i=0;!row.processInstanceId&&i<100;i++){assert.notEqual(row.status,'ATTENTION',JSON.stringify(row));await delay(1000);row=await call(f.runtime+'/submissions/'+row.id);}assert(row.processInstanceId,JSON.stringify(row));f.submissions.push(row);save();return row;
};
const task=async(id)=>{const rows=await call('/workflow/tasks/pending?size=100');const found=rows.content.find(t=>t.processInstanceId===id);assert(found);return found;};
const customer=await scenario('customer','客户档案变更审批',[field('name','STRING'),field('contact','STRING')],null,[],{});
const original=await call(customer.runtime+'/records','POST',{runtime:{formId:customer.formId,formVersion:customer.formVersion},idempotencyKey:randomUUID(),state:'SUBMITTED',values:{name:'客户・顧客',contact:'原联系人'}});
const record=await call(customer.runtime+'/records/'+original.recordId);
const changed=await submit(customer,{name:'客户・顧客',contact:'新联系人'},{recordId:record.id,expectedVersion:record.version});
assert.equal(changed.recordId,record.id);assert.equal(changed.recordVersion,record.version+1);
const captured=await call(customer.runtime+'/submissions/'+changed.id+'/snapshot');assert.equal(captured.record.values.contact,'新联系人');
const stale=await request(customer.runtime+'/submissions',{method:'POST',headers,body:{runtime:{formId:customer.formId,formVersion:customer.formVersion},idempotencyKey:randomUUID(),recordId:record.id,expectedVersion:record.version,definitionId:customer.definitionId,definitionVersion:1,title:'过期变更不得受理',values:{contact:'冲突修改'},variables:{}}});
assert.equal(stale.status,409,stale.body);
const customerTask=await task(changed.processInstanceId);assert.equal((await call('/workflow/tasks/'+customerTask.id+'/complete','POST',{variables:{},comment:'核实客户资料',idempotencyKey:randomUUID()})).status,'COMPLETED');
pass('customer update approval reuses the existing record, captures the new version and rejects stale optimistic updates before accepting an intent');
const xml=`<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://simplepoint.org/workflow">
<process id="PROCESS_KEY" name="巡检分支审批" isExecutable="true">
<startEvent id="start"/><sequenceFlow id="begin" sourceRef="start" targetRef="route"/>
<exclusiveGateway id="route" default="normalFlow"/>
<sequenceFlow id="highFlow" sourceRef="route" targetRef="high"><conditionExpression xsi:type="tFormalExpression"><![CDATA[\${risk > 9007199254740993}]]></conditionExpression></sequenceFlow>
<sequenceFlow id="normalFlow" sourceRef="route" targetRef="normal"/>
<userTask id="high" name="异常复核" flowable:assignee="\${initiator}"/><userTask id="normal" name="常规复核" flowable:assignee="\${initiator}"/>
<sequenceFlow id="highEnd" sourceRef="high" targetRef="end"/><sequenceFlow id="normalEnd" sourceRef="normal" targetRef="end"/><endEvent id="end"/>
</process></definitions>`;
const inspection=await scenario('inspection','巡检风险分支',[field('name','STRING'),field('risk','DECIMAL')],xml,[{name:'risk',label:'风险值',type:'decimal',scope:'both',required:true}],{risk:'risk'});
for(const [risk,expected] of [['9007199254740993.01','异常复核'],['9007199254740993.00','常规复核']]){
 const submitted=await submit(inspection,{name:'设备巡检・点検',risk});const work=await task(submitted.processInstanceId);assert.equal(work.name,expected);
 assert.equal((await call(inspection.runtime+'/submissions/'+submitted.id+'/snapshot')).record.values.risk,risk.replace(/0+$/, '').replace(/\.$/, ''));
 const complete=await call('/workflow/tasks/'+work.id+'/complete','POST',{variables:{},comment:expected+'完成',idempotencyKey:randomUUID()});assert.equal(complete.status,'COMPLETED');
}
pass('real inspection gateway selects distinct branches across a 0.01 difference beyond JavaScript safe integer precision');
evidence.completedAt=new Date().toISOString();save();
