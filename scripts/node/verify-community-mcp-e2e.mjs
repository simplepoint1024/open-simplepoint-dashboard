#!/usr/bin/env node

import {createHash} from 'node:crypto';
import {execFileSync, spawnSync} from 'node:child_process';
import {readFile} from 'node:fs/promises';
import {createServer as createHttpServer} from 'node:http';
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
const verifyPersistentExecutionChain =
  process.env.MCP_E2E_VERIFY_PERSISTENT_CHAIN === 'true';
const keepResources = process.env.MCP_E2E_KEEP_RESOURCES === 'true';
const waitAttempts = Number(process.env.MCP_E2E_WAIT_ATTEMPTS ?? '120');
const registryPushUrl = (process.env.MCP_E2E_REGISTRY_PUSH_URL
  ?? 'http://127.0.0.1:5001').replace(/\/$/, '');
const registryReference = process.env.MCP_E2E_REGISTRY_REFERENCE
  ?? 'registry:5000/simplepoint/community-mcp-e2e';
let modelId = process.env.MCP_E2E_AGENT_MODEL_ID ?? '';
const requireAgent = process.env.MCP_E2E_REQUIRE_AGENT !== 'false';
const useModelStub = process.env.MCP_E2E_USE_MODEL_STUB === 'true';

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
  serverId: '', poolId: '', secretId: '', skillId: '', workflowId: '',
  agentId: '', providerId: '', modelId: '', profileId: '', revisionId: '',
  skillVersionId: '', workflowVersionId: '', agentVersionId: '', managedVolumes: [],
};
let modelStub;
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
    // management probe, Skill, Workflow, and Agent each need capacity.
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
  let skillTool = selected;
  let skillArguments = argumentsValue;
  if (service === 'filesystem') {
    skillTool = discovery.tools.find(tool => tool.name === 'write_file');
    skillArguments = {
      path: '/workspace/approved-by-platform.txt',
      content: 'open-simplepoint-filesystem-mcp-e2e-ok\n',
    };
  } else if (service === 'git') {
    skillTool = discovery.tools.find(tool => tool.name === 'git_checkout');
    skillArguments = {
      repo_path: '/workspace',
      branch_name: 'mcp-e2e-approved',
    };
  }
  if (!skillTool) {
    throw new Error(`Required effectful Tool is absent for ${service}`);
  }
  return {
    tool: selected,
    arguments: argumentsValue,
    skillTool,
    skillArguments,
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

async function registryRequest(path, options = {}) {
  const response = await fetch(`${registryPushUrl}${path}`, options);
  if (!response.ok) {
    throw new Error(`Registry ${options.method ?? 'GET'} ${path} returned ${response.status}: ${await response.text()}`);
  }
  return response;
}

async function pushBlob(repository, bytes) {
  const digest = sha256(bytes);
  const start = await registryRequest(`/v2/${repository}/blobs/uploads/`, {method: 'POST'});
  const location = new URL(start.headers.get('location'), registryPushUrl);
  location.searchParams.set('digest', digest);
  const response = await fetch(location, {method: 'PUT', body: bytes});
  if (!response.ok) throw new Error(`Registry blob upload failed: ${response.status}`);
  return {digest, size: bytes.length};
}

async function pushSkillArtifact(manifest, tag) {
  const slash = registryReference.indexOf('/');
  const repository = registryReference.slice(slash + 1);
  const layer = Buffer.from(JSON.stringify(manifest));
  const layerDescriptor = await pushBlob(repository, layer);
  const config = Buffer.from(JSON.stringify({
    schemaVersion: '1.0',
    manifestMediaType: 'application/vnd.simplepoint.skill.manifest.v1+json',
    manifestDigest: layerDescriptor.digest,
  }));
  const configDescriptor = await pushBlob(repository, config);
  const ociManifest = Buffer.from(JSON.stringify({
    schemaVersion: 2,
    mediaType: 'application/vnd.oci.image.manifest.v1+json',
    artifactType: 'application/vnd.simplepoint.skill.v1+json',
    config: {
      mediaType: 'application/vnd.simplepoint.skill.config.v1+json',
      ...configDescriptor,
    },
    layers: [{
      mediaType: 'application/vnd.simplepoint.skill.manifest.v1+json',
      ...layerDescriptor,
    }],
  }));
  const response = await registryRequest(`/v2/${repository}/manifests/${tag}`, {
    method: 'PUT',
    headers: {'Content-Type': 'application/vnd.oci.image.manifest.v1+json'},
    body: ociManifest,
  });
  return {
    reference: `${registryReference}:${tag}`,
    digest: response.headers.get('docker-content-digest') ?? sha256(ociManifest),
  };
}

async function startModelStub() {
  if (modelStub) return modelStub;
  const server = createHttpServer(async (request, response) => {
    // The provider service may normalize the configured base path before
    // appending the Responses endpoint. This listener is bound to a fresh,
    // loopback-only random port, so every POST belongs to this one model.
    if (request.method !== 'POST') {
      response.writeHead(404, {'Content-Type': 'application/json'});
      response.end('{"error":{"message":"not found"}}');
      return;
    }
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    let body;
    try {
      body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    } catch {
      response.writeHead(400, {'Content-Type': 'application/json'});
      response.end('{"error":{"message":"invalid JSON"}}');
      return;
    }
    const completedSkill = (body.input ?? [])
      .some(item => item?.type === 'function_call_output')
      || (body.messages ?? []).some(item => item?.role === 'tool');
    const tool = body.tools?.[0];
    const toolName = tool?.name ?? tool?.function?.name
      ?? body.functions?.[0]?.name ?? 'acceptance';
    const output = completedSkill
      ? [{
        type: 'message', role: 'assistant',
        content: [{type: 'output_text', text: '{}'}],
      }]
      : [{
        type: 'function_call', id: 'item_acceptance',
        call_id: `call_${service}_${suffix}`, name: toolName,
        arguments: '{}',
      }];
    response.writeHead(200, {'Content-Type': 'application/json'});
    response.end(JSON.stringify({
      id: `response_${service}_${Date.now()}`,
      status: 'completed', output,
      choices: [{
        index: 0,
        finish_reason: completedSkill ? 'stop' : 'tool_calls',
        message: completedSkill
          ? {role: 'assistant', content: '{}'}
          : {
            role: 'assistant', content: null,
            tool_calls: [{
              id: `call_${service}_${suffix}`,
              type: 'function',
              function: {name: toolName, arguments: '{}'},
            }],
          },
      }],
      usage: {
        input_tokens: 8, output_tokens: 4, total_tokens: 12,
        prompt_tokens: 8, completion_tokens: 4,
      },
    }));
  });
  await new Promise((resolvePromise, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolvePromise);
  });
  modelStub = server;
  return server;
}

async function ensureAcceptanceModel() {
  if (modelId) return modelId;
  if (!useModelStub) {
    if (requireAgent) {
      throw new Error(
        'MCP_E2E_AGENT_MODEL_ID is required; alternatively set '
          + 'MCP_E2E_USE_MODEL_STUB=true for the deterministic acceptance model',
      );
    }
    return '';
  }
  const server = await startModelStub();
  const address = server.address();
  if (!address || typeof address === 'string') {
    throw new Error('Acceptance model stub address is unavailable');
  }
  const platformHost = process.env.MCP_E2E_MODEL_STUB_PLATFORM_HOST
    ?? '127.0.0.1';
  const provider = await api('POST', '/workbench/providers', {
    code: `${code}-model`,
    name: `Community MCP E2E model ${suffix}`,
    providerType: 'OPENAI',
    baseUrl: `http://${platformHost}:${address.port}/v1`,
    apiKey: 'community-mcp-e2e-non-secret',
    allowPrivateNetwork: true,
    enabled: true,
    autoSyncEnabled: false,
    description: 'Temporary deterministic Agent acceptance model',
  });
  resources.providerId = provider.id;
  const model = await api('POST', '/workbench/models', {
    providerId: provider.id,
    modelId: 'community-mcp-e2e-model',
    displayName: 'Community MCP E2E deterministic model',
    modelType: 'LLM',
    enabled: true,
    billingEnabled: false,
    description: 'Temporary model for MCP Agent orchestration acceptance',
  });
  resources.modelId = model.id;
  modelId = model.id;
  return model.id;
}

async function createAndExecuteSkill(serverId, snapshotId, tool, argumentsValue) {
  const requiresApproval = tool.annotations?.readOnlyHint !== true
    || tool.annotations?.destructiveHint === true;
  const manifest = {
    apiVersion: 'simplepoint.io/v1alpha1',
    kind: 'Skill',
    metadata: {name: code, version: '1.0.0', title: `Community ${service} acceptance`},
    spec: {
      inputSchema: {type: 'object'},
      outputSchema: {type: 'object'},
      tools: [{alias: 'invoke', serverId, snapshotId, name: tool.name}],
      workflow: {
        steps: [{id: 'invoke', type: 'tool', tool: 'invoke', arguments: argumentsValue}],
        output: {$ref: 'steps.invoke'},
      },
      budgets: {maximumToolCalls: 2, maximumDurationSeconds: 120},
      approvals: {execution: {
        required: requiresApproval,
        // The local acceptance environment has one administrator account. Keep
        // an explicit approval transition while allowing that operator to
        // complete the gate without provisioning a synthetic second identity.
        allowSelfApproval: requiresApproval,
        instructions: 'Community MCP E2E side-effect gate',
      }},
    },
  };
  const artifactTag = persistentRegistration
    ? `${service}-managed` : `${service}-${suffix}`;
  const artifact = await pushSkillArtifact(manifest, artifactTag);
  const skill = await api('POST', '/workbench/skills', {
    code,
    name: persistentRegistration
      ? `Community ${serviceLabel} MCP Skill`
      : `Community ${serviceLabel} E2E Skill`,
    description: persistentRegistration
      ? `Managed Skill pinned to the ${serviceLabel} MCP capability snapshot`
      : 'Generated acceptance Skill pinned to an MCP snapshot',
    enabled: true,
  });
  resources.skillId = skill.id;
  const version = await api('POST', `/workbench/skills/${skill.id}/versions`, {
    version: '1.0.0',
    artifactReference: artifact.reference,
    artifactDigest: artifact.digest,
    manifest,
  });
  resources.skillVersionId = version.id;
  await api('POST', `/workbench/skills/${skill.id}/versions/${version.id}/publish`, {});
  let execution = await api('POST', `/workbench/skills/${skill.id}/executions`, {
    idempotencyKey: `${code}-skill`, input: {},
  });
  if (execution.status === 'WAITING_APPROVAL') {
    execution = await api(
      'POST', `/workbench/skills/${skill.id}/executions/${execution.id}/approve`,
      {comment: 'Community MCP E2E approval'},
    );
  }
  execution = await waitFor(
    'Skill execution',
    () => api('GET', `/workbench/skills/${skill.id}/executions/${execution.id}`),
    value => value?.status === 'SUCCEEDED',
  );
  return {skill, version, execution, requiresApproval};
}

async function createAndExecuteWorkflow(skill, version) {
  const workflow = await api('POST', '/workbench/workflows', {
    code,
    name: persistentRegistration
      ? `Community ${serviceLabel} MCP Workflow`
      : `Community ${serviceLabel} E2E Workflow`,
    description: persistentRegistration
      ? `Workflow invoking the immutable ${serviceLabel} MCP-backed Skill`
      : 'Workflow invoking the immutable MCP-backed Skill',
    enabled: true,
  });
  resources.workflowId = workflow.id;
  const manifest = {
    apiVersion: 'simplepoint.io/v1alpha1',
    kind: 'AgentWorkflow',
    metadata: {name: code, version: '1.0.0'},
    spec: {
      inputSchema: {type: 'object'},
      outputSchema: {type: 'object'},
      budgets: {maximumDurationSeconds: 300, maximumNodeExecutions: 8, maximumParallelism: 1},
      failurePolicy: {mode: 'FAIL_FAST', compensation: 'REVERSE_SUCCEEDED'},
      nodes: [
        {id: 'mcp-skill', type: 'skill', skillId: skill.id, versionId: version.id},
        {id: 'done', type: 'end'},
      ],
      edges: [{from: 'mcp-skill', to: 'done'}],
    },
  };
  const workflowVersion = await api(
    'POST', `/workbench/workflows/${workflow.id}/versions`,
    {version: '1.0.0', manifest},
  );
  resources.workflowVersionId = workflowVersion.id;
  await api(
    'POST', `/workbench/workflows/${workflow.id}/versions/${workflowVersion.id}/publish`, {},
  );
  let execution = await api('POST', `/workbench/workflows/${workflow.id}/executions`, {
    idempotencyKey: `${code}-workflow`, input: {},
  });
  // Approval-required child Skills are visible through the Skill execution API.
  for (let attempt = 0; attempt < waitAttempts; attempt += 1) {
    const children = await api(
      'GET', `/workbench/skills/${skill.id}/executions?page=0&size=50`,
    );
    for (const child of children.content ?? []) {
      if (child.status === 'WAITING_APPROVAL') {
        await api(
          'POST', `/workbench/skills/${skill.id}/executions/${child.id}/approve`,
          {comment: 'Community MCP Workflow E2E approval'},
        );
      }
    }
    execution = await api(
      'GET', `/workbench/workflows/${workflow.id}/executions/${execution.id}`,
    );
    if (execution.status === 'SUCCEEDED') return {workflow, workflowVersion, execution};
    if (['FAILED', 'CANCELLED'].includes(execution.status)) {
      throw new Error(`Workflow execution entered ${execution.status}: ${JSON.stringify(execution)}`);
    }
    await delay(1000);
  }
  throw new Error('Workflow execution did not finish');
}

async function verifyAgentBinding(skill, version) {
  await ensureAcceptanceModel();
  if (!modelId) {
    return {skipped: true, reason: 'MCP_E2E_AGENT_MODEL_ID is not configured'};
  }
  const agent = await api('POST', '/workbench/agents', {
    code,
    name: persistentRegistration
      ? `Community ${serviceLabel} MCP Agent`
      : `Community ${serviceLabel} E2E Agent`,
    description: persistentRegistration
      ? `Agent restricted to the immutable ${serviceLabel} MCP-backed Skill`
      : 'Agent restricted to one immutable MCP-backed Skill',
    enabled: true,
  });
  resources.agentId = agent.id;
  const manifest = {
    apiVersion: 'simplepoint.io/v1alpha1',
    kind: 'Agent',
    metadata: {name: code, version: '1.0.0'},
    spec: {
      systemPrompt: 'Always call the bound acceptance Skill exactly once, then return its result.',
      model: {primaryModelId: modelId, fallbackModelIds: []},
      skills: [{alias: 'acceptance', skillId: skill.id, versionId: version.id}],
      behavior: {instructions: ['Do not answer without calling acceptance.'], responseStyle: 'concise'},
      memory: {shortTermEnabled: true, longTermEnabled: false, maximumMessages: 8},
      budgets: {
        maximumSteps: 8, maximumLoopDepth: 2, maximumConcurrency: 1,
        maximumInputTokens: 8000, maximumOutputTokens: 2000, maximumCost: 10,
      },
      approvals: {execution: {required: false, allowSelfApproval: false}},
      inputSchema: {type: 'object'},
      outputSchema: {type: 'object'},
      publicAccess: false,
    },
  };
  const agentVersion = await api(
    'POST', `/workbench/agents/${agent.id}/versions`, {version: '1.0.0', manifest},
  );
  resources.agentVersionId = agentVersion.id;
  await api('POST', `/workbench/agents/${agent.id}/versions/${agentVersion.id}/publish`, {});
  let execution = await api('POST', `/workbench/agents/${agent.id}/executions`, {
    idempotencyKey: `${code}-agent`,
    input: {request: `Run the ${service} acceptance operation now.`},
  });
  for (let attempt = 0; attempt < waitAttempts; attempt += 1) {
    const children = await api(
      'GET', `/workbench/skills/${skill.id}/executions?page=0&size=50`,
    );
    for (const child of children.content ?? []) {
      if (child.status === 'WAITING_APPROVAL') {
        await api(
          'POST', `/workbench/skills/${skill.id}/executions/${child.id}/approve`,
          {comment: 'Community MCP Agent E2E approval'},
        );
      }
    }
    execution = await api(
      'GET', `/workbench/agents/${agent.id}/executions/${execution.id}`,
    );
    if (execution.status === 'SUCCEEDED') break;
    if (execution.status === 'WAITING_APPROVAL') {
      await api(
        'POST', `/workbench/agents/${agent.id}/executions/${execution.id}/approve`,
        {comment: 'Community MCP Agent E2E execution approval'},
      );
    } else if (['FAILED', 'REJECTED', 'CANCELLED'].includes(execution.status)) {
      throw new Error(`Agent execution entered ${execution.status}: ${JSON.stringify(execution)}`);
    }
    await delay(1000);
  }
  if (execution.status !== 'SUCCEEDED') {
    throw new Error('Agent execution did not finish');
  }
  return {agent, agentVersion, execution, skipped: false};
}

async function verifyLedger(serverId, snapshotId, revisionId) {
  const page = await api(
    'GET', `/workbench/mcp/invocations?serverId=${encodeURIComponent(serverId)}&page=0&size=200`,
  );
  const matches = (page.content ?? []).filter(item =>
    item.snapshotId === snapshotId && item.runtimeRevisionId === revisionId,
  );
  const minimum = requireAgent ? 4 : 3;
  if (matches.length < minimum || matches.some(item => !item.requestHash)) {
    throw new Error(
      `Invocation ledger lacks direct/Skill/Workflow/Agent revision evidence: ${JSON.stringify(page)}`,
    );
  }
  return matches;
}

async function cleanup() {
  if (keepResources || (persistentRegistration && registrationSucceeded)) {
    if (modelStub) {
      await new Promise(resolvePromise => modelStub.close(resolvePromise));
      modelStub = undefined;
    }
    return null;
  }
  const errors = [];
  if (resources.agentId && !resources.agentVersionId) {
    await cleanupApi(
      errors, 'DELETE', `/workbench/agents/${resources.agentId}`,
    );
  }
  if (resources.workflowId && !resources.workflowVersionId) {
    await cleanupApi(
      errors, 'DELETE', `/workbench/workflows/${resources.workflowId}`,
    );
  }
  if (resources.skillId && !resources.skillVersionId) {
    await cleanupApi(
      errors, 'DELETE', `/workbench/skills/${resources.skillId}`,
    );
  }
  if (service === 'github' && resources.serverId && !resources.skillVersionId) {
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
  if (resources.serverId && !resources.skillVersionId) {
    await cleanupApi(
      errors, 'DELETE', `/workbench/mcp/servers?ids=${resources.serverId}`,
    );
  }
  if (resources.secretId && !resources.revisionId) {
    await cleanupApi(
      errors, 'DELETE', '/workbench/runtime/secrets', [resources.secretId],
    );
  }
  if (resources.modelId && !resources.agentVersionId) {
    await cleanupApi(
      errors, 'DELETE', `/workbench/models?ids=${resources.modelId}`,
    );
  }
  if (resources.providerId && !resources.agentVersionId) {
    await cleanupApi(
      errors, 'DELETE', `/workbench/providers?ids=${resources.providerId}`,
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
  if (modelStub) {
    await new Promise(resolvePromise => modelStub.close(resolvePromise));
    modelStub = undefined;
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
    let chain = null;
    if (verifyPersistentExecutionChain) {
      const direct = await discoverAndCall(existing.id);
      const skill = await createAndExecuteSkill(
        existing.id, direct.snapshotId, direct.skillTool, direct.skillArguments,
      );
      const workflow = await createAndExecuteWorkflow(skill.skill, skill.version);
      const agent = await verifyAgentBinding(skill.skill, skill.version);
      chain = {
        directTool: direct.tool.name,
        skillId: skill.skill.id,
        skillExecutionId: skill.execution.id,
        workflowId: workflow.workflow.id,
        workflowExecutionId: workflow.execution.id,
        agentId: agent.agent?.id ?? null,
        agentExecutionId: agent.execution?.id ?? null,
        agentSkipped: agent.skipped,
      };
    }
    registrationSucceeded = true;
    process.stdout.write(`${JSON.stringify({
      status: chain
        ? 'registered_and_verified'
        : discovery ? 'registration_resumed' : 'already_registered',
      service, serverId: existing.id,
      snapshotId: existing.activeSnapshotId ?? discovery?.snapshotId ?? null,
      ...(discovery ?? {}),
      ...(chain ?? {}),
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
      const skill = await createAndExecuteSkill(
        server.id, direct.snapshotId, direct.skillTool, direct.skillArguments,
      );
      if (['filesystem', 'git'].includes(service) && !skill.requiresApproval) {
        throw new Error(`${service} effectful Skill bypassed the conservative approval gate`);
      }
      const workflow = await createAndExecuteWorkflow(skill.skill, skill.version);
      const agent = await verifyAgentBinding(skill.skill, skill.version);
      const ledger = await verifyLedger(server.id, direct.snapshotId, revision.id);
      process.stdout.write(`${JSON.stringify({
        status: 'passed', service, serverId: server.id, profileId: profile.id,
        revisionId: revision.id, poolId: resources.poolId,
        skillExecutionId: skill.execution.id,
        workflowExecutionId: workflow.execution.id,
        agentExecutionId: agent.execution?.id ?? null,
        agentSkipped: agent.skipped,
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
