import assert from 'node:assert/strict';
import test from 'node:test';
import {pageRoutePath, pageTabIdentity, pageViewLocation, pageViewSelection} from './viewIdentity.ts';

const route = '/app/runtime/sales/trades';
const view = {version: 1, kind: 'form-reference', identity: ['trade', '3', 'customer', 'record']};
const location = pageViewLocation(route, view);

test('ordinary filters and anchors still share their resource tab', () => {
  for (const url of [route, `${route}?filter=active`, `#${route}?page=2#row`]) {
    assert.equal(pageTabIdentity(url), route);
    assert.equal(pageRoutePath(url), route);
    assert.deepEqual(pageViewSelection(url), {state: 'none'});
  }
});

test('explicit views retain distinct identities without replacing an ordinary draft', () => {
  assert.notEqual(pageTabIdentity(location), pageTabIdentity(route));
  assert.notEqual(pageTabIdentity(location), pageTabIdentity(pageViewLocation(route, {...view, identity: [...view.identity.slice(0, 3), 'another']})));
  assert.equal(pageTabIdentity(`${location}&filter=active#row`), location);
  const reordered = new URLSearchParams({filter: 'active', 'sp-view': JSON.stringify({identity: view.identity, kind: view.kind, version: 1})});
  assert.equal(pageTabIdentity(`${route}?${reordered}`), location);
  assert.deepEqual(pageViewSelection(location), {state: 'view', view});
});

test('malformed, ambiguous and unsupported views cannot overwrite the ordinary tab', () => {
  const malformed = ['null', '[]', '{}', '{', JSON.stringify({...view, version: 2}),
    JSON.stringify({...view, unexpected: true}), JSON.stringify({...view, identity: []}),
    JSON.stringify({...view, identity: ['../private']}), JSON.stringify({...view, kind: 'https://outside'}),
    JSON.stringify({...view, identity: Array(7).fill('id')}), JSON.stringify({...view, identity: ['a'.repeat(129)]}), 'a'.repeat(1025)];
  for (const value of malformed) {
    const invalid = `${route}?${new URLSearchParams({'sp-view': value})}`;
    assert.deepEqual(pageViewSelection(invalid), {state: 'invalid'});
    assert.notEqual(pageTabIdentity(invalid), route);
  }
  assert.deepEqual(pageViewSelection(`${location}&sp-view=${encodeURIComponent(JSON.stringify(view))}`), {state: 'invalid'});
});

test('view creation refuses external destinations and preexisting query parameters', () => {
  for (const route of ['//outside/path', 'https://outside/path', '/path?next=outside', '/path#part']) {
    assert.throws(() => pageViewLocation(route, view));
  }
});
