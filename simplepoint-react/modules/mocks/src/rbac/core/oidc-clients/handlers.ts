import {http, HttpResponse} from 'msw';
import schema from '../../../../../../scripts/common-main-schemas/Client.json';
import {compileLocalQuery, type LocalFields} from '../../../../../../libs/components/DataTable/localQuery';

const mockClient = {
  id: '1',
  clientId: 'simplepoint-client',
  clientIdIssuedAt: '2025-09-22T13:08:42.826Z',
  clientName: 'simplepoint-client',
  clientSecretExpiresAt: null,
  clientAuthenticationMethods: 'client_secret_basic',
  authorizationGrantTypes: 'authorization_code,refresh_token',
  redirectUris: 'http://localhost:8080/login/oauth2/code/oidc',
  postLogoutRedirectUris: 'http://localhost:8080',
  scopes: 'openid,profile,roles,tenant,organs,email,address,phone',
  clientSettings: '{}',
  tokenSettings: '{}',
};

const mockConfiguration = {
  ...mockClient,
  clientAuthenticationMethods: ['client_secret_basic'],
  authorizationGrantTypes: ['authorization_code', 'refresh_token'],
  redirectUris: ['http://localhost:8080/login/oauth2/code/oidc'],
  postLogoutRedirectUris: ['http://localhost:8080'],
  scopes: ['openid', 'profile', 'roles', 'tenant', 'organs', 'email', 'address', 'phone'],
  requireProofKey: true,
  requireAuthorizationConsent: false,
  jwkSetUrl: 'http://authorization:9000/oauth2/jwks',
  tokenEndpointAuthenticationSigningAlgorithm: 'PS256',
  reuseRefreshTokens: true,
  x509CertificateBoundAccessTokens: false,
  idTokenSignatureAlgorithm: 'PS256',
  accessTokenFormat: 'self-contained',
  accessTokenTtlSeconds: 1800,
  refreshTokenTtlSeconds: 28800,
  authorizationCodeTtlSeconds: 300,
  deviceCodeTtlSeconds: 300,
};

const fields: LocalFields = Object.fromEntries(Object.entries(schema.schema['x-query'].fields)
  .map(([name, field]) => [name, {type: field.javaType === 'Instant' ? 'date-time' : 'string'}]));

const withoutSecret = (body: Record<string, unknown>) => {
  const {clientSecret: _secret, ...visible} = body;
  return visible;
};

export default [
  http.get('/common/oidc/clients/schema', () => HttpResponse.json(schema)),
  http.get('/common/oidc/clients', ({request}) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
    try {
      if (!Number.isSafeInteger(page) || page < 0 || !Number.isSafeInteger(size) || size < 1
        || size > schema.schema['x-query'].maxPageSize) throw Error('分页范围超限');
      const filters = Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort'].includes(key)));
      const query = compileLocalQuery(fields, {filters, sort: url.searchParams.getAll('sort')});
      const rows = [mockClient].filter(query.matches).sort(query.compare);
      return HttpResponse.json({content: rows.slice(page * size, (page + 1) * size),
        page: {number: page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}});
    } catch (error) {
      return HttpResponse.json({code: 'INVALID_QUERY', message: (error as Error).message}, {status: 400});
    }
  }),
  http.get('/common/oidc/clients/:id/configuration', () => HttpResponse.json(mockConfiguration)),
  http.post('/common/oidc/clients/configuration', async ({request}) =>
    HttpResponse.json(withoutSecret(await request.json() as Record<string, unknown>))),
  http.put('/common/oidc/clients/:id/configuration', async ({request, params}) => {
    const body = await request.json() as Record<string, unknown>;
    return HttpResponse.json({...withoutSecret(body), id: params.id});
  }),
];
