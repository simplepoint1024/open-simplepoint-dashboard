import {readFileSync} from 'node:fs';
import {compileLocalQuery} from '../libs/components/DataTable/localQuery.ts';

// Exported by the real CommonMainTableQueryHttpTest; no substitute form engine or schema provider.
const schema = JSON.parse(readFileSync(new URL('./common-main-schemas/ExternalIdentityProvider.json', import.meta.url), 'utf8'));
const base = '/common/oidc/external-providers';
const fields = Object.fromEntries(Object.entries(schema.schema['x-query'].fields).map(([name, field]) => [name,
  {type: field.javaType === 'Boolean' ? 'boolean' : field.javaType === 'Integer' ? 'integer' : 'string'}]));
const dictionaries = {'oidc.external-provider.preset': ['CUSTOM', 'GOOGLE', 'GITHUB', 'APPLE', 'MICROSOFT'],
  'oidc.external-provider.protocol': ['OIDC', 'OAUTH2'],
  'oidc.external-provider.client-authentication-method': ['client_secret_basic', 'client_secret_post', 'none'],
  'oidc.external-provider.match-strategy': ['VERIFIED_EMAIL', 'USER_ID']};

export function createIdentityProviderFixture() {
  const rows = Array.from({length: 521}, (_, index) => ({id: 'provider-' + String(index).padStart(3, '0'),
    registrationId: 'registration-' + index, displayName: 'Provider ' + String(index).padStart(3, '0'),
    preset: 'CUSTOM', protocol: 'OAUTH2', authorizationUri: 'https://provider.example.test/authorize',
    tokenUri: 'https://provider.example.test/token', userInfoUri: 'https://provider.example.test/userinfo',
    issuerUri: null, jwkSetUri: null, clientId: 'isolated-client-' + index, clientAuthenticationMethod: 'client_secret_basic',
    scopes: 'profile,email', userNameAttribute: 'id', subjectClaim: 'id', emailClaim: 'email', emailVerifiedClaim: null,
    matchStrategy: 'USER_ID', requireVerifiedEmail: false, allowPrivateNetwork: false, sortOrder: 0, enabled: false,
    clientSecretConfigured: true, description: 'Original metadata'}));
  rows[520].id = 'literal/provider 520?';
  const reads = [], probes = [], writes = [];
  let enabled = false, mode = 'all', nextResult = 'success', nextHold = false, nextId = 0;
  return {rows, reads, probes, writes, enable() {enabled = true;}, mode(value) {mode = value;},
    result(value) {nextResult = value;}, hold() {nextHold = true;}, release(probe = probes.at(-1)) {probe?.release?.();},
    async handle(req, url, respond, expectedFailure) {
      if (!enabled) return false;
      const path = url.pathname, capturedMode = mode;
      if (path === '/common/platform/dictionaries/options') {
        const values = dictionaries[url.searchParams.get('dictionaryCode')];
        if (!values) return false;
        respond(values.map(value => ({value, label: value}))); return true;
      }
      if (path === base + '/schema') {
        reads.push({kind: 'schema', mode: capturedMode});
        if (capturedMode === 'denied') {expectedFailure(503); respond({message: '配置权限结构不可用（测试）'}, 503); return true;}
        const actual = structuredClone(schema);
        if (capturedMode === 'test-only') actual.buttons = actual.buttons.filter(button => button.key === 'test');
        if (capturedMode === 'no-test') actual.buttons = actual.buttons.filter(button => button.key !== 'test');
        if (capturedMode === 'readonly') {
          for (const [field, property] of Object.entries(actual.schema.properties)) {
            if (field === 'description' || field === 'id') continue;
            property.readOnly = true; property['x-field-access'] = ['enabled', 'protocol', 'clientId'].includes(field) ? 'MASKED' : 'VISIBLE';
          }
        }
        respond(actual); return true;
      }
      if (path === base && req.method === 'GET') {
        reads.push({kind: 'list', mode: capturedMode, params: new URLSearchParams(url.searchParams)});
        const number = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
        if (!Number.isSafeInteger(number) || number < 0 || !Number.isSafeInteger(size) || size < 1 || size > 200) throw Error('Unbounded identity provider list');
        const filters = Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort'].includes(key)));
        const query = compileLocalQuery(fields, {filters, sort: url.searchParams.getAll('sort')});
        const data = rows.filter(query.matches).sort(query.compare);
        let content = data.slice(number * size, (number + 1) * size);
        if (capturedMode === 'readonly') content = content.map(row => ({...row, enabled: '***', protocol: '***', clientId: '***', clientSecretConfigured: '***'}));
        if (capturedMode === 'masked-id') content = content.map(row => ({...row, id: '***'}));
        respond({content, page: {number, size, totalElements: data.length, totalPages: Math.ceil(data.length / size)}}); return true;
      }
      const match = path.startsWith(base + '/') ? path.slice(base.length + 1).match(/^([^/]+)\/test$/) : null;
      if (match && req.method === 'POST') {
        let raw = ''; for await (const chunk of req) raw += chunk;
        const data = raw ? JSON.parse(raw) : null, id = decodeURIComponent(match[1]);
        if (!rows.some(row => row.id === id) || !data || Object.keys(data).length || ['no-test', 'denied', 'masked-id'].includes(capturedMode))
          throw Error('Identity probe invented a target, payload or server test capability');
        const kind = nextResult, held = nextHold; nextResult = 'success'; nextHold = false;
        const probe = {id, data, kind, mode: capturedMode, completed: false}; probes.push(probe);
        if (held) await new Promise(resolve => {probe.release = resolve;});
        probe.completed = true;
        if (kind === 'error') {expectedFailure(503); respond({message: '配置测试失败（隔离测试）'}, 503); return true;}
        if (kind === 'malformed') {respond({success: 'true', callbackUri: 'https://should-not-display.example.test'}); return true;}
        respond({success: kind !== 'false', message: kind === 'false' ? '配置检查未通过（隔离测试）' : 'OAuth2 配置有效，未验证真实登录（隔离测试）',
          authorizationUri: 'https://provider.example.test/authorize', tokenUri: 'https://provider.example.test/token',
          userInfoUri: 'https://provider.example.test/userinfo', jwkSetUri: null,
          callbackUri: kind === 'relative' ? '/login/oauth2/code/registration-520' : 'https://authorization.example.test/base/login/oauth2/code/registration-520'});
        return true;
      }
      if (path === base && ['POST', 'PUT', 'DELETE'].includes(req.method)) {
        let raw = ''; for await (const chunk of req) raw += chunk;
        const data = raw ? JSON.parse(raw) : null; writes.push({method: req.method, data, mode: capturedMode});
        if (capturedMode === 'test-only' || capturedMode === 'denied') throw Error('Identity editor invented a write capability');
        if (req.method === 'PUT') {
          const row = rows.find(row => row.id === data.id); if (!row) throw Error('Identity edit lost its real captured ID');
          if (['registrationId', 'clientSecretConfigured', 'clientSecretCiphertext', 'createdBy', 'updatedBy'].some(field => field in data)) throw Error('Identity write leaked projections or immutable data');
          if (capturedMode === 'readonly' && Object.keys(data).some(field => !['id', 'description'].includes(field))) throw Error('Identity edit resubmitted policy-protected fields');
          if ('clientSecret' in data && !data.clientSecret?.trim()) throw Error('Blank editing secret must be omitted');
          Object.assign(row, data); respond(row); return true;
        }
        if (req.method === 'POST') {
          if (!data.registrationId?.trim() || !data.displayName?.trim() || !data.clientId?.trim()
            || data.clientAuthenticationMethod !== 'none' && !data.clientSecret?.trim()) throw Error('Genuine identity form failed required validation');
          const created = {...data, id: 'created-provider-' + nextId++, clientSecretConfigured: !!data.clientSecret}; delete created.clientSecret;
          rows.push(created); respond(created); return true;
        }
        throw Error('This read-only identity probe regression does not authorize deletions');
      }
      return false;
    },
  };
}
