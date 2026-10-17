import test from 'node:test';
import assert from 'node:assert/strict';
import {runtimeStatus} from './status.ts';
const t=(_key,fallback)=>fallback;
test('finished and unknown statuses never look like running operations',()=>{
  assert.equal(runtimeStatus('REJECTED',t).tone,'error');
  assert.equal(runtimeStatus('RETIRED',t).tone,'neutral');
  assert.equal(runtimeStatus('SKIPPED',t).tone,'neutral');
  assert.deepEqual(runtimeStatus('FUTURE_STATUS',t),{label:'FUTURE_STATUS',tone:'neutral'});
  assert.equal(runtimeStatus('MIGRATING',t).tone,'processing');
});
