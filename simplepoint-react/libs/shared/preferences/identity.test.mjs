import test from 'node:test';
import assert from 'node:assert/strict';
import {tablePreferenceIdentity} from './identity.ts';
test('retained HashRouter owners with identical columns have distinct automatic identities',()=>{
  assert.notEqual(tablePreferenceIdentity(undefined,'/page/a','id|name'), tablePreferenceIdentity(undefined,'/page/b','id|name'));
  assert.equal(tablePreferenceIdentity('resource','/old','id|name'),tablePreferenceIdentity('resource','/new','id|name|new'));
});
