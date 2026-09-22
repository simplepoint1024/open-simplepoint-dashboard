#!/usr/bin/env node

import {createHash} from 'node:crypto';
import {execFileSync, spawnSync} from 'node:child_process';
import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';

const root = resolve(import.meta.dirname, '../..');
const fixtureRoot = resolve(
  root,
  'simplepoint-services/simplepoint-service-tool-runtime-node/testdata/community-mcp-profiles',
);
const service = process.env.MCP_COMMUNITY_E2E_SERVICE;
const supported = new Set([
  'github', 'filesystem', 'git', 'postgresql', 'dockerhub', 'playwright',
]);
if (!supported.has(service)) {
  throw new Error(
    'MCP_COMMUNITY_E2E_SERVICE must be github, filesystem, git, '
      + 'postgresql, dockerhub, or playwright',
  );
}

const baseUrl = (process.env.MCP_E2E_BASE_URL
  ?? 'http://127.0.0.1:8080/ai').replace(/\/$/, '');
const authorization = process.env.MCP_E2E_AUTHORIZATION ?? '';
let cookie = process.env.MCP_E2E_COOKIE ?? '';
const cookieFile = process.env.MCP_E2E_COOKIE_FILE ?? '';
const contextId = process.env.MCP_E2E_CONTEXT_ID ?? '';
const persistentRegistration = process.env.MCP_E2E_PERSIST_RESOURCES === 'true';
const verifyPersistentInvocation = process.env.MCP_E2E_VERIFY_PERSISTENT_INVOCATION === 'true';
const keepResources = process.env.MCP_E2E_KEEP_RESOURCES === 'true';
const waitAttempts = Number(process.env.MCP_E2E_WAIT_ATTEMPTS ?? '120');
if (!cookie && cookieFile) {
  const now = Math.floor(Date.now() / 1000);
  cookie = (await readFile(cookieFile, 'utf8'))
    .split(/\r?\n/)
    .map(line => line.startsWith('#HttpOnly_') ? line.slice('#HttpOnly_'.length) : line)
    .filter(line => line && !line.startsWith('#'))
    .map(line => line.split('\t'))
    .filter(fields => fields.length >= 7
      && (fields[4] === '0' || Number(fields[4]) > now))
    .map(fields => `${fields[5]}=${fields[6]}`)
    .join('; ');
}
if (!authorization && !cookie) {
  throw new Error(
    'MCP_E2E_AUTHORIZATION, MCP_E2E_COOKIE, or MCP_E2E_COOKIE_FILE is required',
  );
}
if (!Number.isInteger(waitAttempts) || waitAttempts < 1) {
  throw new Error('MCP_E2E_WAIT_ATTEMPTS must be a positive integer');
}

const resources = {
  serverId: '', poolId: '', secretId: '', profileId: '', revisionId: '', managedVolumes: [],
};
let registrationSucceeded = false;
const suffix = persistentRegistration
  ? '' : `${new Date().toISOString().replace(/\D/g, '').slice(0, 14)}-${process.pid}`;
const code = persistentRegistration
  ? `community-${service}-mcp` : `community-${service}-${suffix}`;
const serviceLabel = {
  github: 'GitHub',
  filesystem: 'Filesystem',
  git: 'Git',
  postgresql: 'PostgreSQL',
  dockerhub: 'DockerHub',
  playwright: 'Playwright',
}[service];

async function api(method, path, body) {
  const headers = {Accept: 'application/json'};
  if (authorization) headers.Authorization = authorization;
  if (cookie) headers.Cookie = cookie;
  if (contextId) headers['X-Authorization-Context-Id'] = contextId;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: 'manual',
  });
  const text = await response.text();
  if (response.status >= 300 && response.status < 400) {
    const location = response.headers.get('location') ?? '<missing>';
    throw new Error(
      `${method} ${path} redirected to ${location}; the login session is likely expired`,
    );
  }
  if (!response.ok) {
    throw new Error(`${method} ${path} returned ${response.status}: ${text.slice(0, 1000)}`);
  }
  if (!text) return null;
  if (!(response.headers.get('content-type') ?? '').includes('application/json')) {
    return text;
  }
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error(
      `${method} ${path} returned non-JSON content (${response.status}): ${text.slice(0, 200)}`,
      {cause: error},
    );
  }
}

async function cleanupApi(errors, method, path, body) {
  try {
    await api(method, path, body);
  } catch (error) {
    errors.push(error);
  }
}

async function waitFor(label, operation, predicate) {
  let value;
  for (let attempt = 1; attempt <= waitAttempts; attempt += 1) {
    value = await operation();
    if (predicate(value)) return value;
    const status = value?.status ?? 'unknown';
    if (['ERROR', 'FAILED', 'REJECTED', 'CANCELLED'].includes(status)) {
      throw new Error(`${label} entered ${status}: ${JSON.stringify(value)}`);
    }
    process.stderr.write(`${label}: attempt=${attempt} status=${status}\n`);
    await delay(1000);
  }
  throw new Error(`${label} did not become ready`);
}

function sha256(value) {
  return `sha256:${createHash('sha256').update(value).digest('hex')}`;
}

function splitPinnedReference(reference) {
  const marker = reference.lastIndexOf('@sha256:');
  if (marker < 1) throw new Error(`Profile image is not digest-pinned: ${reference}`);
  return {
    repository: reference.slice(0, marker),
    digest: reference.slice(marker + 1),
  };
}

async function createPostgresSecret() {
  const value = `[[sources]]
id = "acceptance"
description = "Open SimplePoint MCP acceptance database"
dsn = "postgres://mcp_reader:mcp-reader-local@postgres.mcp-e2e.local:5432/mcp_e2e?sslmode=disable"
connection_timeout = 10
query_timeout = 10
search_path = "acceptance,public"

[[tools]]
name = "execute_sql"
source = "acceptance"
readonly = true
max_rows = 100
`;
  const secret = await api('POST', '/workbench/runtime/secrets', {
    code: `${code}-dbhub`,
    name: persistentRegistration
      ? 'Community PostgreSQL MCP read-only configuration'
      : `DBHub read-only E2E ${suffix}`,
    description: persistentRegistration
      ? 'Read-only DBHub TOML used by the managed community PostgreSQL MCP server'
      : 'Temporary DBHub TOML for community MCP acceptance',
    value,
    enabled: true,
  });
  resources.secretId = secret.id;
  return secret.id;
}

async function createServer() {
  const github = service === 'github';
  const body = {
    name: persistentRegistration
      ? `Community ${serviceLabel} MCP`
      : `Community ${serviceLabel} MCP E2E ${suffix}`,
    code,
    deploymentType: 'MANAGED_OCI',
    transportType: 'STDIO',
    authenticationType: github ? 'OAUTH2' : 'NONE',
    allowPrivateNetwork: false,
    enabled: true,
    description: persistentRegistration
      ? `Managed ${serviceLabel} MCP using the digest-pinned original community image`
      : 'Original community MCP image acceptance',
  };
  if (github) {
    Object.assign(body, {
      oauthClientId: process.env.MCP_E2E_GITHUB_CLIENT_ID,
      oauthClientSecret: process.env.MCP_E2E_GITHUB_CLIENT_SECRET,
      oauthRedirectUri: process.env.MCP_E2E_GITHUB_REDIRECT_URI,
      oauthScopes: process.env.MCP_E2E_GITHUB_SCOPES ?? 'read:user repo',
      oauthTokenEndpointAuthMethod: 'client_secret_post',
      oauthAuthorizationEndpoint: 'https://github.com/login/oauth/authorize',
      oauthTokenEndpoint: 'https://github.com/login/oauth/access_token',
    });
    for (const [key, value] of Object.entries({
      MCP_E2E_GITHUB_CLIENT_ID: body.oauthClientId,
      MCP_E2E_GITHUB_CLIENT_SECRET: body.oauthClientSecret,
      MCP_E2E_GITHUB_REDIRECT_URI: body.oauthRedirectUri,
    })) {
      if (!value) throw new Error(`${key} is required for the GitHub OAuth gate`);
    }
  }
  const created = await api('POST', '/workbench/mcp/servers', body);
  resources.serverId = created.id;
  return created;
}

async function findExistingPersistentServer() {
  if (!persistentRegistration) return null;
  const page = await api('GET', '/workbench/mcp/servers?page=0&size=500');
  return (page?.content ?? []).find(server => server.code === code) ?? null;
}

async function connectGithub(serverId) {
  const existing = await api('GET', `/workbench/mcp/servers/${serverId}/oauth/connection`);
  if (existing?.connected) return;
  const start = await api('POST', `/workbench/mcp/servers/${serverId}/oauth/authorize`, {});
  if (!start?.authorizationUrl) {
    throw new Error(`GitHub OAuth authorization did not return a URL: ${JSON.stringify(start)}`);
  }
  process.stderr.write(`Open this GitHub authorization URL before it expires:\n${start.authorizationUrl}\n`);
  if (process.env.MCP_E2E_GITHUB_WAIT_FOR_OAUTH !== 'true') {
    throw new Error(
      'GitHub is waiting for interactive OAuth; rerun with '
        + 'MCP_E2E_GITHUB_WAIT_FOR_OAUTH=true and complete the printed URL',
    );
  }
  await waitFor(
    'GitHub Provider Connection',
    () => api('GET', `/workbench/mcp/servers/${serverId}/oauth/connection`),
    value => value?.connected === true,
  );
}

async function importAndPublishProfile(secretId) {
  const profile = JSON.parse(await readFile(
    resolve(fixtureRoot, `${service}.profile.json`), 'utf8',
  ));
  if (persistentRegistration) {
    for (const binding of profile.storage) {
      if (binding.storageReference?.startsWith('community-e2e-')) {
        binding.storageReference = binding.storageReference.replace(
          'community-e2e-', 'community-',
        );
      }
    }
  }
  if (secretId) {
    for (const binding of profile.secrets) {
      if (binding.secretReference === 'MCP_E2E_POSTGRES_SECRET_ID') {
        binding.secretReference = secretId;
      }
    }
  }
  const provenance = JSON.parse(await readFile(
    resolve(fixtureRoot, 'provenance.json'), 'utf8',
  )).images.find(image => image.id === service);
  const descriptorJson = JSON.stringify({
    $schema: 'https://static.modelcontextprotocol.io/schemas/2025-12-11/server.schema.json',
    name: profile.descriptorName,
    version: profile.descriptorVersion,
    repository: {url: provenance.sourceRepository, source: 'github'},
  });
  const imported = await api('POST', '/workbench/runtime/profiles/import', {
    code,
    name: persistentRegistration
      ? `Community ${serviceLabel} MCP profile`
      : `Community ${serviceLabel} MCP profile ${suffix}`,
    descriptor: {
      sourceCatalogEntryId: null,
      registryName: profile.descriptorName,
      serverVersion: profile.descriptorVersion,
      title: `Community ${serviceLabel} MCP`,
      repositoryUrl: provenance.sourceRepository,
      descriptorJson,
    },
    spec: profile,
  });
  resources.profileId = imported.profile.id;
  for (const binding of profile.storage) {
    if (['WORKSPACE_RO', 'WORKSPACE_RW', 'PERSISTENT_VOLUME', 'OBJECT_SNAPSHOT']
      .includes(binding.type)) {
      resources.managedVolumes.push(managedVolumeName(
        imported.profile, binding.storageReference,
      ));
    }
  }
  const revision = await api(
    'POST', `/workbench/runtime/profiles/${imported.profile.id}/publish`, {},
  );
  resources.revisionId = revision.id;
  if (revision.imageDigest !== provenance.manifestDigest) {
    throw new Error('Published Runtime revision does not match provenance digest');
  }
  if (!revision.admissionReportHash) {
    throw new Error('Runtime revision is missing its MCP admission evidence hash');
  }
  return {profile: imported.profile, revision};
}

async function createPool(serverId, profile, revision) {
  const pinned = splitPinnedReference(revision.imageReference);
  const pool = await api('POST', '/workbench/runtime/pools', {
    code,
    name: persistentRegistration
      ? `Community ${serviceLabel} MCP pool`
      : `Community ${serviceLabel} MCP pool ${suffix}`,
    serverId,
    imageReference: pinned.repository,
    imageDigest: revision.imageDigest,
    memoryBytes: service === 'playwright' ? 1073741824 : 268435456,
    nanoCpus: service === 'playwright' ? 1000000000 : 500000000,
    pidsLimit: service === 'playwright' ? 256 : 64,
    networkMode: 'none',
    egressAllowlist: [],
    secretIds: [],
    // Create the Pool dormant, bind its immutable Runtime Profile revision,
    // and only then scale it. Starting with a desired replica would invoke
    // the legacy image-only admission path before the Profile's command,
    // storage, transport, and secret contract exists.
    minReplicas: 0,
    // Dedicated profiles allocate one workload per stable MCP session. The
    // Management discovery and direct invocation need session capacity.
    maxReplicas: 6,
    desiredReplicas: 0,
    activationReplicas: 1,
    prewarmNodes: persistentRegistration ? 0 : 1,
    idleTimeoutSeconds: 300,
    replicaLifetimeSeconds: 3600,
  });
  resources.poolId = pool.id;
  await api('POST', `/workbench/runtime/pools/${pool.id}/revision`, {
    profileId: profile.id,
    revisionId: revision.id,
  });
  if (!persistentRegistration) {
    await api('POST', `/workbench/runtime/pools/${pool.id}/scale`, {
      desiredReplicas: 1,
    });
  }
  return waitFor(
    'Runtime Pool',
    () => api('GET', `/workbench/runtime/pools/${pool.id}`),
    value => persistentRegistration
      ? ['IDLE', 'READY'].includes(value?.status)
      : value?.status === 'READY' && value.readyReplicas >= 1,
  );
}

function managedVolumeName(profile, storageReference) {
  const material = `${profile.scopeType}\0${profile.tenantId ?? ''}\0${storageReference}`;
  return `open-simplepoint-managed-${createHash('sha256').update(material).digest('hex').slice(0, 40)}`;
}

function seedGitWorkspace(profile, imageReference) {
  const storageReference = persistentRegistration
    ? 'community-git' : 'community-e2e-git';
  const volume = managedVolumeName(profile, storageReference);
  const common = [
    'run', '--rm',
    '--volume', `${volume}:/workspace`,
    '--entrypoint', 'git', imageReference,
    '-C', '/workspace',
  ];
  for (const args of [
    ['init'],
    ['config', 'user.email', 'mcp-e2e@simplepoint.local'],
    ['config', 'user.name', 'SimplePoint MCP E2E'],
    ['commit', '--allow-empty', '-m', 'MCP acceptance seed'],
    ['checkout', '-B', 'mcp-e2e-approved'],
  ]) {
    execFileSync('docker', [...common, ...args], {stdio: 'inherit'});
  }
}

const cases = {
  github: {
    tools: ['get_me'],
    args: {},
    expected: '',
  },
  filesystem: {
    tools: ['list_directory'],
    args: {path: '/workspace'},
    expected: '',
  },
  git: {
    tools: ['git_status'],
    args: {repo_path: '/workspace'},
    expected: '',
  },
  postgresql: {
    tools: ['execute_sql', 'execute_sql_acceptance'],
    args: {sql: 'SELECT message FROM acceptance.runtime_probe WHERE id = 1'},
    expected: 'open-simplepoint-postgresql-mcp-e2e-ok',
  },
  dockerhub: {
    tools: ['search'],
    // The upstream server's current output schema predates Docker Hub's new
    // DHI/hardened result variants. Limit this compatibility probe to the
    // first stable official-image result until upstream expands that enum.
    args: {query: 'postgres', size: 1},
    expected: 'postgres',
  },
  playwright: {
    tools: ['browser_navigate'],
    args: {url: 'http://site.mcp-e2e.local/'},
    expected: 'SimplePoint Playwright MCP acceptance',
  },
};

async function discoverAndCall(serverId) {
  const discovery = await api('POST', `/workbench/mcp/servers/${serverId}/discover`, {});
  const selected = cases[service].tools
    .map(name => discovery.tools.find(tool => tool.name === name))
    .find(Boolean);
  if (!selected) {
    throw new Error(`Expected Tool is absent; discovered: ${discovery.tools.map(t => t.name).join(', ')}`);
  }
  const server = await api('GET', `/workbench/mcp/servers/${serverId}`);
  if (!server.activeSnapshotId) throw new Error('Discovery did not activate a capability snapshot');
  const argumentsValue = {...cases[service].args};
  const properties = selected.inputSchema?.properties ?? {};
  if ('source' in properties && !('source' in argumentsValue)) {
    argumentsValue.source = 'acceptance';
  }
  if ('sourceId' in properties && !('sourceId' in argumentsValue)) {
    argumentsValue.sourceId = 'acceptance';
  }
  const result = await api('POST', `/workbench/mcp/servers/${serverId}/tools/call`, {
    toolName: selected.name,
    arguments: argumentsValue,
  });
  if (result.error === true) throw new Error(`MCP Tool returned an error: ${JSON.stringify(result)}`);
  const encoded = JSON.stringify(result);
  if (cases[service].expected && !encoded.includes(cases[service].expected)) {
    throw new Error(`MCP Tool result omitted ${cases[service].expected}: ${encoded}`);
  }
  if (service === 'postgresql') {
    const denied = await api('POST', `/workbench/mcp/servers/${serverId}/tools/call`, {
      toolName: selected.name,
      arguments: {
        ...argumentsValue,
        sql: "INSERT INTO acceptance.runtime_probe(id, message) VALUES (2, 'must-not-write')",
      },
    });
    if (denied.error !== true) {
      throw new Error(`DBHub read-only policy allowed a write: ${JSON.stringify(denied)}`);
    }
  }
  if (service === 'playwright') {
    const click = discovery.tools.find(tool => tool.name === 'browser_click');
    const evaluate = discovery.tools.find(tool => tool.name === 'browser_evaluate');
    if (!click || !evaluate) {
      throw new Error('Playwright browser_click/browser_evaluate Tools are absent');
    }
    const clicked = await api('POST', `/workbench/mcp/servers/${serverId}/tools/call`, {
      toolName: click.name,
      arguments: {
        target: 'button',
        element: 'Verify browser session button',
      },
    });
    if (clicked.error === true) {
      throw new Error(`Playwright click assertion failed: ${JSON.stringify(clicked)}`);
    }
    const evaluated = await api('POST', `/workbench/mcp/servers/${serverId}/tools/call`, {
      toolName: evaluate.name,
      arguments: {
        target: '#result',
        element: 'verification result',
        function: '(element) => element.textContent',
      },
    });
    if (evaluated.error === true
        || !JSON.stringify(evaluated).includes('open-simplepoint-playwright-mcp-e2e-ok')) {
      throw new Error(`Playwright evaluation assertion failed: ${JSON.stringify(evaluated)}`);
    }
  }
  return {
    tool: selected,
    arguments: argumentsValue,
    snapshotId: server.activeSnapshotId,
    result,
  };
}

async function discoverCapabilities(serverId) {
  const discovery = await api('POST', `/workbench/mcp/servers/${serverId}/discover`, {});
  const expectedTool = cases[service].tools
    .map(name => discovery.tools.find(tool => tool.name === name))
    .find(Boolean);
  if (!expectedTool) {
    throw new Error(
      `Expected Tool is absent; discovered: ${discovery.tools.map(tool => tool.name).join(', ')}`,
    );
  }
  const server = await api('GET', `/workbench/mcp/servers/${serverId}`);
  if (!server.activeSnapshotId) {
    throw new Error('Discovery did not activate a capability snapshot');
  }
  return {
    snapshotId: server.activeSnapshotId,
    toolCount: discovery.tools?.length ?? 0,
    resourceCount: discovery.resources?.length ?? 0,
    promptCount: discovery.prompts?.length ?? 0,
  };
}

async function verifyLedger(serverId, snapshotId, revisionId) {
  const page = await api(
    'GET', `/workbench/mcp/invocations?serverId=${encodeURIComponent(serverId)}&page=0&size=200`,
  );
  const matches = (page.content ?? []).filter(item =>
    item.snapshotId === snapshotId && item.runtimeRevisionId === revisionId,
  );
  const minimum = 1;
  if (matches.length < minimum || matches.some(item => !item.requestHash)) {
    throw new Error(
      `Invocation ledger lacks direct MCP revision evidence: ${JSON.stringify(page)}`,
    );
  }
  return matches;
}

async function cleanup() {
  if (keepResources || (persistentRegistration && registrationSucceeded)) {
    return null;
  }
  const errors = [];
  if (service === 'github' && resources.serverId) {
    await cleanupApi(
      errors, 'DELETE', `/workbench/mcp/servers/${resources.serverId}/oauth/connection`,
    );
  }
  if (resources.poolId) {
    await cleanupApi(
      errors, 'POST', `/workbench/runtime/pools/${resources.poolId}/disable`, {},
    );
    let reclaimed = false;
    for (let attempt = 0; attempt < 60; attempt += 1) {
      try {
        const pool = await api('GET', `/workbench/runtime/pools/${resources.poolId}`);
        if (pool.currentReplicas === 0 && pool.readyReplicas === 0) {
          reclaimed = true;
          break;
        }
      } catch {
        break;
      }
      await delay(1000);
    }
    if (!reclaimed) errors.push(new Error('Runtime Pool resources were not reclaimed'));
    await cleanupApi(
      errors, 'DELETE', `/workbench/runtime/pools/${resources.poolId}`,
    );
  }
  if (resources.profileId && !resources.revisionId) {
    await cleanupApi(
      errors, 'DELETE', `/workbench/runtime/profiles/${resources.profileId}`,
    );
  }
  if (resources.serverId) {
    await cleanupApi(
      errors, 'DELETE', `/workbench/mcp/servers?ids=${resources.serverId}`,
    );
  }
  if (resources.secretId && !resources.revisionId) {
    await cleanupApi(
      errors, 'DELETE', '/workbench/runtime/secrets', [resources.secretId],
    );
  }
  for (const volume of resources.managedVolumes) {
    if (/^open-simplepoint-managed-[a-f0-9]{40}$/.test(volume)) {
      const inspect = spawnSync('docker', ['volume', 'inspect', volume]);
      if (inspect.status === 0) {
        const remove = spawnSync('docker', ['volume', 'rm', volume]);
        if (remove.status !== 0) {
          errors.push(new Error(`Managed acceptance volume was not removed: ${volume}`));
        }
      }
    }
  }
  return errors.length === 0
    ? null : new AggregateError(errors, 'Community MCP E2E cleanup failed');
}

let failed;
try {
  const existing = await findExistingPersistentServer();
  if (existing) {
    const discovery = existing.activeSnapshotId
      ? null : await discoverCapabilities(existing.id);
    const direct = verifyPersistentInvocation ? await discoverAndCall(existing.id) : null;
    registrationSucceeded = true;
    process.stdout.write(`${JSON.stringify({
      status: direct
        ? 'registered_and_verified'
        : discovery ? 'registration_resumed' : 'already_registered',
      service, serverId: existing.id,
      snapshotId: existing.activeSnapshotId ?? discovery?.snapshotId ?? null,
      ...(discovery ?? {}),
      ...(direct ? {directTool: direct.tool.name} : {}),
    }, null, 2)}\n`);
  } else {
    const server = await createServer();
    if (service === 'github') await connectGithub(server.id);
    const secretId = service === 'postgresql' ? await createPostgresSecret() : '';
    const {profile, revision} = await importAndPublishProfile(secretId);
    await createPool(server.id, profile, revision);
    if (service === 'git') seedGitWorkspace(profile, revision.imageReference);
    if (persistentRegistration) {
      const discovery = await discoverCapabilities(server.id);
      registrationSucceeded = true;
      process.stdout.write(`${JSON.stringify({
        status: 'registered', service, serverId: server.id, profileId: profile.id,
        revisionId: revision.id, poolId: resources.poolId,
        ...discovery,
      }, null, 2)}\n`);
    } else {
      const direct = await discoverAndCall(server.id);
      const ledger = await verifyLedger(server.id, direct.snapshotId, revision.id);
      process.stdout.write(`${JSON.stringify({
        status: 'passed', service, serverId: server.id, profileId: profile.id,
        revisionId: revision.id, poolId: resources.poolId,
        invocationCount: ledger.length,
      }, null, 2)}\n`);
    }
  }
} catch (error) {
  failed = error;
} finally {
  const cleanupFailure = await cleanup();
  if (cleanupFailure) {
    failed = failed
      ? new AggregateError([failed, cleanupFailure], 'Acceptance and cleanup failed')
      : cleanupFailure;
  }
}
if (failed) throw failed;
