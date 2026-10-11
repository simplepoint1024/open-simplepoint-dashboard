import {readFileSync} from 'node:fs';

// Captured by CurrentUserAccessHttpTest using native TenantResource SQL and
// the production controller/field-permission serializer; do not fabricate rows.
const contracts = Object.fromEntries(['readable-page', 'readable-detail', 'masked-page', 'hidden-page',
  'organization-readable', 'organization-masked', 'organization-hidden', 'organization-unassigned']
  .map(name => [name, JSON.parse(readFileSync(new URL(`./profile-access-contracts/${name}.json`, import.meta.url), 'utf8'))]));

export function createProfileAccessFixture() {
  let mode = 'readable';
  let organizationMode = 'unassigned';
  const reads = [];
  return {
    reads,
    mode(value) {
      if (!['readable', 'masked', 'hidden'].includes(value)) throw Error('Unknown native profile contract');
      mode = value;
    },
    organization(value) {
      if (!['readable', 'masked', 'hidden', 'unassigned'].includes(value)) throw Error('Unknown native organization contract');
      organizationMode = value;
    },
    handle(req, url, respond) {
      const endpoint = url.pathname;
      if (endpoint === '/common/users/me/organization') {
        if (req.method !== 'GET') throw Error('Self organization preview must be read-only');
        reads.push({endpoint, params: new URLSearchParams(url.searchParams), mode: organizationMode});
        respond(structuredClone(contracts[`organization-${organizationMode}`])); return true;
      }
      if (!['/common/users/me/access/resources', '/common/users/me/access/page-detail'].includes(endpoint)) return false;
      if (req.method !== 'GET') throw Error('Personal access preview must be read-only');
      reads.push({endpoint, params: new URLSearchParams(url.searchParams), mode});
      if (endpoint.endsWith('/page-detail')) {
        if (mode !== 'readable' || url.searchParams.get('code') !== 'profile-page') throw Error('Invalid public detail identity');
        respond(structuredClone(contracts['readable-detail']));
      } else {
        if (url.searchParams.get('type') !== 'PAGE' || Number(url.searchParams.get('size')) > 60) throw Error('Invalid profile page query');
        respond(structuredClone(contracts[`${mode}-page`]));
      }
      return true;
    },
  };
}
