import test from 'node:test';
import assert from 'node:assert/strict';
import {createPreferenceSession} from './session.ts';
const deferred = () => {let resolve, reject; const promise = new Promise((a,b) => {resolve=a;reject=b;}); return {promise,resolve,reject};};
const tick = () => new Promise(resolve => setImmediate(resolve));

test('late account settings never overwrite a new local edit; writes serialize to the final value', async () => {
  const read=deferred(), first=deferred(), writes=[], changes=[];
  const session=createPreferenceSession({initial:'default',current:()=>true,load:()=>read.promise,
    save:async value=>{writes.push(value);if(writes.length===1)await first.promise;},
    change:(v,d)=>changes.push([v,d]),failure:()=>{}});
  const loading=session.load(); session.update('cards'); const saving=session.retry();
  session.update('table'); read.resolve('old-server'); await loading;
  assert.equal(changes.some(([v])=>v==='old-server'),false);
  assert.deepEqual(writes,['cards']); first.resolve(); await saving; await tick();
  assert.deepEqual(writes,['cards','table']); assert.deepEqual(changes.at(-1),['table',false]);session.dispose();
});
test('a response from a closed owner cannot commit or write', async () => {
  const read=deferred(); const changes=[],writes=[];
  const session=createPreferenceSession({initial:0,current:()=>true,load:()=>read.promise,
    save:async v=>writes.push(v),change:(...v)=>changes.push(v),failure:()=>{}});
  const loading=session.load(); session.update(1);session.dispose(); read.resolve(99);await loading;await session.retry();
  assert.deepEqual(changes,[[1,true]]);assert.deepEqual(writes,[]);
});
test('failed synchronization retains edits and retries without replacing them with server defaults', async () => {
  let fail=true;const changes=[],failures=[];
  const session=createPreferenceSession({initial:'dirty-cache',dirty:true,current:()=>true,load:async()=>'server',
    save:async()=>{if(fail)throw Error('offline');},change:(...v)=>changes.push(v),failure:v=>failures.push(v)});
  await session.load();await tick();assert.equal(failures.at(-1),true);
  fail=false;await session.retry();assert.deepEqual(changes.at(-1),['dirty-cache',false]);session.dispose();
});
test('loading an empty account does not write defaults; scope changes fence pending work', async () => {
  let current=true; const writes=[];const read=deferred();
  const session=createPreferenceSession({initial:{},current:()=>current,load:()=>read.promise,
    save:async v=>writes.push(v),change:()=>{},failure:()=>{}});
  const loading=session.load();current=false;read.resolve({mode:'cards'});await loading;
  session.update({mode:'table'});await session.retry();assert.deepEqual(writes,[]);session.dispose();
});
