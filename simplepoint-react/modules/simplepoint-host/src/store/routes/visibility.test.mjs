import assert from 'node:assert/strict';
import test from 'node:test';
import {hasVisibleNavigation} from './visibility.ts';

test('directories with only hidden compatibility pages cannot become navigable links', () => {
  for (const directory of [{routeKind:'submenu',path:'/workflow'}, {type:'GROUP',code:'no-url'}, {type:'MODULE',path:'/module'}]) {
    assert.equal(hasVisibleNavigation(directory,0),false);
    assert.equal(hasVisibleNavigation(directory,1),true);
  }
});
test('visibility does not expose disabled or hidden subtrees and keeps real pages', () => {
  assert.equal(hasVisibleNavigation({routeKind:'hidden',path:'/legacy'},2),false);
  assert.equal(hasVisibleNavigation({disabled:true,path:'/unavailable'},2),false);
  assert.equal(hasVisibleNavigation({path:'/page',component:'common/Page'},0),true);
  assert.equal(hasVisibleNavigation({component:'external:https://example.com'},0),true);
  assert.equal(hasVisibleNavigation({},0),false);
});
