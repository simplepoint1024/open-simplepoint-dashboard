import assert from 'node:assert/strict';
import test from 'node:test';
import {
  mcpAuthenticationLabel,
  mcpCapabilityTypeLabel,
  mcpOauthResultNotice,
  mcpPublicationStatusLabel,
  mcpServerErrorLabel,
  mcpServerStatusLabel,
  mcpTransportLabel,
} from './labels.ts';

const keyOf = (key) => key;

test('every MCP Server lifecycle state resolves through a translation key', () => {
  assert.deepEqual(
    ['DRAFT', 'READY', 'ERROR', 'DISABLED'].map(
      (status) => mcpServerStatusLabel(keyOf, status),
    ),
    [
      'ai.mcp.server.status.draft',
      'ai.mcp.server.status.ready',
      'ai.mcp.server.status.error',
      'ai.mcp.server.status.disabled',
    ],
  );
});

test('MCP protocol and publication values resolve through translation keys', () => {
  assert.deepEqual(
    ['STREAMABLE_HTTP', 'STDIO'].map((value) => mcpTransportLabel(keyOf, value)),
    ['ai.mcp.transport.streamableHttp', 'ai.mcp.transport.stdio'],
  );
  assert.deepEqual(
    ['NONE', 'BEARER', 'OAUTH2'].map((value) => mcpAuthenticationLabel(keyOf, value)),
    ['ai.mcp.auth.none', 'ai.mcp.auth.bearer', 'ai.mcp.auth.oauth2'],
  );
  assert.deepEqual(
    ['DRAFT', 'PUBLISHED', 'DISABLED', 'ERROR'].map(
      (value) => mcpPublicationStatusLabel(keyOf, value),
    ),
    [
      'ai.mcp.publication.status.draft',
      'ai.mcp.publication.status.published',
      'ai.mcp.publication.status.disabled',
      'ai.mcp.publication.status.error',
    ],
  );
  assert.deepEqual(
    ['TOOL', 'RESOURCE', 'RESOURCE_TEMPLATE', 'PROMPT'].map(
      (value) => mcpCapabilityTypeLabel(keyOf, value),
    ),
    [
      'ai.mcp.capabilities.tools',
      'ai.mcp.capabilities.resources',
      'ai.mcp.capabilities.templates',
      'ai.mcp.capabilities.prompts',
    ],
  );
});

test('unknown MCP protocol values use safe local labels', () => {
  assert.equal(mcpTransportLabel(keyOf, 'PRIVATE'), 'ai.mcp.transport.unknown');
  assert.equal(mcpAuthenticationLabel(keyOf, 'PRIVATE'), 'ai.mcp.auth.unknown');
  assert.equal(
    mcpPublicationStatusLabel(keyOf, 'PRIVATE'),
    'ai.mcp.publication.status.unknown',
  );
  assert.equal(
    mcpCapabilityTypeLabel(keyOf, 'PRIVATE'),
    'ai.mcp.capabilities.unknown',
  );
});

test('unknown MCP Server lifecycle values never become user-facing text', () => {
  assert.equal(
    mcpServerStatusLabel(keyOf, 'PRIVATE_BACKEND_STATE'),
    'ai.mcp.server.status.unknown',
  );
});

test('MCP Server diagnostics resolve to safe local messages', () => {
  assert.equal(
    mcpServerErrorLabel(keyOf, 'AI_MCP_DISCOVERY_FAILED'),
    'ai.mcp.server.error.discoveryFailed',
  );
  assert.equal(
    mcpServerErrorLabel(keyOf, 'private backend details'),
    'ai.mcp.server.error.unknown',
  );
});

test('browser OAuth results resolve to finite local notices', () => {
  assert.equal(mcpOauthResultNotice('SUCCESS')?.key, 'ai.mcp.oauth.success.complete');
  assert.equal(
    mcpOauthResultNotice('AI_MCP_OAUTH_ACCESS_DENIED')?.key,
    'ai.mcp.oauth.error.accessDenied',
  );
  assert.equal(
    mcpOauthResultNotice('AI_MCP_OAUTH_AUTHORIZATION_EXPIRED')?.key,
    'ai.mcp.oauth.error.expired',
  );
  assert.equal(
    mcpOauthResultNotice('PRIVATE_PROVIDER_PROSE')?.key,
    'ai.mcp.oauth.error.complete',
  );
});
