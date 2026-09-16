import assert from 'node:assert/strict';
import test from 'node:test';
import {
  classifySessionProbeResponse,
  isAuthenticationRedirectResponse,
} from './sessionProbe.ts';

const response = (overrides = {}) => ({
  headers: new Headers({'content-type': 'application/json'}),
  ok: true,
  redirected: false,
  status: 200,
  type: 'basic',
  url: 'http://home.simplepoint.online/userinfo',
  ...overrides,
});

test('classifies explicit authentication responses as an inactive session', () => {
  assert.equal(classifySessionProbeResponse(response({status: 401, ok: false})), 'inactive');
  assert.equal(classifySessionProbeResponse(response({status: 403, ok: false})), 'inactive');
  assert.equal(classifySessionProbeResponse(response({
    status: 302,
    ok: false,
    url: '/oauth2/authorization/simplepoint-client',
  })), 'inactive');
  assert.equal(classifySessionProbeResponse(response({type: 'opaqueredirect'})), 'inactive');
});

test('does not turn an unavailable session probe into a false login redirect', () => {
  assert.equal(classifySessionProbeResponse(response({status: 502, ok: false})), 'unknown');
});

test('recognizes login html reached after a followed API redirect', () => {
  const redirected = response({
    redirected: true,
    url: 'http://home.simplepoint.online/login',
    headers: new Headers({'content-type': 'text/html;charset=UTF-8'}),
  });

  assert.equal(
    isAuthenticationRedirectResponse(redirected, 'http://home.simplepoint.online'),
    true,
  );
  assert.equal(classifySessionProbeResponse(redirected), 'inactive');
});

test('keeps a successful json userinfo response active', () => {
  assert.equal(classifySessionProbeResponse(response()), 'active');
});
